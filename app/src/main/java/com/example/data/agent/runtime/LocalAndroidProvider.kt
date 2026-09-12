package com.example.data.agent.runtime

import android.os.Build
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Stage 3 Task 2: Local Android Execution Provider.
 * Uses ProcessBuilder for explicitly authorized requests inside the Wasti workspace.
 * This is software containment, not a kernel/OS sandbox; callers must not label it as one.
 */
class LocalAndroidProvider(
    private val workspaceManager: WorkspaceManager,
    private val allowedExecutables: Set<String> = DEFAULT_ALLOWED_EXECUTABLES,
    private val maxOutputSizeBytes: Int = DEFAULT_MAX_OUTPUT_SIZE_BYTES
) : CodeExecutionProvider {

    companion object {
        val DEFAULT_ALLOWED_EXECUTABLES = setOf(
            "sh", "dalvikvm", "kotlinc", "javac", "java", "python3", "echo", "cat", "ls", "pwd", "true"
        )
        const val DEFAULT_MAX_OUTPUT_SIZE_BYTES = 1048576 // 1 MB

        // Environment variables that can alter interpreter/class-loader behavior or
        // redirect executable resolution are never accepted from an execution request.
        private val DISALLOWED_ENV_KEYS = setOf(
            "PATH", "CLASSPATH", "JAVA_HOME", "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS",
            "PYTHONPATH", "PYTHONHOME", "PYTHONSTARTUP", "NODE_OPTIONS", "NODE_PATH", "RUBYLIB", "PERL5LIB",
            "BASH_ENV", "ENV", "LD_PRELOAD", "LD_LIBRARY_PATH", "LD_AUDIT", "DYLD_INSERT_LIBRARIES",
            "DYLD_LIBRARY_PATH"
        )
    }

    override suspend fun execute(request: ExecutionRequest): ExecutionResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()

        // Executable names are capability identifiers. Do not allow a caller to supply
        // an arbitrary path whose basename merely happens to match an approved name.
        val requestedExecutable = request.executable.trim()
        val execName = File(requestedExecutable).name.lowercase()
        if (requestedExecutable.isBlank() || requestedExecutable.contains('/') || requestedExecutable.contains('\\') ||
            !allowedExecutables.contains(execName) && !allowedExecutables.contains("*")) {
            return@withContext ExecutionResult(
                stdout = "",
                stderr = "SECURITY_BLOCKED: Executable must be an approved command name; arbitrary executable paths are not permitted.",
                exitCode = -1,
                executionTimeMs = System.currentTimeMillis() - startTime,
                status = ExecutionStatus(false, "SECURITY_BLOCKED: Executable not allowed"),
                errorType = ExecutionErrorType.SECURITY
            )
        }

        // Workspace containment is canonicalized by WorkspaceManager.
        val workDirResult = workspaceManager.resolvePathSafely(request.workingDirectory)
        if (workDirResult.isFailure) {
            return@withContext ExecutionResult(
                stdout = "",
                stderr = "SECURITY_BLOCKED: Working directory '${request.workingDirectory}' escapes workspace boundary.",
                exitCode = -1,
                executionTimeMs = System.currentTimeMillis() - startTime,
                status = ExecutionStatus(false, "SECURITY_BLOCKED: Directory outside workspace"),
                errorType = ExecutionErrorType.SECURITY
            )
        }
        val targetDirectory = workDirResult.getOrThrow()

        val command = mutableListOf<String>().apply {
            add(requestedExecutable)
            addAll(request.arguments)
        }

        val processBuilder = ProcessBuilder(command).apply {
            directory(targetDirectory)
            // Preserve the trusted process environment and reject request-controlled
            // variables that can redirect interpreters, class loaders, or command lookup.
            val env = environment()
            request.environment.forEach { (key, value) ->
                if (!DISALLOWED_ENV_KEYS.contains(key.uppercase())) env[key] = value
            }
        }

        var process: Process? = null
        try {
            process = processBuilder.start()
            try { process.outputStream.close() } catch (_: Exception) {}

            var stdoutStr = ""
            var stderrStr = ""

            val stdoutThread = Thread { stdoutStr = readStreamWithSizeLimit(process.inputStream, maxOutputSizeBytes) }
            val stderrThread = Thread { stderrStr = readStreamWithSizeLimit(process.errorStream, maxOutputSizeBytes) }
            stdoutThread.start()
            stderrThread.start()

            val completedInTime = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                process.waitFor(request.timeoutMs, TimeUnit.MILLISECONDS)
            } else {
                val checkInterval = 50L
                var elapsed = 0L
                var finished = false
                while (elapsed < request.timeoutMs) {
                    try {
                        process.exitValue()
                        finished = true
                        break
                    } catch (_: IllegalThreadStateException) {
                        Thread.sleep(checkInterval)
                        elapsed += checkInterval
                    }
                }
                finished
            }

            if (!completedInTime) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) process.destroyForcibly() else process.destroy()
                stdoutThread.join(500)
                stderrThread.join(500)
                return@withContext ExecutionResult(
                    stdout = stdoutStr,
                    stderr = "$stderrStr\nTIMEOUT: Process exceeded maximum execution time of ${request.timeoutMs}ms and was cancelled.",
                    exitCode = -1,
                    executionTimeMs = System.currentTimeMillis() - startTime,
                    status = ExecutionStatus(false, "TIMEOUT: Execution timed out"),
                    errorType = ExecutionErrorType.TIMEOUT
                )
            }

            stdoutThread.join(1000)
            stderrThread.join(1000)
            val exitCode = process.exitValue()
            val executionDuration = System.currentTimeMillis() - startTime
            val isSuccess = exitCode == 0

            ExecutionResult(
                stdout = stdoutStr,
                stderr = stderrStr,
                exitCode = exitCode,
                executionTimeMs = executionDuration,
                status = ExecutionStatus(isSuccess, if (isSuccess) "Process completed successfully" else "Process failed with exit code $exitCode"),
                errorType = if (isSuccess) ExecutionErrorType.NONE else ExecutionErrorType.RUNTIME
            )
        } catch (e: Exception) {
            process?.let { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) it.destroyForcibly() else it.destroy() }
            ExecutionResult(
                stdout = "",
                stderr = "EXECUTION_ERROR: ${e.message}",
                exitCode = -1,
                executionTimeMs = System.currentTimeMillis() - startTime,
                status = ExecutionStatus(false, "EXECUTION_ERROR: ${e.message}"),
                errorType = ExecutionErrorType.RUNTIME
            )
        }
    }

    private fun readStreamWithSizeLimit(inputStream: InputStream, maxBytes: Int): String {
        val buffer = ByteArray(4096)
        val sb = StringBuilder()
        var totalRead = 0
        var bytesRead: Int
        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
            if (totalRead + bytesRead > maxBytes) {
                val allowed = maxBytes - totalRead
                if (allowed > 0) sb.append(String(buffer, 0, allowed, Charsets.UTF_8))
                sb.append("\n[OUTPUT TRUNCATED: Exceeded $maxBytes bytes limit]")
                break
            } else {
                sb.append(String(buffer, 0, bytesRead, Charsets.UTF_8))
                totalRead += bytesRead
            }
        }
        return sb.toString()
    }
}
