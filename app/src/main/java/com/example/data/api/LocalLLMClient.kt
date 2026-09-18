package com.example.data.api

import android.util.Log
import com.example.data.credential.CredentialRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Sovereign Local LLM Client.
 * Connects Wasti AI OS directly to local inference runtimes:
 * - Ollama (e.g. running in Termux or on local network: http://127.0.0.1:11434)
 * - llama.cpp server (llama-server: http://127.0.0.1:8080)
 * - LocalAI / vLLM / custom server specified by LOCAL_LLM_URL.
 *
 * 100% Free, 100% Offline, Zero API Keys, 100% Real Neural Token Execution.
 */
object LocalLLMClient {
    private const val TAG = "LocalLLMClient"
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    fun getCandidateEndpoints(): List<String> {
        val candidates = mutableListOf<String>()
        val configured = CredentialRegistry.getRawValue("LOCAL_LLM_URL")
        if (!configured.isNullOrBlank() && !CredentialRegistry.isPlaceholder(configured)) {
            candidates.add(configured.trimEnd('/'))
        }
        candidates.add("http://127.0.0.1:11434")
        candidates.add("http://127.0.0.1:8080")
        candidates.add("http://10.0.2.2:11434")
        candidates.add("http://localhost:11434")
        return candidates.distinct()
    }

    suspend fun isServerActive(): Boolean = withContext(Dispatchers.IO) {
        for (base in getCandidateEndpoints()) {
            if (probeEndpoint(base)) return@withContext true
        }
        false
    }

    private fun probeEndpoint(baseUrl: String): Boolean {
        return try {
            val req1 = Request.Builder().url("$baseUrl/api/version").get().build()
            okHttpClient.newCall(req1).execute().use { if (it.isSuccessful) return true }

            val req2 = Request.Builder().url("$baseUrl/v1/models").get().build()
            okHttpClient.newCall(req2).execute().use { if (it.isSuccessful) return true }

            val req3 = Request.Builder().url("$baseUrl/health").get().build()
            okHttpClient.newCall(req3).execute().use { if (it.isSuccessful) return true }

            false
        } catch (_: Throwable) {
            false
        }
    }

    suspend fun generateText(
        prompt: String,
        systemInstruction: String = "You are Wasti AI OS running locally.",
        modelName: String = "llama3.2"
    ): String = withContext(Dispatchers.IO) {
        for (baseUrl in getCandidateEndpoints()) {
            Log.d(TAG, "Attempting local inference on endpoint: $baseUrl")
            val result = tryEndpoint(baseUrl, prompt, systemInstruction, modelName)
            if (!result.isNullOrBlank()) {
                Log.i(TAG, "Local inference succeeded via $baseUrl")
                return@withContext result
            }
        }
        ""
    }

    private fun tryEndpoint(
        baseUrl: String,
        prompt: String,
        systemInstruction: String,
        modelName: String
    ): String? {
        // 1. Try standard OpenAI-compatible /v1/chat/completions (supported by modern Ollama and llama-server)
        try {
            val messages = JSONArray().apply {
                if (systemInstruction.isNotBlank()) {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", systemInstruction)
                    })
                }
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            }
            val payload = JSONObject().apply {
                put("model", modelName)
                put("messages", messages)
                put("temperature", 0.7)
                put("stream", false)
            }
            val body = payload.toString().toRequestBody(JSON_MEDIA_TYPE)
            val request = Request.Builder()
                .url("$baseUrl/v1/chat/completions")
                .post(body)
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val rawJson = response.body?.string() ?: return null
                    val json = JSONObject(rawJson)
                    val choices = json.optJSONArray("choices")
                    if (choices != null && choices.length() > 0) {
                        val msg = choices.getJSONObject(0).optJSONObject("message")
                        val content = msg?.optString("content")
                        if (!content.isNullOrBlank()) return content.trim()
                    }
                }
            }
        } catch (_: Throwable) {}

        // 2. Try Ollama native /api/chat endpoint
        try {
            val messages = JSONArray().apply {
                if (systemInstruction.isNotBlank()) {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", systemInstruction)
                    })
                }
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            }
            val payload = JSONObject().apply {
                put("model", modelName)
                put("messages", messages)
                put("stream", false)
            }
            val body = payload.toString().toRequestBody(JSON_MEDIA_TYPE)
            val request = Request.Builder()
                .url("$baseUrl/api/chat")
                .post(body)
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val rawJson = response.body?.string() ?: return null
                    val json = JSONObject(rawJson)
                    val messageObj = json.optJSONObject("message")
                    val content = messageObj?.optString("content")
                    if (!content.isNullOrBlank()) return content.trim()
                }
            }
        } catch (_: Throwable) {}

        // 3. Try Ollama native /api/generate endpoint
        try {
            val fullPrompt = if (systemInstruction.isNotBlank()) "$systemInstruction\n\n$prompt" else prompt
            val payload = JSONObject().apply {
                put("model", modelName)
                put("prompt", fullPrompt)
                put("stream", false)
            }
            val body = payload.toString().toRequestBody(JSON_MEDIA_TYPE)
            val request = Request.Builder()
                .url("$baseUrl/api/generate")
                .post(body)
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val rawJson = response.body?.string() ?: return null
                    val json = JSONObject(rawJson)
                    val content = json.optString("response")
                    if (!content.isNullOrBlank()) return content.trim()
                }
            }
        } catch (_: Throwable) {}

        return null
    }
}
