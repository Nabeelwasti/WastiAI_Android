package com.example.data.api

import com.example.data.credential.CredentialRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

object ZapierClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun triggerZapierAutomation(actionName: String, paramsJson: String): Boolean = withContext(Dispatchers.IO) {
        val token = CredentialRegistry.getRawValue("ZAPIER_CONNECT_TOKEN").orEmpty()
        val shareLink = CredentialRegistry.getRawValue("ZAPIER_MCP_SHARE_LINK").orEmpty()
        if (token.isBlank() && shareLink.isBlank()) return@withContext false

        val targetUrl = if (shareLink.startsWith("http")) shareLink else "https://nla.zapier.com/api/v1/dynamic/executed-action/"

        val request = Request.Builder()
            .url(targetUrl)
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Content-Type", "application/json")
            .post(paramsJson.toRequestBody("application/json".toMediaType()))
            .build()

        try {
            val response = client.newCall(request).execute()
            response.isSuccessful || response.code in 200..299
        } catch (e: Exception) {
            false
        }
    }
}
