package com.example.data.ai.runtime

import android.content.Context
import android.os.StatFs
import android.util.Log
import com.example.data.ai.engine.HardwareCapabilityDetector
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
import java.net.URI
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

    fun isTrustedSha256(value: String): Boolean {
        if (value.length != 64) return false
        if (value.all { it == '0' } || value.all { it == 'f' || it == 'F' }) return false
        return value.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }
    }

    fun isSecureDownloadUrl(urlString: String): Boolean {
        return try {
            val uri = URI(urlString)
            if (!uri.scheme.equals("https", ignoreCase = true)) return false
            val host = uri.host?.lowercase() ?: return false
            if (host == "localhost" || host == "127.0.0.1" || host == "::1") return false
            if (host.startsWith("10.") || host.startsWith("192.168.") || host.startsWith("169.254.")) return false
            val allowedSuffixes = listOf("huggingface.co", "github.com", "githubusercontent.com")
            allowedSuffixes.any { host == it || host.endsWith(".$it") }
        } catch (_: Throwable) {
            false
        }
    }

    fun checkDiskSpaceForDownload(context: Context, requiredBytes: Long): Pair<Boolean, String> {
        val storageDir = context.filesDir
        val stat = StatFs(storageDir.absolutePath)
        val availableBytes = stat.availableBlocksLong * stat.blockSizeLong
        val safetyBuffer = 500L * 1024 * 1024 // 500MB headroom
        val totalNeeded = requiredBytes + safetyBuffer
        if (availableBytes < totalNeeded) {
            val availableMb = availableBytes / (1024 * 1024)
            val neededMb = totalNeeded / (1024 * 1024)
            return false to "Insufficient disk space: Model requires ${requiredBytes / (1024 * 1024)}MB (+ 500MB safety buffer = ${neededMb}MB), but device only has ${availableMb}MB available."
        }
        return true to "Storage space verified."
    }

    fun canDownload(context: Context, manifest: ModelArtifactManifest): Pair<Boolean, String> {
        if (!isTrustedSha256(manifest.expectedSha256)) {
            return false to "Model '${manifest.modelId}' does not have a verified, published SHA-256 checksum in catalog."
        }
        if (!isSecureDownloadUrl(manifest.downloadUrl)) {
            return false to "Model download URL is not secure HTTPS or from an allowed repository: ${manifest.downloadUrl}"
        }
        val diskCheck = checkDiskSpaceForDownload(context, manifest.byteSize)
        if (!diskCheck.first) {
            return diskCheck
        }
        val hwSpecs = HardwareCapabilityDetector.detectHardwareEnvironment(context)
        if (hwSpecs.isBatteryLowOrThermalsThrottling) {
            return false to "Device is battery-constrained or thermally throttling. Download paused for device safety."
        }
        return true to "Ready to download."
    }

    suspend fun downloadModel(
        context: Context,
        manifest: ModelArtifactManifest
    ): Boolean = withContext(Dispatchers.IO) {
        val modelId = manifest.modelId
        val targetFile = ModelArtifactManager.getModelFile(context, modelId)
        targetFile.parentFile?.mkdirs()
        val tempFile = File(targetFile.parentFile, "${targetFile.name}.downloading")

        // Pre-download validation: SHA-256, URL security, disk space, and battery/thermals
        val check = canDownload(context, manifest)
        if (!check.first) {
            val errorMsg = check.second
            Log.w(TAG, errorMsg)
            updateProgress(
                ModelDownloadProgress(
                    modelId = modelId,
                    bytesDownloaded = 0L,
                    totalBytes = manifest.byteSize,
                    progressFraction = 0.0f,
                    statusText = "Download blocked: $errorMsg",
                    isFailed = true,
                    errorMessage = errorMsg
                )
            )
            val newStatus = if (!isTrustedSha256(manifest.expectedSha256)) {
                ModelRuntimeStatus.PENDING_VERIFICATION
            } else {
                ModelRuntimeStatus.AVAILABLE_PENDING_DOWNLOAD
            }
            ModelArtifactManager.updateStatus(modelId, newStatus)
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

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                val errorMsg = "HTTP error $responseCode while downloading model."
                Log.e(TAG, errorMsg)
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
                ModelArtifactManager.updateStatus(modelId, ModelRuntimeStatus.AVAILABLE_PENDING_DOWNLOAD)
                return@withContext false
            }

            val totalBytes = if (connection.contentLengthLong > 0) connection.contentLengthLong else manifest.byteSize
            val digest = MessageDigest.getInstance("SHA-256")
            var downloadedBytes = 0L

            if (tempFile.exists()) {
                tempFile.delete()
            }

            connection.inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(32768)
                    var bytesRead: Int
                    var lastUpdate = System.currentTimeMillis()

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        digest.update(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead

                        val now = System.currentTimeMillis()
                        if (now - lastUpdate > 300) {
                            val fraction = if (totalBytes > 0) downloadedBytes.toFloat() / totalBytes else 0.0f
                            updateProgress(
                                ModelDownloadProgress(
                                    modelId = modelId,
                                    bytesDownloaded = downloadedBytes,
                                    totalBytes = totalBytes,
                                    progressFraction = fraction,
                                    statusText = "Downloading: ${(fraction * 100).toInt()}% (${downloadedBytes / (1024 * 1024)}MB / ${totalBytes / (1024 * 1024)}MB)"
                                )
                            )
                            lastUpdate = now
                        }
                    }
                }
            }

            // Verify Exact Checksum (Reject synthetic/prefix/wildcard hashes)
            val calculatedSha = digest.digest().joinToString("") { "%02x".format(it) }
            val isExactMatch = calculatedSha.equals(manifest.expectedSha256, ignoreCase = true)

            if (!isExactMatch) {
                val mismatchMsg = "SHA-256 integrity verification failed for $modelId. Expected: ${manifest.expectedSha256}, Calculated: $calculatedSha"
                Log.e(TAG, mismatchMsg)
                if (tempFile.exists()) tempFile.delete()
                updateProgress(
                    ModelDownloadProgress(
                        modelId = modelId,
                        bytesDownloaded = downloadedBytes,
                        totalBytes = totalBytes,
                        progressFraction = 0.0f,
                        statusText = "Integrity check failed: Checksum mismatch",
                        isFailed = true,
                        errorMessage = mismatchMsg
                    )
                )
                ModelArtifactManager.updateStatus(modelId, ModelRuntimeStatus.FAILED_INITIALIZATION)
                return@withContext false
            }

            // Atomic move only after successful cryptographic verification
            if (tempFile.exists()) {
                if (targetFile.exists()) targetFile.delete()
                val moved = tempFile.renameTo(targetFile)
                if (!moved) {
                    tempFile.copyTo(targetFile, overwrite = true)
                    tempFile.delete()
                }
            }

            if (!targetFile.exists() || targetFile.length() == 0L) {
                val atomicFailMsg = "Atomic commitment of downloaded model file failed for $modelId."
                Log.e(TAG, atomicFailMsg)
                updateProgress(
                    ModelDownloadProgress(
                        modelId = modelId,
                        bytesDownloaded = downloadedBytes,
                        totalBytes = totalBytes,
                        progressFraction = 0.0f,
                        statusText = "Storage error: Atomic move failed",
                        isFailed = true,
                        errorMessage = atomicFailMsg
                    )
                )
                ModelArtifactManager.updateStatus(modelId, ModelRuntimeStatus.FAILED_INITIALIZATION)
                return@withContext false
            }

            updateProgress(
                ModelDownloadProgress(
                    modelId = modelId,
                    bytesDownloaded = downloadedBytes,
                    totalBytes = totalBytes,
                    progressFraction = 1.0f,
                    statusText = "Model ready (Integrity verified SHA-256)",
                    isCompleted = true
                )
            )
            ModelArtifactManager.updateStatus(modelId, ModelRuntimeStatus.VERIFIED_INTEGRITY)
            ModelArtifactManager.updateStatus(modelId, ModelRuntimeStatus.LOCAL_WEIGHTS_PRESENT)
            true
        } catch (e: CancellationException) {
            Log.i(TAG, "Download cancelled for $modelId")
            if (tempFile.exists()) tempFile.delete()
            ModelArtifactManager.updateStatus(modelId, ModelRuntimeStatus.AVAILABLE_PENDING_DOWNLOAD)
            false
        } catch (e: Exception) {
            Log.e(TAG, "Download error for $modelId", e)
            if (tempFile.exists()) tempFile.delete()
            updateProgress(
                ModelDownloadProgress(
                    modelId = modelId,
                    bytesDownloaded = 0L,
                    totalBytes = manifest.byteSize,
                    progressFraction = 0.0f,
                    statusText = "Download failed: ${e.message}",
                    isFailed = true,
                    errorMessage = e.message
                )
            )
            ModelArtifactManager.updateStatus(modelId, ModelRuntimeStatus.AVAILABLE_PENDING_DOWNLOAD)
            false
        } finally {
            connection?.disconnect()
        }
    }

    private fun updateProgress(progress: ModelDownloadProgress) {
        val current = _downloadProgressMap.value.toMutableMap()
        current[progress.modelId] = progress
        _downloadProgressMap.value = current
    }
}
