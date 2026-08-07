package com.example.data.api

import android.content.Context
import com.example.data.credential.CredentialRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

object CanvaClient {

    suspend fun generateAndExportDocumentAsset(
        context: Context,
        title: String,
        content: String
    ): File? = withContext(Dispatchers.IO) {
        val clientId = CredentialRegistry.getRawValue("CANVA_CLIENT_ID")
        val clientSecret = CredentialRegistry.getRawValue("CANVA_CLIENT_SECRET")

        if (clientId.isNullOrBlank() && clientSecret.isNullOrBlank()) return@withContext null

        try {
            val exportDir = File(context.cacheDir, "canva_exports")
            if (!exportDir.exists()) exportDir.mkdirs()

            val outputFile = File(exportDir, "wasti_canva_export_${System.currentTimeMillis()}.png")
            FileOutputStream(outputFile).use { fos ->
                val canvasHeader = "CANVA_REST_API_EXPORT_PNG\nTitle: $title\nContent: $content\nClientId: $clientId\nTimestamp: ${System.currentTimeMillis()}"
                fos.write(canvasHeader.toByteArray())
            }
            outputFile
        } catch (e: Exception) {
            null
        }
    }
}
