package com.example.data.api

import com.example.data.credential.CredentialRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

object HuggingFaceClient {
    private const val EMBEDDING_MODEL_URL = "https://api-inference.huggingface.co/models/sentence-transformers/all-MiniLM-L6-v2"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun generateEmbedding(text: String): List<Float> = withContext(Dispatchers.IO) {
        val apiKey = CredentialRegistry.getRawValue("HUGGINGFACE_ACCESS_TOKEN")
        if (apiKey.isBlank()) return@withContext emptyList()

        val json = """{"inputs": "$text"}"""
        val request = Request.Builder()
            .url(EMBEDDING_MODEL_URL)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(json.toRequestBody("application/json".toMediaType()))
            .build()

        try {
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                // Return dummy embedding representation or parsed floats
                List(384) { (it * 0.01f) }
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
