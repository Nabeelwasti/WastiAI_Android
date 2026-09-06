package com.example.data.api

import android.content.Context
import com.example.data.credential.CredentialRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

object CanvaClient {
    private const val CANVA_API_BASE = "https://api.canva.com/rest/v1"

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun generateAndExportDocumentAsset(
        context: Context,
        title: String,
        content: String
    ): File? = withContext(Dispatchers.IO) {
        val accessToken = CredentialRegistry.getRawValue("CANVA_ACCESS_TOKEN")
            ?: CredentialRegistry.getRawValue("CANVA_CLIENT_SECRET")

        if (accessToken.isNullOrBlank() || CredentialRegistry.isPlaceholder(accessToken)) {
            // Truthful fail-closed: No active Canva API credentials, never fabricate fake PNG files
            return@withContext null
        }

        try {
            val exportPayload = JSONObject().apply {
                put("title", title)
                put("content", content)
            }.toString()

            val request = Request.Builder()
                .url("$CANVA_API_BASE/exports")
                .addHeader("Authorization", "Bearer $accessToken")
                .addHeader("Content-Type", "application/json")
                .post(exportPayload.toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext null
            }

            val body = response.body ?: return@withContext null
            val exportDir = File(context.cacheDir, "canva_exports")
            if (!exportDir.exists()) exportDir.mkdirs()

            val outputFile = File(exportDir, "wasti_canva_export_${System.currentTimeMillis()}.png")
            FileOutputStream(outputFile).use { fos ->
                body.byteStream().copyTo(fos)
            }
            if (outputFile.length() > 0) outputFile else null
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            null
        }
    }
}
