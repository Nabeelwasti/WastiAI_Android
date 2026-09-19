package com.example.data.ai.runtime

import android.content.Context
import android.util.Log
import com.example.data.ai.engine.HardwareCapabilityDetector
import com.example.data.ai.engine.ModelArtifactManager
import com.example.data.ai.model.ModelArtifactManifest
import com.example.data.ai.model.ModelRuntimeStatus
import com.example.data.ai.model.OpenSourceModelCatalog
import com.example.data.ai.model.QuantizationType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * GGUF Binary Format Header Specification (v2 / v3)
 */
data class GgufHeader(
    val magic: String,
    val version: UInt,
    val tensorCount: ULong,
    val metadataKvCount: ULong,
    val isValidGguf: Boolean
)

object GgufContainerValidator {
    private const val GGUF_MAGIC = 0x46554747 // "GGUF" in little-endian

    fun hasValidGgufHeader(file: File): Boolean {
        if (!file.exists() || file.length() < 8) {
            return false
        }
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val buffer = ByteArray(8)
                raf.readFully(buffer)
                val byteBuf = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)
                val magicInt = byteBuf.int
                val version = byteBuf.int.toUInt()
                magicInt == GGUF_MAGIC && version in 1u..3u
            }
        } catch (_: Exception) {
            false
        }
    }
}

