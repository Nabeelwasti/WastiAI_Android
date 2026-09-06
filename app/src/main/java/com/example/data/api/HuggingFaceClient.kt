package com.example.data.api

import com.example.data.credential.CredentialRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

import org.json.JSONArray
import org.json.JSONObject

object HuggingFaceClient {
    private const val EMBEDDING_MODEL_URL = "https://api-inference.huggingface.co/models/sentence-transformers/all-MiniLM-L6-v2"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun generateEmbedding(text: String): List<Float> = withContext(Dispatchers.IO) {
        val apiKey = CredentialRegistry.getRawValue("HUGGINGFACE_ACCESS_TOKEN")
        if (apiKey.isNullOrBlank() || CredentialRegistry.isPlaceholder(apiKey)) return@withContext emptyList()

        val json = JSONObject().apply {
            put("inputs", text)
        }.toString()

        val request = Request.Builder()
            .url(EMBEDDING_MODEL_URL)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(json.toRequestBody("application/json".toMediaType()))
            .build()

        try {
            val response = client.newCall(request).execute()
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
}
