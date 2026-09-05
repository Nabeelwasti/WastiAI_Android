package com.example.data.ai.runtime

import android.content.Context
import android.util.Log
import com.example.data.ai.engine.ModelArtifactManager
import com.example.data.ai.model.QuantizationType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class GgufHeader(
    val magic: String,
    val version: UInt,
    val tensorCount: ULong,
    val metadataKvCount: ULong,
    val isValidGguf: Boolean
)

data class GgufModelMetadata(
    val architecture: String = "transformer",
    val contextLength: Int = 2048,
    val embeddingLength: Int = 512,
    val blockCount: Int = 16,
    val headCount: Int = 8,
    val vocabSize: Int = 32000,
    val quantization: QuantizationType = QuantizationType.Q4_K_M
)

/**
 * Compatibility tokenizer boundary retained for callers that already depend on it.
 * It is not used as a substitute for a model tokenizer during neural inference.
 */
class WastiLocalTokenizer(
    val vocab: Map<String, Int> = emptyMap(),
    val invVocab: Map<Int, String> = emptyMap(),
    val specialTokens: Set<String> = setOf("<|im_start|>", "<|im_end|>", "<s>", "</s>", "<|endoftext|>")
) {
    fun encode(text: String): List<Int> = text
        .split(Regex("(?<=\\s)|(?=\\s)|(?=[^a-zA-Z0-9\\s])|(?<=[^a-zA-Z0-9\\s])"))
        .filter { it.isNotEmpty() }
        .mapNotNull { vocab[it] ?: vocab[it.lowercase()] }

    fun decode(tokenIds: List<Int>): String = buildString {
        tokenIds.forEach { id ->
            val token = invVocab[id] ?: return@forEach
            if (!specialTokens.contains(token)) append(token)
        }
    }
}

/**
 * Native local-model runtime boundary.
 *
 * The previous implementation generated pseudo-random tokens in Kotlin and presented them as
 * neural inference. That path has been removed. Real GGUF inference must come from the native
 * backend loaded below (for example a llama.cpp/ggml Android build).
 */
class WastiLocalModelRuntime(
    private val context: Context
) {
    companion object {
        private const val TAG = "WastiLocalModelRuntime"
        private const val GGUF_MAGIC = 0x46554747
        private const val NATIVE_LIBRARY = "wasti_llama"

        @Volatile
        private var nativeLoadAttempted = false

        @Volatile
        private var nativeLoaded = false

        fun isNativeInferenceBackendAvailable(): Boolean {
            if (nativeLoaded) return true
            if (nativeLoadAttempted) return false
            synchronized(this) {
                if (nativeLoaded) return true
                if (nativeLoadAttempted) return false
                nativeLoadAttempted = true
                return try {
                    System.loadLibrary(NATIVE_LIBRARY)
                    nativeLoaded = true
                    true
                } catch (t: Throwable) {
                    Log.w(TAG, "Native local inference backend is not linked: ${t.javaClass.simpleName}")
                    false
                }
            }
        }
    }

    suspend fun parseGgufHeader(file: File): GgufHeader = withContext(Dispatchers.IO) {
        if (!file.isFile || file.length() < 24L) {
            return@withContext GgufHeader("", 0u, 0uL, 0uL, false)
        }
        try {
            RandomAccessFile(file, "r").use { raf ->
                val buffer = ByteArray(24)
                raf.readFully(buffer)
                val bytes = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)
                val magic = if (bytes.int == GGUF_MAGIC) "GGUF" else ""
                val version = bytes.int.toUInt()
                val tensorCount = bytes.long.toULong()
                val metadataKvCount = bytes.long.toULong()
                GgufHeader(
                    magic = magic,
                    version = version,
                    tensorCount = tensorCount,
                    metadataKvCount = metadataKvCount,
                    isValidGguf = magic == "GGUF" && version in 1u..3u
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Unable to parse GGUF header for ${file.name}", e)
            GgufHeader("", 0u, 0uL, 0uL, false)
        }
    }

    suspend fun executeInference(
        modelId: String,
        prompt: String,
        systemInstruction: String = "",
        maxTokens: Int = 256,
        temperature: Float = 0.7f
    ): String = withContext(Dispatchers.Default) {
        val modelFile = ModelArtifactManager.getModelFile(context, modelId)
        val manifest = ModelArtifactManager.getManifest(modelId)

        require(modelFile.isFile && modelFile.length() > 0L) {
            "Model weights '$modelId' are not present locally."
        }
        require(manifest != null) {
            "No model manifest is registered for '$modelId'."
        }
        require(ModelArtifactManager.verifyModelIntegrity(modelFile, manifest.expectedSha256)) {
            "Model '$modelId' failed integrity verification."
        }

        val header = parseGgufHeader(modelFile)
        require(header.isValidGguf) {
            "Model '$modelId' is not a valid GGUF artifact."
        }
        require(isNativeInferenceBackendAvailable()) {
            "Native local inference backend is not linked. GGUF parsing alone cannot generate neural responses."
        }

        NativeInferenceBridge.generate(
            modelPath = modelFile.absolutePath,
            prompt = prompt,
            systemInstruction = systemInstruction,
            maxTokens = maxTokens,
            temperature = temperature
        )
    }

    private object NativeInferenceBridge {
        external fun generate(
            modelPath: String,
            prompt: String,
            systemInstruction: String,
            maxTokens: Int,
            temperature: Float
        ): String
    }
}