data class GgufTensorInfo(
    val name: String,
    val nDimensions: UInt,
    val dimensions: LongArray,
    val type: UInt,
    val offset: ULong
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
 * Progressive Lifecycle States for Local Neural Runtime.
 * Enforces strict progression: UNAVAILABLE -> CONFIGURED -> INSTALLED -> LOADABLE -> EXECUTABLE -> VERIFIED
 */
enum class LocalNeuralProgressiveState {
    UNAVAILABLE,
    CONFIGURED,
    INSTALLED,
    LOADABLE,
    EXECUTABLE,
    VERIFIED
}

/**
 * JNI Native Bridge to Llama.cpp / GGML Open-Source Inference Engine
 */
object NativeLlamaBridge {
    private const val TAG = "NativeLlamaBridge"
    private var isNativeLibraryLoaded = false

    init {
        try {
            System.loadLibrary("wasti_ai_native")
            isNativeLibraryLoaded = true
            Log.i(TAG, "libwasti_ai_native.so successfully loaded.")
        } catch (_: UnsatisfiedLinkError) {
            try {
                System.loadLibrary("llama")
                isNativeLibraryLoaded = true
                Log.i(TAG, "libllama.so successfully loaded into Wasti AI OS runtime.")
            } catch (_: UnsatisfiedLinkError) {
                isNativeLibraryLoaded = false
                Log.d(TAG, "Native llama/wasti_ai_native shared library not bundled for current ABI (arm64-v8a/x86_64). Falling back to pure verified tensor engine.")
            }
        }
    }

    fun isNativeSupported(): Boolean = isNativeLibraryLoaded

    fun getNativeVersion(): String {
        return if (isNativeLibraryLoaded) {
            try {
                getNativeRuntimeVersion()
            } catch (_: Throwable) {
                "UNAVAILABLE"
            }
        } else {
            "UNAVAILABLE"
        }
    }

    fun isVerifiedNeural(modelHandle: Long, prompt: String): Boolean {
        return if (isNativeLibraryLoaded && modelHandle != 0L) {
            try {
                verifyNeuralInference(modelHandle, prompt)
            } catch (_: Throwable) {
                false
            }
        } else {
            false
        }
    }

    fun hasTensorsLoaded(modelHandle: Long): Boolean {
        return if (isNativeLibraryLoaded && modelHandle != 0L) {
            try {
                hasLoadedTensors(modelHandle)
            } catch (_: Throwable) {
                false
            }
        } else {
            false
        }
    }

    // Native external declarations (bound when native .so is bundled)
    external fun getNativeRuntimeVersion(): String
    external fun initModel(modelPath: String, nThreads: Int, contextLength: Int): Long
    external fun evalPrompt(modelHandle: Long, prompt: String, maxTokens: Int, temperature: Float): String
    external fun freeModel(modelHandle: Long)
    external fun verifyNeuralInference(modelHandle: Long, prompt: String): Boolean
    external fun hasLoadedTensors(modelHandle: Long): Boolean
}

/**
 * Byte-Pair Encoding & Byte-Fallback Tokenizer for On-Device Models
 */
class WastiLocalTokenizer(
    val vocab: Map<String, Int> = emptyMap(),
    val invVocab: Map<Int, String> = emptyMap(),
    val specialTokens: Set<String> = setOf("<|im_start|>", "<|im_end|>", "<s>", "</s>", "<|endoftext|>")
) {
    fun encode(text: String): List<Int> {
        if (text.isEmpty()) return emptyList()
        val tokens = mutableListOf<Int>()
        val words = text.split(Regex("(?<=\\s)|(?=\\s)|(?=[^a-zA-Z0-9\\s])|(?<=[^a-zA-Z0-9\\s])"))
        for (word in words) {
            if (word.isEmpty()) continue
            val id = vocab[word] ?: vocab[word.lowercase()]
            if (id != null) {
                tokens.add(id)
            } else {
                val bytes = word.toByteArray(Charsets.UTF_8)
                for (b in bytes) {
                    val byteId = (b.toInt() and 0xFF) + 3
                    tokens.add(byteId)
                }
            }
        }
        return tokens
    }

    fun decode(tokenIds: List<Int>): String {
        val sb = StringBuilder()
        for (id in tokenIds) {
            val token = invVocab[id]
            if (token != null) {
                if (!specialTokens.contains(token)) {
                    sb.append(token)
                }
            } else if (id in 3..258) {
                val byteVal = (id - 3).toByte()
                sb.append(String(byteArrayOf(byteVal), Charsets.UTF_8))
            }
        }
        return sb.toString()
    }
}

/**
 * On-device local model runtime managing GGUF model artifact validation and native inference dispatch.
 * Strictly adheres to Wasti Zero-Fabrication Law: Never generates pseudo-logits or artificial responses.
 *
 * NOTE: GGUF header parsing and file validation verify binary integrity and metadata on-device.
 * Full neural token generation requires the native llama.cpp shared library (libllama.so) and downloaded
 * model weights. When native libraries or weights are absent, this runtime truthfully reports unavailable
 * states and refuses to simulate tensor execution.
 */
class WastiLocalModelRuntime(
    val context: Context
) {
    companion object {
        private const val TAG = "WastiLocalModelRuntime"
        private const val GGUF_MAGIC = 0x46554747 // "GGUF" in little-endian
    }

    fun parseGgufHeader(file: File): GgufHeader {
        if (!file.exists() || file.length() < 24) {
            return GgufHeader(magic = "", version = 0u, tensorCount = 0uL, metadataKvCount = 0uL, isValidGguf = false)
        }

        return try {
            RandomAccessFile(file, "r").use { raf ->
                val buffer = ByteArray(24)
                raf.readFully(buffer)
                val byteBuf = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)
                
                val magicInt = byteBuf.int
                val magicStr = if (magicInt == GGUF_MAGIC) "GGUF" else ""
                val version = byteBuf.int.toUInt()
                val tensorCount = byteBuf.long.toULong()
                val metadataKvCount = byteBuf.long.toULong()

                val isValid = magicStr == "GGUF" && version in 1u..3u
                GgufHeader(
                    magic = magicStr,
                    version = version,
                    tensorCount = tensorCount,
                    metadataKvCount = metadataKvCount,
                    isValidGguf = isValid
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing GGUF header for ${file.name}", e)
            GgufHeader(magic = "", version = 0u, tensorCount = 0uL, metadataKvCount = 0uL, isValidGguf = false)
        }
    }

    suspend fun executeInferenceDetailed(
        modelId: String,
        prompt: String,
        systemInstruction: String = "",
        maxTokens: Int = 4096,
        temperature: Float = 0.7f
    ): LocalInferenceResult = withContext(Dispatchers.Default) {
        val startTime = System.currentTimeMillis()

        if (com.example.data.di.WastiServiceLocator.emergencyStopController.isEmergencyStopped) {
            val stopReason = com.example.data.di.WastiServiceLocator.emergencyStopController.getReason() ?: "Emergency stop active"
            return@withContext LocalInferenceResult(
                status = LocalInferenceStatus.ABORTED_EMERGENCY_STOP,
                output = "[EMERGENCY_STOP_ACTIVE]: Local neural model inference aborted: $stopReason",
                modelId = modelId,
                latencyMs = 0L,
                isNeuralOutput = false,
                errorMessage = "Execution aborted by emergency stop: $stopReason"
            )
        }

        val modelFile = ModelArtifactManager.getModelFile(context, modelId)
        val manifest = ModelArtifactManager.getManifest(modelId)

        if (!modelFile.exists() || modelFile.length() == 0L) {
            return@withContext LocalInferenceResult(
                status = LocalInferenceStatus.WEIGHTS_MISSING,
                output = "[LOCAL_MODEL_UNAVAILABLE]: Model weights for '$modelId' are not present locally on device. Download required via Model Manager.",
                modelId = modelId,
                latencyMs = System.currentTimeMillis() - startTime,
                isNeuralOutput = false,
                errorMessage = "Model weights not present on device"
            )
        }

        val header = parseGgufHeader(modelFile)
        if (!header.isValidGguf) {
            return@withContext LocalInferenceResult(
                status = LocalInferenceStatus.GGUF_HEADER_CORRUPT,
                output = "[LOCAL_MODEL_CORRUPT]: GGUF header validation failed for '${modelFile.name}'. File may be corrupt or invalid format.",
                modelId = modelId,
                latencyMs = System.currentTimeMillis() - startTime,
                isNeuralOutput = false,
                errorMessage = "GGUF header validation failed"
            )
        }

        // Check hardware requirements
        val specs = HardwareCapabilityDetector.detectHardwareEnvironment(context)
        val minRam = manifest?.minRamRequiredMb ?: 256
        if (specs.totalRamMb < minRam) {
            return@withContext LocalInferenceResult(
                status = LocalInferenceStatus.INSUFFICIENT_RAM,
                output = "[INSUFFICIENT_RAM]: Model requires ${minRam}MB RAM, device only provides ${specs.totalRamMb}MB.",
                modelId = modelId,
                latencyMs = System.currentTimeMillis() - startTime,
                isNeuralOutput = false,
                errorMessage = "Insufficient device RAM"
            )
        }

        // If native llama.cpp backend is bundled, invoke native runtime
        if (NativeLlamaBridge.isNativeSupported()) {
            return@withContext try {
                val handle = NativeLlamaBridge.initModel(modelFile.absolutePath, 4, 2048)
                if (handle != 0L) {
                    val fullPrompt = if (systemInstruction.isNotBlank()) {
                        "<|im_start|>system\n$systemInstruction<|im_end|>\n<|im_start|>user\n$prompt<|im_end|>\n<|im_start|>assistant\n"
                    } else {
                        "<|im_start|>user\n$prompt<|im_end|>\n<|im_start|>assistant\n"
                    }
                    val result = NativeLlamaBridge.evalPrompt(handle, fullPrompt, maxTokens, temperature)
                    val hasTensors = NativeLlamaBridge.hasTensorsLoaded(handle)
                    val isNeuralVerified = if (hasTensors) NativeLlamaBridge.isVerifiedNeural(handle, fullPrompt) else false
                    NativeLlamaBridge.freeModel(handle)

                    val latency = System.currentTimeMillis() - startTime
                    val isGenuineNeural = hasTensors && isNeuralVerified && !result.startsWith("[NATIVE_") && !result.startsWith("[ERROR")

                    if (!isGenuineNeural) {
                        return@withContext LocalInferenceResult(
                            status = LocalInferenceStatus.UNAVAILABLE,
                            output = if (result.startsWith("[NATIVE_") || result.startsWith("[ERROR")) result else "[NATIVE_NEURAL_UNAVAILABLE]: Model '$modelId' container validated, but genuine neural tensor execution is unmapped or unsupported.",
                            modelId = modelId,
                            latencyMs = latency,
                            isNeuralOutput = false,
                            errorMessage = "Neural execution not proven for model",
                            engineUsed = "Wasti Native Tensor Bridge (Unverified/Unsupported)",
                            tokensGenerated = 0
                        )
                    }

                    val estimatedTokens = result.split(Regex("\\s+")).filter { it.isNotBlank() }.size.coerceAtLeast(1)

                    try {
                        val evidence = com.example.data.agent.runtime.VerifiedExecutionEvidence(
                            evidenceSource = com.example.data.agent.runtime.EvidenceSource.LOCAL_MODEL_INFERENCE,
                            subject = "local_native_inference:$modelId",
                            verifiedState = "GENUINE_NEURAL_TENSOR_FORWARD_PASS",
                            confidence = 0.90
                        )
                        com.example.data.agent.runtime.ExecutionProvenanceLedger.recordExecution(
                            taskId = "task_native_${System.currentTimeMillis()}",
                            actionId = "inference_${System.currentTimeMillis()}",
                            capabilityId = "local_native_runtime",
                            providerId = "NativeLlamaBridge",
                            modelId = modelId,
                            inputContent = prompt,
                            outputContent = result,
                            evidence = evidence
                        )
                    } catch (_: Throwable) {
                        // Non-blocking provenance recording
                    }

                    LocalInferenceResult(
                        status = LocalInferenceStatus.SUCCESS,
                        output = result,
                        modelId = modelId,
                        latencyMs = latency,
                        isNeuralOutput = true,
                        engineUsed = "Wasti Native Llama Tensor Engine (Genuine Neural Inference)",
                        tokensGenerated = estimatedTokens
                    )
                } else {
                    LocalInferenceResult(
                        status = LocalInferenceStatus.NATIVE_LOAD_FAILED,
                        output = "[NATIVE_LOAD_FAILED]: Native Llama runtime failed to initialize model handle from GGUF.",
                        modelId = modelId,
                        latencyMs = System.currentTimeMillis() - startTime,
                        isNeuralOutput = false,
                        errorMessage = "Failed to initialize native model handle",
                        tokensGenerated = 0
                    )
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Native inference execution error", e)
                LocalInferenceResult(
                    status = LocalInferenceStatus.NATIVE_EXECUTION_ERROR,
                    output = "[NATIVE_INFERENCE_ERROR]: ${e.message}",
                    modelId = modelId,
                    latencyMs = System.currentTimeMillis() - startTime,
                    isNeuralOutput = false,
                    errorMessage = e.message,
                    tokensGenerated = 0
                )
            }
        }

        // Truthful reporting: Native runtime is unavailable
        val latency = (System.currentTimeMillis() - startTime).coerceAtLeast(5L)
        val modelDesc = OpenSourceModelCatalog.getModelById(modelId)
        val brandName = modelDesc?.brandDisplayName ?: manifest?.canonicalFileName ?: modelId

        LocalInferenceResult(
            status = LocalInferenceStatus.NATIVE_RUNTIME_UNAVAILABLE,
            output = "[LOCAL_MODEL_UNAVAILABLE]: Native neural execution engine (libwasti_ai_native.so / libllama.so) is unavailable for on-device inference for $brandName.",
            modelId = modelId,
            latencyMs = latency,
            isNeuralOutput = false,
            errorMessage = "Native inference engine unavailable",
            engineUsed = "Wasti Native Tensor Bridge (Unavailable)",
            tokensGenerated = 0
        )
    }

    private val provenNeuralExecutionCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Boolean, Long>>()

    /**
     * Determines whether genuine executable neural inference is proven for the given model.
     * Evaluates real tensor mapping and dynamic inference probe, never passing from file presence alone.
     */
    fun isGenuineNeuralExecutionProven(modelId: String): Boolean {
        val now = System.currentTimeMillis()
        provenNeuralExecutionCache[modelId]?.let { (proven, timestamp) ->
            if (now - timestamp < 45_000L) return proven
        }

        if (!NativeLlamaBridge.isNativeSupported()) {
            provenNeuralExecutionCache[modelId] = false to now
            return false
        }
        val modelFile = ModelArtifactManager.getModelFile(context, modelId)
        if (!modelFile.exists() || modelFile.length() < 24) {
            provenNeuralExecutionCache[modelId] = false to now
            return false
        }
        val header = parseGgufHeader(modelFile)
        if (!header.isValidGguf) {
            provenNeuralExecutionCache[modelId] = false to now
            return false
        }

        val proven = try {
            val handle = NativeLlamaBridge.initModel(modelFile.absolutePath, 2, 512)
            if (handle != 0L) {
                val hasTensors = NativeLlamaBridge.hasTensorsLoaded(handle)
                val probeOk = if (hasTensors) {
                    try {
                        NativeLlamaBridge.verifyNeuralInference(handle, "probe")
                    } catch (_: Throwable) {
                        false
                    }
                } else false
                val evalProbeOk = if (hasTensors && probeOk) {
                    try {
                        val testOutput = NativeLlamaBridge.evalPrompt(handle, "probe", 2, 0.5f)
                        testOutput.isNotBlank() && !testOutput.startsWith("[NATIVE_") && !testOutput.startsWith("[ERROR")
                    } catch (_: Throwable) {
                        false
                    }
                } else false
                NativeLlamaBridge.freeModel(handle)
                hasTensors && probeOk && evalProbeOk
            } else false
        } catch (_: Throwable) {
            false
        }

        provenNeuralExecutionCache[modelId] = proven to now
        return proven
    }

    /**
     * Determines truthful progressive lifecycle state for a given local neural model:
     * UNAVAILABLE -> CONFIGURED -> INSTALLED -> LOADABLE -> EXECUTABLE -> VERIFIED
     */
    fun getProgressiveState(modelId: String): LocalNeuralProgressiveState {
        val manifest = ModelArtifactManager.getManifest(modelId) ?: return LocalNeuralProgressiveState.UNAVAILABLE
        val modelFile = ModelArtifactManager.getModelFile(context, modelId)
        if (!modelFile.exists() || modelFile.length() < 24) {
            return LocalNeuralProgressiveState.CONFIGURED
        }

        val header = parseGgufHeader(modelFile)
        if (!header.isValidGguf || header.tensorCount == 0uL) {
            return LocalNeuralProgressiveState.CONFIGURED
        }

        val isLoadable = NativeLlamaBridge.isNativeSupported()
        if (!isLoadable) {
            return LocalNeuralProgressiveState.INSTALLED
        }

        return try {
            val handle = NativeLlamaBridge.initModel(modelFile.absolutePath, 2, 512)
            if (handle != 0L) {
                val hasTensors = NativeLlamaBridge.hasTensorsLoaded(handle)
                val isNeuralProbeOk = if (hasTensors) {
                    try {
                        NativeLlamaBridge.verifyNeuralInference(handle, "probe")
                    } catch (_: Throwable) {
                        false
                    }
                } else false
                val evalProbeOk = if (hasTensors && isNeuralProbeOk) {
                    try {
                        val testOutput = NativeLlamaBridge.evalPrompt(handle, "probe", 2, 0.5f)
                        testOutput.isNotBlank() && !testOutput.startsWith("[NATIVE_") && !testOutput.startsWith("[ERROR")
                    } catch (_: Throwable) {
                        false
                    }
                } else false
                NativeLlamaBridge.freeModel(handle)
                if (hasTensors && isNeuralProbeOk && evalProbeOk) {
                    LocalNeuralProgressiveState.VERIFIED
                } else if (hasTensors && isNeuralProbeOk) {
                    LocalNeuralProgressiveState.EXECUTABLE
                } else if (hasTensors) {
                    LocalNeuralProgressiveState.LOADABLE
                } else {
                    LocalNeuralProgressiveState.LOADABLE
                }
            } else {
                LocalNeuralProgressiveState.LOADABLE
            }
        } catch (_: Throwable) {
            LocalNeuralProgressiveState.LOADABLE
        }
    }

    suspend fun executeInference(
        modelId: String,
        prompt: String,
        systemInstruction: String = "",
        maxTokens: Int = 4096,
        temperature: Float = 0.7f
    ): String {
        return executeInferenceDetailed(modelId, prompt, systemInstruction, maxTokens, temperature).output
    }

    fun getModelRuntimeStatus(modelId: String): ModelRuntimeStatus {
        val file = ModelArtifactManager.getModelFile(context, modelId)
        return when {
            file.exists() && file.length() > 0 -> ModelRuntimeStatus.LOCAL_WEIGHTS_PRESENT
            else -> ModelRuntimeStatus.AVAILABLE_PENDING_DOWNLOAD
        }
    }

    fun getModelRuntimeStatus(manifest: ModelArtifactManifest): ModelRuntimeStatus {
        return getModelRuntimeStatus(manifest.modelId)
    }
}

enum class LocalInferenceStatus {
    SUCCESS,
    WEIGHTS_MISSING,
    GGUF_HEADER_CORRUPT,
    INSUFFICIENT_RAM,
    NATIVE_RUNTIME_UNAVAILABLE,
    NATIVE_LOAD_FAILED,
    NATIVE_EXECUTION_ERROR,
    ABORTED_EMERGENCY_STOP,
    UNAVAILABLE
}

data class LocalInferenceResult(
    val status: LocalInferenceStatus,
    val output: String,
    val modelId: String,
    val latencyMs: Long = 0L,
    val isNeuralOutput: Boolean = false,
    val errorMessage: String? = null,
    val engineUsed: String = "llama.cpp",
    val tokensGenerated: Int = 0
) {
    val error: String? get() = errorMessage
}
