package com.example.data.agent.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Stage 3 Task 3: Remote Sandbox Code Execution Provider.
 * Abstraction for remote isolated code execution (e.g. Judge0 or custom HTTP sandbox).
 * Does NOT hard-code credentials or endpoints into core runtime.
 */
class RemoteSandboxCodeExecutionProvider(
    private val endpointUrlSupplier: () -> String? = { null },
    private val apiKeySupplier: () -> String? = { null },
    private val httpClientAdapter: RemoteSandboxHttpClient? = null
) : CodeExecutionProvider {

    interface RemoteSandboxHttpClient {
        suspend fun postExecutionRequest(
            endpoint: String,
            apiKey: String?,
            request: ExecutionRequest
        ): RemoteSandboxResponse
    }

    data class RemoteSandboxResponse(
        val statusCode: Int, // HTTP Status code
        val stdout: String? = null,
        val stderr: String? = null,
        val exitCode: Int? = null,
        val compileOutput: String? = null,
        val isQuotaExhausted: Boolean = false,
        val isAuthFailed: Boolean = false,
        val isTimedOut: Boolean = false,
        val isServiceUnavailable: Boolean = false,
        val isMalformed: Boolean = false
    )

    override suspend fun execute(request: ExecutionRequest): ExecutionResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()

        val endpoint = endpointUrlSupplier()
        if (endpoint.isNullOrBlank()) {
            return@withContext ExecutionResult(
                stdout = "",
                stderr = "PROVIDER_UNAVAILABLE: Remote sandbox endpoint URL is not configured",
                exitCode = -1,
                executionTimeMs = System.currentTimeMillis() - startTime,
                status = ExecutionStatus(
                    isSuccess = false,
                    message = "PROVIDER_UNAVAILABLE: Missing remote endpoint"
                ),
                errorType = ExecutionErrorType.PROVIDER_UNAVAILABLE
            )
        }

        val apiKey = apiKeySupplier()

        if (httpClientAdapter == null) {
            return@withContext ExecutionResult(
                stdout = "",
                stderr = "PROVIDER_UNAVAILABLE: No HTTP transport adapter configured for remote sandbox provider",
                exitCode = -1,
                executionTimeMs = System.currentTimeMillis() - startTime,
                status = ExecutionStatus(
                    isSuccess = false,
                    message = "PROVIDER_UNAVAILABLE: Missing HTTP adapter"
                ),
                errorType = ExecutionErrorType.PROVIDER_UNAVAILABLE
            )
        }

        try {
            val response = httpClientAdapter.postExecutionRequest(endpoint, apiKey, request)
            val duration = System.currentTimeMillis() - startTime

            when {
                response.isAuthFailed || response.statusCode == 401 || response.statusCode == 403 -> {
                    ExecutionResult(
                        stdout = "",
                        stderr = "AUTHENTICATION_FAILED: Remote sandbox rejected API key or credentials (HTTP ${response.statusCode})",
                        exitCode = -1,
                        executionTimeMs = duration,
                        status = ExecutionStatus(isSuccess = false, message = "AUTHENTICATION_FAILED"),
                        errorType = ExecutionErrorType.AUTHENTICATION_FAILED
                    )
                }
                response.isQuotaExhausted || response.statusCode == 429 -> {
                    ExecutionResult(
                        stdout = "",
                        stderr = "QUOTA_EXHAUSTED: Remote sandbox rate limit or execution quota exceeded (HTTP ${response.statusCode})",
                        exitCode = -1,
                        executionTimeMs = duration,
                        status = ExecutionStatus(isSuccess = false, message = "QUOTA_EXHAUSTED"),
                        errorType = ExecutionErrorType.QUOTA_EXHAUSTED
                    )
                }
                response.isServiceUnavailable || response.statusCode >= 500 -> {
                    ExecutionResult(
                        stdout = "",
                        stderr = "PROVIDER_UNAVAILABLE: Remote sandbox service is down or returning errors (HTTP ${response.statusCode})",
                        exitCode = -1,
                        executionTimeMs = duration,
                        status = ExecutionStatus(isSuccess = false, message = "PROVIDER_UNAVAILABLE"),
                        errorType = ExecutionErrorType.PROVIDER_UNAVAILABLE
                    )
                }
                response.isTimedOut -> {
                    ExecutionResult(
                        stdout = response.stdout.orEmpty(),
                        stderr = "${response.stderr.orEmpty()}\nTIMEOUT: Remote execution exceeded timeout limit",
                        exitCode = -1,
                        executionTimeMs = duration,
                        status = ExecutionStatus(isSuccess = false, message = "TIMEOUT"),
                        errorType = ExecutionErrorType.TIMEOUT
                    )
                }
                response.isMalformed -> {
                    ExecutionResult(
                        stdout = "",
                        stderr = "MALFORMED_RESPONSE: Unable to parse remote sandbox response",
                        exitCode = -1,
                        executionTimeMs = duration,
                        status = ExecutionStatus(isSuccess = false, message = "MALFORMED_RESPONSE"),
                        errorType = ExecutionErrorType.INVALID_REQUEST
                    )
                }
                !response.compileOutput.isNullOrBlank() -> {
                    ExecutionResult(
                        stdout = response.stdout.orEmpty(),
                        stderr = response.compileOutput,
                        exitCode = response.exitCode ?: -1,
                        executionTimeMs = duration,
                        status = ExecutionStatus(isSuccess = false, message = "COMPILATION_ERROR"),
                        errorType = ExecutionErrorType.COMPILATION
                    )
                }
                else -> {
                    val exit = response.exitCode ?: 0
                    val isSuccess = (exit == 0)
                    ExecutionResult(
                        stdout = response.stdout.orEmpty(),
                        stderr = response.stderr.orEmpty(),
                        exitCode = exit,
                        executionTimeMs = duration,
                        status = ExecutionStatus(
                            isSuccess = isSuccess,
                            message = if (isSuccess) "Remote execution succeeded" else "Remote execution failed with exit code $exit"
                        ),
                        errorType = if (isSuccess) ExecutionErrorType.NONE else ExecutionErrorType.RUNTIME
                    )
                }
            }
        } catch (e: Exception) {
            ExecutionResult(
                stdout = "",
                stderr = "NETWORK_ERROR: ${e.message}",
                exitCode = -1,
                executionTimeMs = System.currentTimeMillis() - startTime,
                status = ExecutionStatus(isSuccess = false, message = "NETWORK_ERROR: ${e.message}"),
                errorType = ExecutionErrorType.NETWORK
            )
        }
    }
}
