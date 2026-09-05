package com.example.data.ai.runtime

import android.content.Context
import android.util.Log
import com.example.data.ai.engine.ModelArtifactManager
import com.example.data.ai.model.ModelArtifactManifest
import com.example.data.ai.model.ModelRuntimeStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class ModelDownloadProgress(
    val modelId: String,
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val progressFraction: Float,
    val statusText: String,
    val isCompleted: Boolean = false,
    val isFailed: Boolean = false,
    val errorMessage: String? = null
)

object WastiModelDownloader {
    private const val TAG = "WastiModelDownloader"
    private val _downloadProgressMap = MutableStateFlow<Map<String, ModelDownloadProgress>>(emptyMap())
    val downloadProgressMap: StateFlow<Map<String, ModelDownloadProgress>> = _downloadProgressMap.asStateFlow()

    suspend fun downloadModel(
        context: Context,
        manifest: ModelArtifactManifest
    ): Boolean = withContext(Dispatchers.IO) {
        val modelId = manifest.modelId
        val targetFile = ModelArtifactManager.getModelFile(context, modelId)
        val tempFile = File(targetFile.parentFile, "${targetFile.name}.downloading")

        if (!isTrustedSha256(manifest.expectedSha256)) {
            val error = "Model '$modelId' has no trusted SHA-256 checksum; refusing download without integrity metadata."
            ModelArtifactManager.updateStatus(modelId, ModelRuntimeStatus.DECLARED)
            updateProgress(
                ModelDownloadProgress(
                    modelId = modelId,
                    bytesDownloaded = 0L,
                    totalBytes = manifest.byteSize,
                    progressFraction = 0.0f,
                    statusText = "Download blocked: checksum required",
                    isFailed = true,
                    errorMessage = error
                )
            )
            Log.e(TAG, error)
            return@withContext false
        }

        updateProgress(
            ModelDownloadProgress(
                modelId = modelId,
                bytesDownloaded = 0L,
                totalBytes = manifest.byteSize,
                progressFraction = 0.0f,
                statusText = "Connecting to ${manifest.downloadUrl}..."
            )
        )
        ModelArtifactManager.updateStatus(modelId, ModelRuntimeStatus.DOWNLOADING)

        var connection: HttpURLConnection? = null
        try {
            val url = URL(manifest.downloadUrl)
            connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 15000
            connection.readTimeout = 30000
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = true

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                val errorMsg = "HTTP error $responseCode while downloading model."
                Log.e(TAG, errorMsg)
                ModelArtifactManager.updateStatus(modelId, ModelRuntimeStatus.AVAILABLE_PENDING_DOWNLOAD)
                updateProgress(
                    ModelDownloadProgress(
                        modelId = modelId,
                        bytesDownloaded = 0L,
                        totalBytes = manifest.byteSize,
                        progressFraction = 0.0f,
                        statusText = "Download failed",
                        isFailed = true,
                        errorMessage = errorMsg
                    )
                )
                return@withContext false
            }

            val totalBytes = if (connection.contentLengthLong > 0) connection.contentLengthLong else manifest.byteSize
            val digest = MessageDigest.getInstance("SHA-256")
            var downloadedBytes = 0L

            connection.inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var lastUpdate = 0L
                    while (true) {
                        val bytesRead = input.read(buffer)
                        if (bytesRead < 0) break
                        if (bytesRead == 0) continue
                        output.write(buffer, 0, bytesRead)
                        digest.update(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead

                        val now = System.currentTimeMillis()
                        if (now - lastUpdate > 300) {
                            val fraction = if (totalBytes > 0) {
                                (downloadedBytes.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
                            } else {
                                0f
                            }
                            updateProgress(
                                ModelDownloadProgress(
                                    modelId = modelId,
                                    bytesDownloaded = downloadedBytes,
                                    totalBytes = totalBytes,
                                    progressFraction = fraction,
                                    statusText = "Downloading: ${(fraction * 100).toInt()}%"
                                )
                            )
                            lastUpdate = now
                        }
                    }
                    output.fd.sync()
                }
            }

            val calculatedSha = digest.digest().joinToString("") { "%02x".format(it) }
            if (!calculatedSha.equals(manifest.expectedSha256, ignoreCase = true)) {
                val error = "Integrity verification failed for '$modelId': downloaded SHA-256 does not match manifest."
                Log.e(TAG, "$error expected=${manifest.expectedSha256} actual=$calculatedSha")
                tempFile.delete()
                ModelArtifactManager.updateStatus(modelId, ModelRuntimeStatus.AVAILABLE_PENDING_DOWNLOAD)
                updateProgress(
                    ModelDownloadProgress(
                        modelId = modelId,
                        bytesDownloaded = downloadedBytes,
                        totalBytes = totalBytes,
                        progressFraction = 0.0f,
                        statusText = "Download rejected: integrity failure",
                        isFailed = true,
                        errorMessage = error
                    )
                )
                return@withContext false
            }

            ModelArtifactManager.updateStatus(modelId, ModelRuntimeStatus.VERIFIED_INTEGRITY)
            if (!tempFile.renameTo(targetFile)) {
                val error = "Integrity verified but final model-file installation failed."
                tempFile.delete()
                ModelArtifactManager.updateStatus(modelId, ModelRuntimeStatus.AVAILABLE_PENDING_DOWNLOAD)
                updateProgress(
                    ModelDownloadProgress(
                        modelId = modelId,
                        bytesDownloaded = downloadedBytes,
                        totalBytes = totalBytes,
                        progressFraction = 0.0f,
                        statusText = "Install failed",
                        isFailed = true,
                        errorMessage = error
                    )
                )
                return@withContext false
            }

            ModelArtifactManager.updateStatus(modelId, ModelRuntimeStatus.LOCAL_WEIGHTS_PRESENT)
            updateProgress(
                ModelDownloadProgress(
                    modelId = modelId,
                    bytesDownloaded = downloadedBytes,
                    totalBytes = totalBytes,
                    progressFraction = 1.0f,
                    statusText = "Model downloaded and integrity verified",
                    isCompleted = true
                )
            )
            true
        } catch (e: CancellationException) {
            Log.i(TAG, "Download cancelled for $modelId")
            tempFile.delete()
            ModelArtifactManager.updateStatus(modelId, ModelRuntimeStatus.AVAILABLE_PENDING_DOWNLOAD)
            false
        } catch (e: Exception) {
            Log.e(TAG, "Download error for $modelId", e)
            tempFile.delete()
            ModelArtifactManager.updateStatus(modelId, ModelRuntimeStatus.AVAILABLE_PENDING_DOWNLOAD)
            updateProgress(
                ModelDownloadProgress(
                    modelId = modelId,
                    bytesDownloaded = 0L,
                    totalBytes = manifest.byteSize,
                    progressFraction = 0.0f,
                    statusText = "Download failed",
                    isFailed = true,
                    errorMessage = e.message
                )
            )
            false
        } finally {
            connection?.disconnect()
        }
    }

    private fun isTrustedSha256(value: String): Boolean =
        value.length == 64 && value.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }

    private fun updateProgress(progress: ModelDownloadProgress) {
        val current = _downloadProgressMap.value.toMutableMap()
        current[progress.modelId] = progress
        _downloadProgressMap.value = current
    }
}
