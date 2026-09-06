package com.example.assistant.backend

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * BackendClient: small HTTP client to call the backend endpoints (/llm and /dev/patch).
 * Reads base URL from BuildConfig at runtime. Keep calls simple and safe.
 */
object BackendClient {
    private val client = OkHttpClient()

    suspend fun callLLM(baseUrl: String, provider: String, payloadJson: String, authToken: String? = null): String? {
        return withContext(Dispatchers.IO) {
            try {
                val url = "$baseUrl/llm"
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
                Log.e("BackendClient", "LLM call exception", e)
                null
            }
        }
    }

    suspend fun createDevPatch(baseUrl: String, owner: String, repo: String, title: String, bodyJson: String, changesJson: String, authToken: String? = null): String? {
        return withContext(Dispatchers.IO) {
            try {
                val url = "$baseUrl/dev/patch"
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
                Log.e("BackendClient", "dev/patch exception", e)
                null
            }
        }
    }

    suspend fun sendWakeword(baseUrl: String, eventPayloadJson: String, token: String? = null, authToken: String? = null): String? {
        return withContext(Dispatchers.IO) {
            try {
                val url = "$baseUrl/wakeword"
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
                Log.e("BackendClient", "wakeword exception", e)
                null
            }
        }
    }

    suspend fun sendEmail(baseUrl: String, to: String, subject: String, html: String, approvalToken: String, authToken: String? = null): String? {
        return withContext(Dispatchers.IO) {
            try {
                val url = "$baseUrl/email/send"
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
                Log.e("BackendClient", "email/send exception", e)
                null
            }
        }
    }
}
