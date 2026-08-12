package com.example.data.agent.runtime

import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Stage 3 Task 2: Local Android Execution Provider.
 * Implements local process execution using ProcessBuilder for explicitly authorized requests.
 * Execution is strictly bounded within the Wasti-controlled workspace.
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
        private val DISALLOWED_ENV_KEYS = setOf(
            "LD_PRELOAD", "LD_LIBRARY_PATH", "DYLD_INSERT_LIBRARIES"
        )
    }

    override suspend fun execute(request: ExecutionRequest): ExecutionResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()

        // 1. Validate Executable Capability
        val execName = File(request.executable).name.lowercase()
        if (!allowedExecutables.contains(execName) && !allowedExecutables.contains("*")) {
            return@withContext ExecutionResult(
                stdout = "",
                stderr = "UNSUPPORTED_EXECUTABLE: Executable '${request.executable}' is not in the approved execution capability catalogue.",
                exitCode = -1,
                executionTimeMs = System.currentTimeMillis() - startTime,
                status = ExecutionStatus(
                    isSuccess = false,
                    message = "UNSUPPORTED_EXECUTABLE: Executable not allowed"
                ),
                errorType = ExecutionErrorType.INVALID_REQUEST
            )
        }

        // 2. Validate Working Directory Containment in Workspace
        val workDirResult = workspaceManager.resolvePathSafely(request.workingDirectory)
        if (workDirResult.isFailure) {
            return@withContext ExecutionResult(
                stdout = "",
                stderr = "SECURITY_BLOCKED: Working directory '${request.workingDirectory}' escapes workspace boundary.",
                exitCode = -1,
                executionTimeMs = System.currentTimeMillis() - startTime,
                status = ExecutionStatus(
                    isSuccess = false,
                    message = "SECURITY_BLOCKED: Directory outside workspace"
                ),
                errorType = ExecutionErrorType.SECURITY
            )
        }
        val targetDirectory = workDirResult.getOrThrow()

        // 3. Construct ProcessBuilder safely
        val command = mutableListOf<String>().apply {
            add(request.executable)
            addAll(request.arguments)
        }

        val processBuilder = ProcessBuilder(command).apply {
            directory(targetDirectory)
            // Sanitize environment overrides
            val env = environment()
            request.environment.forEach { (key, value) ->
                if (!DISALLOWED_ENV_KEYS.contains(key.uppercase())) {
                    env[key] = value
                }
            }
        }

        var process: Process? = null
        try {
            process = processBuilder.start()

            var stdoutStr = ""
            var stderrStr = ""
            var completedInTime = false

            // Read process output stream asynchronously with size limits
            val stdoutThread = Thread {
                stdoutStr = readStreamWithSizeLimit(process.inputStream, maxOutputSizeBytes)
            }
            val stderrThread = Thread {
                stderrStr = readStreamWithSizeLimit(process.errorStream, maxOutputSizeBytes)
            }

            stdoutThread.start()
            stderrThread.start()

            completedInTime = process.waitFor(request.timeoutMs, TimeUnit.MILLISECONDS)

            if (!completedInTime) {
                process.destroyForcibly()
                stdoutThread.join(500)
                stderrThread.join(500)

                return@withContext ExecutionResult(
                    stdout = stdoutStr,
                    stderr = "$stderrStr\nTIMEOUT: Process exceeded maximum execution time of ${request.timeoutMs}ms and was cancelled.",
                    exitCode = -1,
                    executionTimeMs = System.currentTimeMillis() - startTime,
                    status = ExecutionStatus(
                        isSuccess = false,
                        message = "TIMEOUT: Execution timed out"
                    ),
                    errorType = ExecutionErrorType.TIMEOUT
                )
            }

            stdoutThread.join(1000)
            stderrThread.join(1000)

            val exitCode = process.exitValue()
            val executionDuration = System.currentTimeMillis() - startTime
            val isSuccess = (exitCode == 0)

            ExecutionResult(
                stdout = stdoutStr,
                stderr = stderrStr,
                exitCode = exitCode,
                executionTimeMs = executionDuration,
                status = ExecutionStatus(
                    isSuccess = isSuccess,
                    message = if (isSuccess) "Process completed successfully" else "Process failed with exit code $exitCode"
                ),
                errorType = if (isSuccess) ExecutionErrorType.NONE else ExecutionErrorType.RUNTIME
            )
        } catch (e: Exception) {
            process?.destroyForcibly()
            ExecutionResult(
                stdout = "",
                stderr = "EXECUTION_ERROR: ${e.message}",
                exitCode = -1,
                executionTimeMs = System.currentTimeMillis() - startTime,
                status = ExecutionStatus(
                    isSuccess = false,
                    message = "EXECUTION_ERROR: ${e.message}"
                ),
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
                if (allowed > 0) {
                    sb.append(String(buffer, 0, allowed, Charsets.UTF_8))
                }
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
