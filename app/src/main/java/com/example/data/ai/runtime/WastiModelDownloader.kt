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
            val allowedSuffixes = listOf("huggingface.co", "github.com", "githubusercontent.com", "hf-mirror.com")
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

        val candidateUrls = mutableListOf(manifest.downloadUrl)
        manifest.mirrorDownloadUrl?.let { mirror ->
            if (mirror.isNotBlank() && mirror != manifest.downloadUrl && isSecureDownloadUrl(mirror)) {
                candidateUrls.add(mirror)
            }
        }

        var connection: HttpURLConnection? = null
        var downloadSucceeded = false
        var lastErrorMsg = "Unable to initiate download"
        var totalBytes = manifest.byteSize
        var downloadedBytes = 0L

        try {
            var urlIndex = 0
            while (urlIndex < candidateUrls.size) {
                val currentUrl = candidateUrls[urlIndex]
                try {
                    if (urlIndex > 0) {
                        Log.i(TAG, "Attempting fallback download candidate for $modelId ($urlIndex/${candidateUrls.size}): $currentUrl")
                        updateProgress(
                            ModelDownloadProgress(
                                modelId = modelId,
                                bytesDownloaded = tempFile.takeIf { it.exists() }?.length() ?: 0L,
                                totalBytes = manifest.byteSize,
                                progressFraction = 0.0f,
                                statusText = "Switching to alternative download endpoint..."
                            )
                        )
                    }

                    val url = URL(currentUrl)
                    connection = url.openConnection() as HttpURLConnection
                    connection.connectTimeout = 15000
                    connection.readTimeout = 30000
                    connection.requestMethod = "GET"

                    val existingLength = if (tempFile.exists()) tempFile.length() else 0L
                    var isResuming = false
                    if (existingLength in 1 until manifest.byteSize) {
                        connection.setRequestProperty("Range", "bytes=$existingLength-")
                        isResuming = true
                    }

                    val responseCode = connection.responseCode
                    val appending = isResuming && responseCode == 206
                    if (!appending && responseCode !in 200..299) {
                        lastErrorMsg = "HTTP error $responseCode while downloading from $currentUrl"
                        Log.w(TAG, lastErrorMsg)
                        connection.disconnect()
                        connection = null
                        urlIndex++
                        continue
                    }

                    if (appending) {
                        downloadedBytes = existingLength
                        val remaining = connection.contentLengthLong
                        totalBytes = if (remaining > 0) existingLength + remaining else manifest.byteSize
                        Log.i(TAG, "Resuming download for $modelId from byte $existingLength (remaining: $remaining)")
                    } else {
                        downloadedBytes = 0L
                        totalBytes = if (connection.contentLengthLong > 0) connection.contentLengthLong else manifest.byteSize
                        if (tempFile.exists()) tempFile.delete()
                    }

                    connection.inputStream.use { input ->
                        FileOutputStream(tempFile, appending).use { output ->
                            val buffer = ByteArray(65536)
                            var bytesRead: Int
                            var lastUpdate = System.currentTimeMillis()

                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                output.write(buffer, 0, bytesRead)
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
                    val calculatedSha = calculateFileSha256(tempFile)
                    val isExactMatch = calculatedSha.equals(manifest.expectedSha256, ignoreCase = true)

                    if (!isExactMatch) {
                        lastErrorMsg = "SHA-256 integrity verification failed for $modelId. Expected: ${manifest.expectedSha256}, Calculated: $calculatedSha"
                        Log.e(TAG, lastErrorMsg)
                        if (tempFile.exists()) tempFile.delete()
                        connection.disconnect()
                        connection = null
                        urlIndex++
                        continue
                    }

                    downloadSucceeded = true
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastErrorMsg = e.message ?: "Unknown download error on $currentUrl"
                    Log.w(TAG, "Candidate URL $currentUrl failed: ${e.message}")
                    connection?.disconnect()
                    connection = null
                    urlIndex++
                }

                // If hardcoded candidates are exhausted, trigger live web research for dynamic alternative mirrors
                if (urlIndex == candidateUrls.size && !downloadSucceeded) {
                    val dynamicUrls = discoverDynamicCandidateUrls(manifest)
                    for (dynUrl in dynamicUrls) {
                        if (!candidateUrls.contains(dynUrl)) {
                            candidateUrls.add(dynUrl)
                            Log.i(TAG, "Discovered dynamic mirror for $modelId via live research: $dynUrl")
                        }
                    }
                }
            }

            if (!downloadSucceeded) {
                // Activate Sovereign Coding Environment resolution plan
                val resolutionPlan = CodingEnvironmentInstallerBridge.planResolution(context, modelId, lastErrorMsg)
                Log.w(TAG, "All download endpoints exhausted for $modelId. Fallback available: ${resolutionPlan.suggestedStrategy}")

                updateProgress(
                    ModelDownloadProgress(
                        modelId = modelId,
                        bytesDownloaded = 0L,
                        totalBytes = manifest.byteSize,
                        progressFraction = 0.0f,
                        statusText = "Direct download failed. Sovereign Coding Environment available: 'model install $modelId'",
                        isFailed = true,
                        errorMessage = "$lastErrorMsg (Fallback: ${resolutionPlan.commandLineSnippet})"
                    )
                )
                ModelArtifactManager.updateStatus(modelId, ModelRuntimeStatus.AVAILABLE_PENDING_DOWNLOAD)
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

    fun calculateFileSha256(file: File): String {
        if (!file.exists() || file.length() == 0L) return ""
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    suspend fun discoverDynamicCandidateUrls(manifest: ModelArtifactManifest): List<String> = withContext(Dispatchers.IO) {
        val discovered = mutableListOf<String>()
        try {
            val query = "${manifest.canonicalFileName} resolve main gguf"
            val searchOutcome = com.example.data.agent.runtime.SovereignAlternativeRegistry.executeSovereignWebSearch(query)
            for (res in searchOutcome.results) {
                val url = res.sourceUrl
                if (isSecureDownloadUrl(url) && url.contains(manifest.canonicalFileName, ignoreCase = true)) {
                    discovered.add(url)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Dynamic URL discovery encountered error: ${e.message}")
        }
        discovered
    }
}
