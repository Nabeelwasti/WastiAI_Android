package com.example.service

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * Task 39A: Vosk Model Downloader & Setup
 * Downloads 'vosk-model-small-en-us-0.15.zip' (~40MB) from Alphacephei to internal storage
 * and unzips it so WakeWordVoskService can load Model(modelDir.absolutePath).
 */
object VoskModelDownloader {

    private const val TAG = "VoskModelDownloader"
    const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
    const val MODEL_DIR_NAME = "vosk-model-small-en-us-0.15"

    sealed class DownloadState {
        object Idle : DownloadState()
        data class Downloading(val progress: Float, val bytesDownloaded: Long, val totalBytes: Long) : DownloadState()
        data class Extracting(val message: String) : DownloadState()
        object Success : DownloadState()
        data class Error(val message: String) : DownloadState()
    }

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    fun isModelInstalled(context: Context): Boolean {
        val modelDir = getModelDir(context)
        if (!modelDir.exists() || !modelDir.isDirectory) return false
        val children = modelDir.list()
        return !children.isNullOrEmpty()
    }

    fun getModelDir(context: Context): File {
        return File(context.filesDir, MODEL_DIR_NAME)
    }

    suspend fun downloadAndInstallModel(context: Context): Boolean = withContext(Dispatchers.IO) {
        val targetDir = getModelDir(context)
        if (isModelInstalled(context)) {
            Log.i(TAG, "Vosk model already installed at ${targetDir.absolutePath}")
            _downloadState.value = DownloadState.Success
            return@withContext true
        }

        val cacheZipFile = File(context.cacheDir, "vosk-model-temp.zip")

        try {
            _downloadState.value = DownloadState.Downloading(0f, 0L, 0L)
            Log.i(TAG, "Downloading Vosk model from $MODEL_URL...")

            val client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .build()

            val request = Request.Builder()
                .url(MODEL_URL)
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                val errorMsg = "Download failed with HTTP ${response.code}: ${response.message}"
                Log.e(TAG, errorMsg)
                _downloadState.value = DownloadState.Error(errorMsg)
                return@withContext false
            }

            val body = response.body ?: run {
                val errorMsg = "Empty response body from model server"
                Log.e(TAG, errorMsg)
                _downloadState.value = DownloadState.Error(errorMsg)
                return@withContext false
            }

            val contentLength = body.contentLength()
            var bytesDownloaded = 0L

            body.byteStream().use { inputStream ->
                FileOutputStream(cacheZipFile).use { outputStream ->
                    val buffer = ByteArray(8192)
                    var read: Int
                    while (inputStream.read(buffer).also { read = it } != -1) {
                        outputStream.write(buffer, 0, read)
                        bytesDownloaded += read
                        val progress = if (contentLength > 0) bytesDownloaded.toFloat() / contentLength.toFloat() else 0f
                        _downloadState.value = DownloadState.Downloading(progress, bytesDownloaded, contentLength)
                    }
                    outputStream.flush()
                }
            }

            _downloadState.value = DownloadState.Extracting("Unzipping model archive (~40MB)...")
            Log.i(TAG, "Download complete (${bytesDownloaded} bytes). Extracting to ${context.filesDir.absolutePath}...")

            val unzippedOk = unzip(cacheZipFile, context.filesDir)
            if (cacheZipFile.exists()) {
                cacheZipFile.delete()
            }

            if (unzippedOk && isModelInstalled(context)) {
                Log.i(TAG, "Vosk model successfully installed at ${targetDir.absolutePath}")
                _downloadState.value = DownloadState.Success
                true
            } else {
                val errorMsg = "Zip extraction failed or model files incomplete"
                Log.e(TAG, errorMsg)
                _downloadState.value = DownloadState.Error(errorMsg)
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during Vosk model setup", e)
            _downloadState.value = DownloadState.Error("Error: ${e.localizedMessage ?: e.message}")
            if (cacheZipFile.exists()) {
                cacheZipFile.delete()
            }
            false
        }
    }

    private fun unzip(zipFile: File, targetDirectory: File): Boolean {
        return try {
            ZipInputStream(zipFile.inputStream()).use { zipInputStream ->
                var zipEntry = zipInputStream.nextEntry
                val buffer = ByteArray(8192)
                while (zipEntry != null) {
                    val newFile = File(targetDirectory, zipEntry.name)
                    // Security check against Zip Slip
                    if (!newFile.canonicalPath.startsWith(targetDirectory.canonicalPath)) {
                        throw SecurityException("Zip entry is outside target directory: ${zipEntry.name}")
                    }
                    if (zipEntry.isDirectory) {
                        newFile.mkdirs()
                    } else {
                        newFile.parentFile?.mkdirs()
                        FileOutputStream(newFile).use { outputStream ->
                            var len: Int
                            while (zipInputStream.read(buffer).also { len = it } != -1) {
                                outputStream.write(buffer, 0, len)
                            }
                        }
                    }
                    zipInputStream.closeEntry()
                    zipEntry = zipInputStream.nextEntry
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error unzipping Vosk model", e)
            false
        }
    }
}
