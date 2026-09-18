package com.example.data.ai.model

import java.io.File

enum class ModelRuntimeStatus {
    DECLARED,                       // Model descriptor declared in catalog
    PENDING_VERIFICATION,           // Model manifest or published checksum pending cryptographic verification
    AVAILABLE_PENDING_DOWNLOAD,     // Quantized weights downloadable from Hugging Face / GGUF hub
    DOWNLOADING,                    // Weight stream in progress
    VERIFIED_INTEGRITY,             // SHA-256 integrity check passed
    LOCAL_WEIGHTS_PRESENT,          // GGUF / ONNX artifact stored in app private storage
    ACTIVE_LOADED,                  // Loaded into memory / active inference session
    FAILED_INITIALIZATION           // Insufficient RAM or incompatible hardware
}

enum class QuantizationType {
    Q4_K_M,
    Q5_K_M,
    Q8_0,
    FP16,
    INT8_ONNX
}

data class ModelArtifactManifest(
    val modelId: String,
    val canonicalFileName: String,
    val expectedSha256: String,
    val byteSize: Long,
    val quantization: QuantizationType,
    val downloadUrl: String,
    val license: String,
    val minRamRequiredMb: Int,
    val requiredHardwareBackend: LocalExecutionBackend,
    val isChecksumVerifiedPublished: Boolean = false,
    val mirrorDownloadUrl: String? = null
) {
    fun getLocalFile(baseDir: File): File = File(baseDir, canonicalFileName)
}

enum class AcceleratorExecutionStatus {
    NOT_DETECTED,                     // No hardware acceleration driver or device node present
    DRIVER_DETECTED_UNVERIFIED,       // System driver/node exists, but no neural kernel execution has been verified
    INITIALIZED_NEURAL_BACKEND,       // NPU/GPU runtime bridge initialized
    ACTIVE_VERIFIED_ACCELERATION      // Verified tensor acceleration on hardware with execution evidence
}

data class HardwareEnvironmentSpecs(
    val totalRamMb: Long,
    val availableRamMb: Long,
    val availableStorageMb: Long,
    val cpuCores: Int,
    val isNpuHardwareDetected: Boolean = false,
    val isGpuHardwareDetected: Boolean = false,
    val acceleratorStatus: AcceleratorExecutionStatus = AcceleratorExecutionStatus.NOT_DETECTED,
    val isLowRamDevice: Boolean = false,
    val isBatteryLowOrThermalsThrottling: Boolean = false,
    val verifiedExecutionEvidence: String? = null,
    val hasNpuAcceleration: Boolean = false,
    val isBatteryLow: Boolean = false,
    val isThermalThrottling: Boolean = false,
    val isLowRam: Boolean = false,
    val cpuArchitecture: String = "arm64-v8a",
    val totalStorageMb: Long = 0L,
    val batteryPercentage: Int = 100,
    val isCharging: Boolean = false,
    val deviceModel: String = "",
    val androidVersion: String = "",
    val hasVulkanSupport: Boolean = false,
    val inferenceViabilityScore: String = ""
)

enum class ModelTier {
    EDGE_FAST,
    BALANCED,
    CLOUD_CORTEX
}

enum class ModelExecutionRuntime {
    NATIVE_LLAMA_CPP,
    ONNX_TENSOR,
    DETERMINISTIC_FALLBACK
}

data class ModelSpec(
    val id: String = "wasti-smollm",
    val name: String = "SmolLM2 1.7B",
    val tier: ModelTier = ModelTier.EDGE_FAST,
    val runtime: ModelExecutionRuntime = ModelExecutionRuntime.NATIVE_LLAMA_CPP,
    val description: String = "Ultra-fast on-device neural execution model"
)

object WastiModelCatalog {
    val MODELS: List<ModelSpec> = listOf(
        ModelSpec(
            id = "wasti-smollm",
            name = "SmolLM2 1.7B",
            tier = ModelTier.EDGE_FAST,
            runtime = ModelExecutionRuntime.NATIVE_LLAMA_CPP,
            description = "Ultra-fast on-device edge model"
        ),
        ModelSpec(
            id = "wasti-llama",
            name = "Llama 3.2 1B",
            tier = ModelTier.EDGE_FAST,
            runtime = ModelExecutionRuntime.NATIVE_LLAMA_CPP,
            description = "General reasoning edge model"
        ),
        ModelSpec(
            id = "wasti-qwen",
            name = "Qwen 2.5 Coder 1.5B",
            tier = ModelTier.BALANCED,
            runtime = ModelExecutionRuntime.NATIVE_LLAMA_CPP,
            description = "Code and automation edge model"
        ),
        ModelSpec(
            id = "wasti-cortex",
            name = "Wasti Cortex Hybrid",
            tier = ModelTier.CLOUD_CORTEX,
            runtime = ModelExecutionRuntime.DETERMINISTIC_FALLBACK,
            description = "Federated cloud cortex model"
        )
    )

    fun getDefaultLocalModel(): ModelSpec = MODELS.first()
}

