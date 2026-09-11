package com.example.data.core

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

enum class MemoryPressureLevel {
    LOW,
    MODERATE,
    CRITICAL
}

data class DatasetChunk<T>(
    val items: List<T>,
    val pageIndex: Int,
    val isLastPage: Boolean,
    val totalProcessedSoFar: Int
)

/**
 * Large Dataset & Streaming Engine for Wasti AI OS.
 * Designed for maximum throughput, unlimited capacity, and resilient processing.
 * Never imposes artificial limits; dynamically scales to device capacity and streams
 * massive files, datasets, and logs efficiently without crashing or ANRs.
 */
object LargeDatasetEngine {

    private const val TAG = "LargeDatasetEngine"
    const val STREAM_BUFFER_SIZE = 64 * 1024 // 64 KB high-performance streaming buffer

    fun getMemoryPressureLevel(): MemoryPressureLevel {
        val runtime = Runtime.getRuntime()
        val maxMem = runtime.maxMemory()
        val totalMem = runtime.totalMemory()
        val freeMem = runtime.freeMemory()
        val availableMem = maxMem - totalMem + freeMem

        val fractionFree = availableMem.toDouble() / maxMem.toDouble()
        return when {
            fractionFree < 0.10 -> MemoryPressureLevel.CRITICAL
            fractionFree < 0.20 -> MemoryPressureLevel.MODERATE
            else -> MemoryPressureLevel.LOW
        }
    }

    /**
     * Streams lines from a file of any size up to Int.MAX_VALUE without artificial truncation.
     */
    suspend fun streamFileLines(
        file: File,
        offset: Int = 0,
        limit: Int = Int.MAX_VALUE,
        lineFilter: (String) -> Boolean = { true }
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            if (!file.exists() || !file.isFile) {
                return@withContext Result.failure(NoSuchFileException(file, null, "File does not exist"))
            }

            val collectedLines = mutableListOf<String>()
            var currentIndex = 0

            file.bufferedReader(StandardCharsets.UTF_8).use { reader ->
                var line: String? = reader.readLine()
                while (line != null) {
                    if (currentIndex >= offset && lineFilter(line)) {
                        collectedLines.add(line)
                        if (collectedLines.size >= limit) {
                            break
                        }
                    }
                    currentIndex++
                    line = reader.readLine()
                }
            }

            Result.success(collectedLines)
        } catch (e: Exception) {
            Log.e(TAG, "Error streaming file lines from ${file.name}", e)
            Result.failure(e)
        }
    }

    /**
     * Reads a chunk of bytes with high-speed buffered streaming.
     */
    suspend fun readFileChunk(
        file: File,
        offsetBytes: Long,
        chunkSize: Int = STREAM_BUFFER_SIZE
    ): Result<ByteArray> = withContext(Dispatchers.IO) {
        try {
            if (!file.exists() || !file.isFile) {
                return@withContext Result.failure(NoSuchFileException(file, null, "File does not exist"))
            }

            FileInputStream(file).use { fis ->
                var remainingToSkip = offsetBytes
                while (remainingToSkip > 0) {
                    val skipped = fis.skip(remainingToSkip)
                    if (skipped <= 0) break
                    remainingToSkip -= skipped
                }
                val buffer = ByteArray(chunkSize)
                val bytesRead = fis.read(buffer)
                if (bytesRead <= 0) {
                    return@withContext Result.success(ByteArray(0))
                }
                if (bytesRead < chunkSize) {
                    Result.success(buffer.copyOf(bytesRead))
                } else {
                    Result.success(buffer)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading file chunk from ${file.name}", e)
            Result.failure(e)
        }
    }

    suspend fun compressGzip(data: ByteArray): ByteArray = withContext(Dispatchers.Default) {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { gzip ->
            gzip.write(data)
        }
        bos.toByteArray()
    }

    suspend fun decompressGzip(compressed: ByteArray): ByteArray = withContext(Dispatchers.Default) {
        val bis = ByteArrayInputStream(compressed)
        val bos = ByteArrayOutputStream()
        GZIPInputStream(bis).use { gzip ->
            val buffer = ByteArray(STREAM_BUFFER_SIZE)
            var len: Int
            while (gzip.read(buffer).also { len = it } > 0) {
                bos.write(buffer, 0, len)
            }
        }
        bos.toByteArray()
    }

    /**
     * Processes massive datasets in streaming pages without artificial caps.
     */
    suspend fun <T> processInBatches(
        pageSize: Int = 1000,
        maxItems: Int = Int.MAX_VALUE,
        fetchPage: suspend (limit: Int, offset: Int) -> List<T>,
        onBatchProcessed: suspend (DatasetChunk<T>) -> Unit
    ) = withContext(Dispatchers.IO) {
        var offset = 0
        var pageIndex = 0
        var totalProcessed = 0
        var hasMore = true

        while (hasMore && totalProcessed < maxItems) {
            val pressure = getMemoryPressureLevel()
            val effectivePageSize = when (pressure) {
                MemoryPressureLevel.CRITICAL -> (pageSize / 2).coerceAtLeast(100)
                MemoryPressureLevel.MODERATE -> pageSize
                MemoryPressureLevel.LOW -> pageSize * 2
            }.coerceAtMost(maxItems - totalProcessed)

            val items = fetchPage(effectivePageSize, offset)
            if (items.isEmpty()) {
                hasMore = false
            } else {
                totalProcessed += items.size
                val isLast = items.size < effectivePageSize || totalProcessed >= maxItems
                onBatchProcessed(
                    DatasetChunk(
                        items = items,
                        pageIndex = pageIndex,
                        isLastPage = isLast,
                        totalProcessedSoFar = totalProcessed
                    )
                )
                offset += items.size
                pageIndex++
                if (isLast) hasMore = false
            }
        }
    }
}
