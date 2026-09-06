package com.example.data.agent.runtime

import com.example.data.credential.CredentialRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Stage 3 Task 3: Remote Sandbox Code Execution Provider.
 * Abstraction for remote isolated code execution (e.g. Judge0 or custom HTTP sandbox).
 * Does NOT hard-code credentials or endpoints into core runtime.
 */
class RemoteSandboxCodeExecutionProvider(
    private val endpointUrlSupplier: () -> String? = { CredentialRegistry.getRawValue("REMOTE_SANDBOX_URL") ?: CredentialRegistry.getRawValue("JUDGE0_URL") },
    private val apiKeySupplier: () -> String? = { CredentialRegistry.getRawValue("REMOTE_SANDBOX_API_KEY") ?: CredentialRegistry.getRawValue("JUDGE0_API_KEY") },
    private val httpClientAdapter: RemoteSandboxHttpClient? = DefaultRemoteSandboxHttpClient
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

/**
 * Production OkHttp-based implementation for remote code execution sandboxes.
 */
object DefaultRemoteSandboxHttpClient : RemoteSandboxCodeExecutionProvider.RemoteSandboxHttpClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override suspend fun postExecutionRequest(
        endpoint: String,
        apiKey: String?,
        request: ExecutionRequest
    ): RemoteSandboxCodeExecutionProvider.RemoteSandboxResponse = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply {
                put("executable", request.executable)
                put("arguments", JSONArray(request.arguments))
                put("workingDirectory", request.workingDirectory)
                put("language", request.language ?: "")
                put("timeoutMs", request.timeoutMs)
                val envObj = JSONObject()
                request.environment.forEach { (k, v) -> envObj.put(k, v) }
                put("environment", envObj)
            }
            val body = json.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
            val reqBuilder = Request.Builder().url(endpoint).post(body)
            if (!apiKey.isNullOrBlank()) {
                reqBuilder.header("Authorization", "Bearer $apiKey")
                reqBuilder.header("x-api-key", apiKey)
            }
            client.newCall(reqBuilder.build()).execute().use { resp ->
                val respBody = resp.body?.string().orEmpty()
                val isAuthFailed = resp.code == 401 || resp.code == 403
                val isQuota = resp.code == 429
                val isServiceDown = resp.code >= 500
                if (resp.isSuccessful) {
                    try {
                        val parsed = JSONObject(respBody)
                        RemoteSandboxCodeExecutionProvider.RemoteSandboxResponse(
                            statusCode = resp.code,
                            stdout = parsed.optString("stdout", ""),
                            stderr = parsed.optString("stderr", ""),
                            exitCode = parsed.optInt("exitCode", 0),
                            compileOutput = if (parsed.has("compileOutput") && !parsed.isNull("compileOutput")) parsed.optString("compileOutput") else null
                        )
                    } catch (_: Exception) {
                        RemoteSandboxCodeExecutionProvider.RemoteSandboxResponse(
                            statusCode = resp.code,
                            stdout = respBody,
                            exitCode = 0
                        )
                    }
                } else {
                    RemoteSandboxCodeExecutionProvider.RemoteSandboxResponse(
                        statusCode = resp.code,
                        stderr = respBody,
                        isAuthFailed = isAuthFailed,
                        isQuotaExhausted = isQuota,
                        isServiceUnavailable = isServiceDown
                    )
                }
            }
        } catch (e: java.net.SocketTimeoutException) {
            RemoteSandboxCodeExecutionProvider.RemoteSandboxResponse(
                statusCode = 408,
                isTimedOut = true,
                stderr = "Socket timeout connecting to remote sandbox"
            )
        } catch (e: Exception) {
            RemoteSandboxCodeExecutionProvider.RemoteSandboxResponse(
                statusCode = 500,
                isServiceUnavailable = true,
                stderr = "Remote sandbox HTTP error: ${e.message}"
            )
        }
    }
}
