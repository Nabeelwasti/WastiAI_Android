package com.example.data.api

import com.example.data.credential.CredentialRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

object SlackClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun sendSlackNotification(messageText: String): Boolean = withContext(Dispatchers.IO) {
        val domainOrWebhook = CredentialRegistry.getRawValue("SLACK_DOMAIN")
        if (domainOrWebhook.isBlank()) return@withContext false

        val url = if (domainOrWebhook.startsWith("http")) {
            domainOrWebhook
        } else {
            "https://hooks.slack.com/services/$domainOrWebhook"
        }

        val json = """{"text": "⚡ [Wasti AI Alert]: $messageText"}"""

        val request = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .post(json.toRequestBody("application/json".toMediaType()))
            .build()

        try {
            val response = client.newCall(request).execute()
            response.isSuccessful
        } catch (e: Exception) {
            false
        }
    }
}
