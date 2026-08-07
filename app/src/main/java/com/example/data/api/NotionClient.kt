package com.example.data.api

import com.example.data.credential.CredentialRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

object NotionClient {
    private const val BASE_URL = "https://api.notion.com/v1"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun getBotUserMe(): String = withContext(Dispatchers.IO) {
        val notionToken = CredentialRegistry.getRawValue("NOTION_CONNECTION_ID")
        if (notionToken.isNullOrBlank()) return@withContext "Not Configured"

        val request = Request.Builder()
            .url("$BASE_URL/users/me")
            .addHeader("Authorization", "Bearer $notionToken")
            .addHeader("Notion-Version", "2022-06-28")
            .get()
            .build()

        try {
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                response.body?.string() ?: "Connected"
            } else {
                "Notion Auth Status: HTTP ${response.code}"
            }
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }
}
