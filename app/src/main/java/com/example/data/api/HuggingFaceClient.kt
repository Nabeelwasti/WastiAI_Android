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
 * Hugging Face Open-Source Model Client.
 * Connects directly to Hugging Face's official Inference Router:
 * https://router.huggingface.co/hf-inference/v1/chat/completions
 *
 * Provides real neural token generation for open-source model families:
 * - Meta Llama 3.2 / 3.1
 * - Qwen 2.5 Coder
 * - DeepSeek R1 / V3
 * - Mistral Nemo / 7B
 * - Google Gemma 2
 * - Microsoft Phi-3.5
 * - HuggingFace SmolLM2
 */
object HuggingFaceClient {
    private const val TAG = "HuggingFaceClient"
    private const val ROUTER_URL = "https://router.huggingface.co/hf-inference/v1/chat/completions"
    private const val EMBEDDING_MODEL_URL = "https://api-inference.huggingface.co/models/sentence-transformers/all-MiniLM-L6-v2"
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    fun isConfigured(): Boolean {
        val token = CredentialRegistry.getRawValue("HUGGINGFACE_ACCESS_TOKEN")
            ?: CredentialRegistry.getRawValue("HUGGINGFACE_API_KEY")
        return !token.isNullOrBlank() && !CredentialRegistry.isPlaceholder(token)
    }

    suspend fun generateEmbedding(text: String): List<Float> = withContext(Dispatchers.IO) {
        val apiKey = CredentialRegistry.getRawValue("HUGGINGFACE_ACCESS_TOKEN")
            ?: CredentialRegistry.getRawValue("HUGGINGFACE_API_KEY")
        if (apiKey.isNullOrBlank() || CredentialRegistry.isPlaceholder(apiKey)) return@withContext emptyList()

        val bearer = if (apiKey.startsWith("Bearer ")) apiKey else "Bearer $apiKey"
        val json = JSONObject().apply {
            put("inputs", text)
        }.toString()

        val request = Request.Builder()
            .url(EMBEDDING_MODEL_URL)
            .addHeader("Authorization", bearer)
            .post(json.toRequestBody("application/json".toMediaType()))
            .build()

        try {
            val response = okHttpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val bodyString = response.body?.string().orEmpty()
                parseEmbeddingResponse(bodyString)
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            emptyList()
        }
    }

    fun parseEmbeddingResponse(bodyString: String): List<Float> {
        if (bodyString.isBlank()) return emptyList()
        return try {
            val jsonArray = JSONArray(bodyString)
            val result = mutableListOf<Float>()
            if (jsonArray.length() > 0) {
                val first = jsonArray.opt(0)
                if (first is JSONArray) {
                    for (i in 0 until first.length()) {
                        result.add(first.getDouble(i).toFloat())
                    }
                } else {
                    for (i in 0 until jsonArray.length()) {
                        result.add(jsonArray.getDouble(i).toFloat())
                    }
                }
            }
            result
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun generateText(
        prompt: String,
        systemInstruction: String = "You are Wasti AI OS.",
        modelId: String = "meta-llama/Llama-3.2-3B-Instruct"
    ): String = withContext(Dispatchers.IO) {
        val token = CredentialRegistry.getRawValue("HUGGINGFACE_ACCESS_TOKEN")
            ?: CredentialRegistry.getRawValue("HUGGINGFACE_API_KEY")
            ?: return@withContext ""

        if (CredentialRegistry.isPlaceholder(token)) return@withContext ""

        val bearer = if (token.startsWith("Bearer ")) token else "Bearer $token"

        val resolvedModel = mapToHuggingFaceRepo(modelId)

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
                put("model", resolvedModel)
                put("messages", messages)
                put("max_tokens", 2048)
                put("temperature", 0.7)
            }

            val body = payload.toString().toRequestBody(JSON_MEDIA_TYPE)
            val request = Request.Builder()
                .url(ROUTER_URL)
                .header("Authorization", bearer)
                .post(body)
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val rawJson = response.body?.string() ?: return@withContext ""
                    val json = JSONObject(rawJson)
                    val choices = json.optJSONArray("choices")
                    if (choices != null && choices.length() > 0) {
                        val msg = choices.getJSONObject(0).optJSONObject("message")
                        val content = msg?.optString("content")
                        if (!content.isNullOrBlank()) return@withContext content.trim()
                    }
                } else {
                    Log.d(TAG, "HuggingFace API response code: ${response.code}")
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.d(TAG, "HuggingFace API error: ${e.message}")
        }
        ""
    }

    fun mapToHuggingFaceRepo(idOrFamily: String): String {
        val lower = idOrFamily.lowercase()
        return when {
            lower.contains("llama") -> "meta-llama/Llama-3.2-3B-Instruct"
            lower.contains("qwen") -> "Qwen/Qwen2.5-Coder-7B-Instruct"
            lower.contains("deepseek") -> "deepseek-ai/DeepSeek-R1-Distill-Qwen-8B"
            lower.contains("mistral") -> "mistralai/Mistral-7B-Instruct-v0.3"
            lower.contains("gemma") -> "google/gemma-2-2b-it"
            lower.contains("phi") -> "microsoft/Phi-3.5-mini-instruct"
            lower.contains("smollm") -> "HuggingFaceTB/SmolLM2-1.7B-Instruct"
            lower.contains("granite") -> "ibm-granite/granite-3.0-8b-instruct"
            lower.contains("falcon") -> "tiiuae/Falcon3-7B-Instruct"
            lower.contains("stablelm") -> "stabilityai/stablelm-2-12b-chat"
            lower.contains("glm") -> "THUDM/glm-4-9b-chat"
            lower.contains("/") -> idOrFamily // already full org/repo
            else -> "meta-llama/Llama-3.2-3B-Instruct"
        }
    }
}
