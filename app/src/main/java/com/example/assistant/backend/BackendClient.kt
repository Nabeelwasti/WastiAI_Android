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

    suspend fun callLLM(baseUrl: String, provider: String, payloadJson: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                val url = "$baseUrl/llm"
                val body: RequestBody = payloadJson.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
                val req = Request.Builder().url(url).post(body).build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Log.e("BackendClient", "LLM call failed: ${'$'}{resp.code}")
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

    suspend fun createDevPatch(baseUrl: String, owner: String, repo: String, title: String, bodyJson: String, changesJson: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                val url = "$baseUrl/dev/patch"
                val payload = "{\"owner\":\"${'$'}owner\",\"repo\":\"${'$'}repo\",\"title\":\"${'$'}title\",\"body\":${bodyJson},\"changes\":${changesJson}}"
                val body: RequestBody = payload.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
                val req = Request.Builder().url(url).post(body).build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Log.e("BackendClient", "dev/patch failed: ${'$'}{resp.code}")
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
}
