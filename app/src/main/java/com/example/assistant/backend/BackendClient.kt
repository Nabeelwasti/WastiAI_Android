package com.example.assistant.backend

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Detailed health and reachability telemetry for the backend endpoint.
 */
data class BackendHealthStatus(
    val isReachable: Boolean,
    val httpCode: Int = 0,
    val status: String = "unreachable",
    val timestamp: Long = 0L,
    val latencyMs: Long = -1L,
    val githubConfigured: Boolean = false,
    val brevoConfigured: Boolean = false,
    val stripeConfigured: Boolean = false,
    val firebaseConfigured: Boolean = false,
    val authEnforced: Boolean = false,
    val errorMessage: String? = null
)

/**
 * BackendClient: HTTP client to call the backend endpoints (/health, /llm, /dev/patch, etc.).
 * Reads base URL from BuildConfig or environment at runtime.
 * Implements strict URL validation, emergency stop checks, observable health telemetry,
 * and fail-closed handling without synthetic success.
 */
object BackendClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    init {
        try {
            com.example.data.di.WastiServiceLocator.emergencyStopController.registerOkHttpClient(client)
        } catch (_: Throwable) {}
    }

    private fun isEmergencyStopped(): Boolean {
        return try {
            com.example.data.di.WastiServiceLocator.emergencyStopController.isEmergencyStopped
        } catch (_: Throwable) {
            false
        }
    }

    fun isValidUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return try {
            val uri = URI(url.trim())
            val scheme = uri.scheme?.lowercase()
            (scheme == "http" || scheme == "https") && !uri.host.isNullOrBlank()
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Probes the backend /health endpoint, returning structured telemetry.
     * Fails closed with descriptive errors when unreachable.
     */
    suspend fun checkHealth(
        baseUrl: String,
        authToken: String? = null,
        timeoutMs: Long = 5000
    ): BackendHealthStatus = withContext(Dispatchers.IO) {
        if (isEmergencyStopped()) {
            Log.w("BackendClient", "checkHealth rejected: emergency stop active")
            return@withContext BackendHealthStatus(
                isReachable = false,
                errorMessage = "Emergency stop active"
            )
        }

        val trimmedUrl = baseUrl.trim().trimEnd('/')
        if (!isValidUrl(trimmedUrl)) {
            Log.e("BackendClient", "checkHealth rejected: invalid baseUrl '$baseUrl'")
            return@withContext BackendHealthStatus(
                isReachable = false,
                errorMessage = "Invalid base URL: $baseUrl"
            )
        }

        val startTime = System.currentTimeMillis()
        try {
            val url = "$trimmedUrl/health"
            val reqBuilder = Request.Builder().url(url).get()
            if (!authToken.isNullOrBlank()) {
                reqBuilder.header("Authorization", "Bearer $authToken")
                reqBuilder.header("x-wasti-auth-token", authToken)
            }

            val healthClient = client.newBuilder()
                .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .build()

            healthClient.newCall(reqBuilder.build()).execute().use { resp ->
                val latency = System.currentTimeMillis() - startTime
                val body = resp.body?.string().orEmpty()

                if (resp.isSuccessful) {
                    try {
                        val json = JSONObject(body)
                        BackendHealthStatus(
                            isReachable = true,
                            httpCode = resp.code,
                            status = json.optString("status", "ok"),
                            timestamp = json.optLong("timestamp", System.currentTimeMillis()),
                            latencyMs = latency,
                            githubConfigured = json.optBoolean("githubConfigured", false),
                            brevoConfigured = json.optBoolean("brevoConfigured", false),
                            stripeConfigured = json.optBoolean("stripeConfigured", false),
                            firebaseConfigured = json.optBoolean("firebaseConfigured", false),
                            authEnforced = json.optBoolean("authEnforced", false)
                        )
                    } catch (e: Exception) {
                        BackendHealthStatus(
                            isReachable = true,
                            httpCode = resp.code,
                            status = "non_json_response",
                            latencyMs = latency,
                            errorMessage = "Failed to parse health JSON: ${e.message}"
                        )
                    }
                } else {
                    BackendHealthStatus(
                        isReachable = false,
                        httpCode = resp.code,
                        status = "http_error_${resp.code}",
                        latencyMs = latency,
                        errorMessage = "Backend health probe returned HTTP ${resp.code}: $body"
                    )
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            val latency = System.currentTimeMillis() - startTime
            Log.e("BackendClient", "checkHealth exception for $baseUrl", e)
            BackendHealthStatus(
                isReachable = false,
                latencyMs = latency,
                errorMessage = e.message ?: "Network error connecting to $baseUrl"
            )
        }
    }

    /**
     * Fast reachability probe for any HTTP/HTTPS endpoint URL.
     */
    suspend fun isEndpointReachable(
        endpointUrl: String,
        timeoutMs: Long = 3000
    ): Boolean = withContext(Dispatchers.IO) {
        if (isEmergencyStopped()) return@withContext false
        val trimmed = endpointUrl.trim()
        if (!isValidUrl(trimmed)) return@withContext false

        try {
            val probeClient = client.newBuilder()
                .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .build()
            val req = Request.Builder().url(trimmed).head().build()
            probeClient.newCall(req).execute().use { resp ->
                resp.code > 0
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            false
        }
    }

    suspend fun callLLM(baseUrl: String, provider: String, payloadJson: String, authToken: String? = null): String? {
        return withContext(Dispatchers.IO) {
            if (isEmergencyStopped()) {
                Log.w("BackendClient", "callLLM rejected: emergency stop active")
                return@withContext null
            }
            val trimmedUrl = baseUrl.trim().trimEnd('/')
            if (!isValidUrl(trimmedUrl)) {
                Log.e("BackendClient", "callLLM rejected: invalid baseUrl '$baseUrl'")
                return@withContext null
            }
            try {
                val url = "$trimmedUrl/llm"
                val body: RequestBody = payloadJson.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
                val reqBuilder = Request.Builder().url(url).post(body)
                if (!authToken.isNullOrBlank()) {
                    reqBuilder.header("Authorization", "Bearer $authToken")
                    reqBuilder.header("x-wasti-auth-token", authToken)
                }
                client.newCall(reqBuilder.build()).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Log.e("BackendClient", "LLM call failed: ${resp.code}")
                        return@withContext null
                    }
                    return@withContext resp.body?.string()
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e("BackendClient", "LLM call exception", e)
                null
            }
        }
    }

    suspend fun createDevPatch(baseUrl: String, owner: String, repo: String, title: String, bodyJson: String, changesJson: String, authToken: String? = null): String? {
        return withContext(Dispatchers.IO) {
            if (isEmergencyStopped()) {
                Log.w("BackendClient", "createDevPatch rejected: emergency stop active")
                return@withContext null
            }
            val trimmedUrl = baseUrl.trim().trimEnd('/')
            if (!isValidUrl(trimmedUrl)) {
                Log.e("BackendClient", "createDevPatch rejected: invalid baseUrl '$baseUrl'")
                return@withContext null
            }
            try {
                val url = "$trimmedUrl/dev/patch"
                val payload = "{\"owner\":\"$owner\",\"repo\":\"$repo\",\"title\":\"$title\",\"body\":$bodyJson,\"changes\":$changesJson}"
                val body: RequestBody = payload.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
                val reqBuilder = Request.Builder().url(url).post(body)
                if (!authToken.isNullOrBlank()) {
                    reqBuilder.header("Authorization", "Bearer $authToken")
                    reqBuilder.header("x-wasti-auth-token", authToken)
                }
                client.newCall(reqBuilder.build()).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Log.e("BackendClient", "dev/patch failed: ${resp.code}")
                        return@withContext null
                    }
                    return@withContext resp.body?.string()
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e("BackendClient", "dev/patch exception", e)
                null
            }
        }
    }

    suspend fun sendWakeword(baseUrl: String, eventPayloadJson: String, token: String? = null, authToken: String? = null): String? {
        return withContext(Dispatchers.IO) {
            if (isEmergencyStopped()) {
                Log.w("BackendClient", "sendWakeword rejected: emergency stop active")
                return@withContext null
            }
            val trimmedUrl = baseUrl.trim().trimEnd('/')
            if (!isValidUrl(trimmedUrl)) {
                Log.e("BackendClient", "sendWakeword rejected: invalid baseUrl '$baseUrl'")
                return@withContext null
            }
            try {
                val url = "$trimmedUrl/wakeword"
                val tokenField = if (token != null) ",\"token\":\"$token\"" else ""
                val payload = "{\"event\":$eventPayloadJson$tokenField}"
                val body: RequestBody = payload.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
                val reqBuilder = Request.Builder().url(url).post(body)
                if (!authToken.isNullOrBlank()) {
                    reqBuilder.header("Authorization", "Bearer $authToken")
                    reqBuilder.header("x-wasti-auth-token", authToken)
                }
                client.newCall(reqBuilder.build()).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Log.e("BackendClient", "wakeword failed: ${resp.code}")
                        return@withContext null
                    }
                    return@withContext resp.body?.string()
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e("BackendClient", "wakeword exception", e)
                null
            }
        }
    }

    suspend fun getWakewordQueueStatus(baseUrl: String, limit: Int = 20, authToken: String? = null): String? {
        return withContext(Dispatchers.IO) {
            if (isEmergencyStopped()) {
                Log.w("BackendClient", "getWakewordQueueStatus rejected: emergency stop active")
                return@withContext null
            }
            val trimmedUrl = baseUrl.trim().trimEnd('/')
            if (!isValidUrl(trimmedUrl)) {
                Log.e("BackendClient", "getWakewordQueueStatus rejected: invalid baseUrl '$baseUrl'")
                return@withContext null
            }
            try {
                val url = "$trimmedUrl/wakeword/queue?limit=$limit"
                val reqBuilder = Request.Builder().url(url).get()
                if (!authToken.isNullOrBlank()) {
                    reqBuilder.header("Authorization", "Bearer $authToken")
                    reqBuilder.header("x-wasti-auth-token", authToken)
                }
                client.newCall(reqBuilder.build()).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Log.e("BackendClient", "getWakewordQueueStatus failed: ${resp.code}")
                        return@withContext null
                    }
                    return@withContext resp.body?.string()
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e("BackendClient", "getWakewordQueueStatus exception", e)
                null
            }
        }
    }

    suspend fun dequeueWakewordEvents(baseUrl: String, limit: Int = 10, authToken: String? = null): String? {
        return withContext(Dispatchers.IO) {
            if (isEmergencyStopped()) {
                Log.w("BackendClient", "dequeueWakewordEvents rejected: emergency stop active")
                return@withContext null
            }
            val trimmedUrl = baseUrl.trim().trimEnd('/')
            if (!isValidUrl(trimmedUrl)) {
                Log.e("BackendClient", "dequeueWakewordEvents rejected: invalid baseUrl '$baseUrl'")
                return@withContext null
            }
            try {
                val url = "$trimmedUrl/wakeword/dequeue"
                val payload = "{\"limit\":$limit}"
                val body: RequestBody = payload.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
                val reqBuilder = Request.Builder().url(url).post(body)
                if (!authToken.isNullOrBlank()) {
                    reqBuilder.header("Authorization", "Bearer $authToken")
                    reqBuilder.header("x-wasti-auth-token", authToken)
                }
                client.newCall(reqBuilder.build()).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Log.e("BackendClient", "dequeueWakewordEvents failed: ${resp.code}")
                        return@withContext null
                    }
                    return@withContext resp.body?.string()
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e("BackendClient", "dequeueWakewordEvents exception", e)
                null
            }
        }
    }

    suspend fun acknowledgeWakewordEvents(baseUrl: String, eventIds: List<String>, authToken: String? = null): String? {
        return withContext(Dispatchers.IO) {
            if (isEmergencyStopped()) {
                Log.w("BackendClient", "acknowledgeWakewordEvents rejected: emergency stop active")
                return@withContext null
            }
            val trimmedUrl = baseUrl.trim().trimEnd('/')
            if (!isValidUrl(trimmedUrl)) {
                Log.e("BackendClient", "acknowledgeWakewordEvents rejected: invalid baseUrl '$baseUrl'")
                return@withContext null
            }
            try {
                val url = "$trimmedUrl/wakeword/ack"
                val jsonArray = eventIds.joinToString(prefix = "[\"", separator = "\",\"", postfix = "\"]")
                val payload = "{\"eventIds\":$jsonArray}"
                val body: RequestBody = payload.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
                val reqBuilder = Request.Builder().url(url).post(body)
                if (!authToken.isNullOrBlank()) {
                    reqBuilder.header("Authorization", "Bearer $authToken")
                    reqBuilder.header("x-wasti-auth-token", authToken)
                }
                client.newCall(reqBuilder.build()).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Log.e("BackendClient", "acknowledgeWakewordEvents failed: ${resp.code}")
                        return@withContext null
                    }
                    return@withContext resp.body?.string()
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e("BackendClient", "acknowledgeWakewordEvents exception", e)
                null
            }
        }
    }

    suspend fun sendEmail(baseUrl: String, to: String, subject: String, html: String, approvalToken: String, authToken: String? = null): String? {
        return withContext(Dispatchers.IO) {
            if (isEmergencyStopped()) {
                Log.w("BackendClient", "sendEmail rejected: emergency stop active")
                return@withContext null
            }
            val trimmedUrl = baseUrl.trim().trimEnd('/')
            if (!isValidUrl(trimmedUrl)) {
                Log.e("BackendClient", "sendEmail rejected: invalid baseUrl '$baseUrl'")
                return@withContext null
            }
            try {
                val url = "$trimmedUrl/email/send"
                val escapedSubject = subject.replace("\"", "\\\"")
                val escapedHtml = html.replace("\"", "\\\"")
                val payload = "{\"to\":\"$to\",\"subject\":\"$escapedSubject\",\"html\":\"$escapedHtml\"}"
                val body: RequestBody = payload.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
                val reqBuilder = Request.Builder().url(url).post(body)
                    .header("x-approval-token", approvalToken)
                if (!authToken.isNullOrBlank()) {
                    reqBuilder.header("Authorization", "Bearer $authToken")
                    reqBuilder.header("x-wasti-auth-token", authToken)
                }
                client.newCall(reqBuilder.build()).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Log.e("BackendClient", "email/send failed: ${resp.code}")
                        return@withContext null
                    }
                    return@withContext resp.body?.string()
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e("BackendClient", "email/send exception", e)
                null
            }
        }
    }
}
