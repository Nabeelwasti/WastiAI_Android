package com.example.data.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

import com.example.data.credential.CredentialRegistry
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class VeoVideoResult(
    val videoId: String,
    val title: String,
    val videoUrl: String,
    val status: String
)

object VeoVideoClient {
    private const val VEO_API_ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models/veo-2.0-generate-video:predict"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun generateShortVideo(prompt: String): VeoVideoResult = withContext(Dispatchers.IO) {
        val apiKey = CredentialRegistry.getRawValue("GEMINI_API_KEY")
            ?: CredentialRegistry.getRawValue("GOOGLE_API_KEY")

        if (apiKey.isNullOrBlank() || CredentialRegistry.isPlaceholder(apiKey)) {
            return@withContext VeoVideoResult(
                videoId = "",
                title = prompt.take(30),
                videoUrl = "",
                status = "UNAVAILABLE: Veo video generation credentials unconfigured"
            )
        }

        try {
            val payload = JSONObject().apply {
                put("prompt", prompt)
            }.toString()

            val request = Request.Builder()
                .url("$VEO_API_ENDPOINT?key=$apiKey")
                .addHeader("Content-Type", "application/json")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            val bodyStr = response.body?.string().orEmpty()

            if (response.isSuccessful) {
                val json = try { JSONObject(bodyStr) } catch (_: Exception) { null }
                val videoUrl = json?.optString("videoUri")
                    ?: json?.optString("downloadUrl")
                    ?: ""
                val videoId = json?.optString("id") ?: "veo_${System.currentTimeMillis()}"

                if (videoUrl.isNotBlank()) {
                    VeoVideoResult(
                        videoId = videoId,
                        title = "Veo AI Generated Clip: ${prompt.take(30)}",
                        videoUrl = videoUrl,
                        status = "COMPLETED"
                    )
                } else {
                    VeoVideoResult(
                        videoId = videoId,
                        title = prompt.take(30),
                        videoUrl = "",
                        status = "PENDING_PROCESSING"
                    )
                }
            } else {
                VeoVideoResult(
                    videoId = "",
                    title = prompt.take(30),
                    videoUrl = "",
                    status = "FAILED: HTTP ${response.code}"
                )
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            VeoVideoResult(
                videoId = "",
                title = prompt.take(30),
                videoUrl = "",
                status = "ERROR: ${e.message ?: e.toString()}"
            )
        }
    }
}
