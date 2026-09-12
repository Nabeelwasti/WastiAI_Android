package com.example.data.agent.runtime

import android.os.Build
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Wasti's local process provider. This is workspace-constrained software containment,
 * not an OS/kernel sandbox. Canonical system runtime paths are explicitly allowlisted.
 *
 * Execution duration is observational telemetry only. A long-running process is not
 * killed merely because a caller supplied timeoutMs. Explicit cancellation, emergency
 * stop, or an actual process/runtime failure remains the termination authority.
 */
class LocalAndroidProvider(
    private val workspaceManager: WorkspaceManager,
    private val allowedExecutables: Set<String> = DEFAULT_ALLOWED_EXECUTABLES,
    private val maxOutputSizeBytes: Int = DEFAULT_MAX_OUTPUT_SIZE_BYTES
) : CodeExecutionProvider {
    companion object {
        val DEFAULT_ALLOWED_EXECUTABLES = setOf("sh", "dalvikvm", "kotlinc", "javac", "java", "python3", "echo", "cat", "ls", "pwd", "true")
        const val DEFAULT_MAX_OUTPUT_SIZE_BYTES = 1048576
        private val TRUSTED_EXECUTABLE_PATHS = setOf(
            "/system/bin/sh", "/bin/sh", "/usr/bin/sh",
            "/system/bin/dalvikvm", "/system/bin/python3", "/system/bin/python",
            "/data/local/tmp/python3", "/system/bin/node", "/data/local/tmp/node",
            "/system/bin/git", "/usr/bin/git", "/bin/echo", "/usr/bin/echo",
            "/bin/cat", "/usr/bin/cat", "/bin/ls", "/usr/bin/ls", "/bin/pwd", "/usr/bin/pwd",
            "/bin/true", "/usr/bin/true"
        )
        private val DISALLOWED_ENV_KEYS = setOf(
            "PATH", "CLASSPATH", "JAVA_HOME", "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS",
            "PYTHONPATH", "PYTHONHOME", "PYTHONSTARTUP", "NODE_OPTIONS", "NODE_PATH", "RUBYLIB", "PERL5LIB",
            "BASH_ENV", "ENV", "LD_PRELOAD", "LD_LIBRARY_PATH", "LD_AUDIT", "DYLD_INSERT_LIBRARIES", "DYLD_LIBRARY_PATH"
        )
    }

    override suspend fun execute(request: ExecutionRequest): ExecutionResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val requested = request.executable.trim()
        if (requested.isBlank()) return@withContext invalidRequest("INVALID_REQUEST: UNSUPPORTED_EXECUTABLE: executable is empty")

        val isPath = requested.contains('/') || requested.contains('\\')
        val execName = File(requested).name.lowercase()
        val approvedName = allowedExecutables.contains(execName) || allowedExecutables.contains("*")
        val approvedPath = TRUSTED_EXECUTABLE_PATHS.contains(requested)
        if ((!isPath && !approvedName) || (isPath && !approvedPath)) {
            return@withContext invalidRequest("INVALID_REQUEST: UNSUPPORTED_EXECUTABLE: '$requested' is not an approved executable")
        }

        val workDir = workspaceManager.resolvePathSafely(request.workingDirectory)
            .getOrElse { return@withContext securityBlocked("SECURITY_BLOCKED: Working directory escapes workspace boundary") }

        val command = mutableListOf(requested).apply { addAll(request.arguments) }
        val processBuilder = ProcessBuilder(command).apply {
            directory(workDir)
            val env = environment()
            request.environment.forEach { (key, value) -> if (!DISALLOWED_ENV_KEYS.contains(key.uppercase())) env[key] = value }
        }

        var process: Process? = null
        try {
            process = processBuilder.start()
            try { process.outputStream.close() } catch (_: Exception) {}
            var stdout = ""
            var stderr = ""
            val stdoutThread = Thread { stdout = readStreamWithSizeLimit(process.inputStream, maxOutputSizeBytes) }
            val stderrThread = Thread { stderr = readStreamWithSizeLimit(process.errorStream, maxOutputSizeBytes) }
            stdoutThread.start(); stderrThread.start()

            // IMPORTANT: timeoutMs is retained as caller telemetry/configuration for compatibility,
            // but is never a blind process-kill deadline. Observe until the real process exits.
            while (true) {
                try {
                    val exitCode = process.exitValue()
                    stdoutThread.join(1000)
                    stderrThread.join(1000)
                    val ok = exitCode == 0
                    return@withContext ExecutionResult(
                        stdout,
                        stderr,
                        exitCode,
                        System.currentTimeMillis() - startTime,
                        ExecutionStatus(ok, if (ok) "Process completed successfully" else "Process failed with exit code $exitCode"),
                        if (ok) ExecutionErrorType.NONE else ExecutionErrorType.RUNTIME
                    )
                } catch (_: IllegalThreadStateException) {
                    Thread.sleep(250)
                }
            }
        } catch (e: InterruptedException) {
            // Interruption is an explicit cancellation/termination signal, not a duration timeout.
            Thread.currentThread().interrupt()
            process?.let { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) it.destroyForcibly() else it.destroy() }
            ExecutionResult("", "EXECUTION_CANCELLED: ${e.message ?: "Execution thread interrupted"}", -1, System.currentTimeMillis() - startTime, ExecutionStatus(false, "EXECUTION_CANCELLED"), ExecutionErrorType.TIMEOUT)
        } catch (e: Exception) {
            process?.let { if (it.isAlive) { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) it.destroyForcibly() else it.destroy() } }
            ExecutionResult("", "EXECUTION_ERROR: ${e.message}", -1, System.currentTimeMillis() - startTime, ExecutionStatus(false, "EXECUTION_ERROR: ${e.message}"), ExecutionErrorType.RUNTIME)
        }
    }

    private fun invalidRequest(message: String) = ExecutionResult("", message, -1, 0L, ExecutionStatus(false, message), ExecutionErrorType.INVALID_REQUEST)
    private fun securityBlocked(message: String) = ExecutionResult("", message, -1, 0L, ExecutionStatus(false, message), ExecutionErrorType.SECURITY)

    private fun readStreamWithSizeLimit(inputStream: InputStream, maxBytes: Int): String {
        val buffer = ByteArray(4096); val sb = StringBuilder(); var total = 0
        while (true) {
            val n = inputStream.read(buffer); if (n == -1) break
            if (total + n > maxBytes) {
                val allowed = maxBytes - total
                if (allowed > 0) sb.append(String(buffer, 0, allowed, Charsets.UTF_8))
                sb.append("\n[OUTPUT TRUNCATED: Exceeded $maxBytes bytes limit]"); break
            }
            sb.append(String(buffer, 0, n, Charsets.UTF_8)); total += n
        }
        return sb.toString()
    }
}
