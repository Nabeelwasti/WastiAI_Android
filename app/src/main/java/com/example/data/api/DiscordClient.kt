package com.example.data.api

import com.example.data.credential.CredentialRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object DiscordClient {
    private const val BASE_URL = "https://discord.com/api/v10"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun getBotUserMe(): String = withContext(Dispatchers.IO) {
        val botToken = CredentialRegistry.getRawValue("DISCORD_BOT_KEY")
        if (botToken.isNullOrBlank()) return@withContext "Not Configured"

        val request = Request.Builder()
            .url("$BASE_URL/users/@me")
            .addHeader("Authorization", "Bot $botToken")
            .get()
            .build()

        try {
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                response.body?.string() ?: "Connected"
            } else {
                "Discord Auth Status: HTTP ${response.code}"
            }
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }
}
