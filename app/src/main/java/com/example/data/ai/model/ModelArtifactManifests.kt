package com.example.data.ai.model

enum class ModelRuntimeStatus {
    DECLARED,
    AVAILABLE_PENDING_DOWNLOAD,
    DOWNLOADING,
    VERIFIED_INTEGRITY,
    LOCAL_WEIGHTS_PRESENT,
    ACTIVE_LOADED,
    FAILED_INITIALIZATION
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
    /** Empty until the exact upstream artifact digest has been independently verified. */
    val expectedSha256: String,
    val byteSize: Long,
    val quantization: QuantizationType,
    val downloadUrl: String,
    val license: String,
    val minRamRequiredMb: Int,
    val requiredHardwareBackend: LocalExecutionBackend
)

data class HardwareEnvironmentSpecs(
    val totalRamMb: Long,
    val availableRamMb: Long,
    val availableStorageMb: Long,
    val cpuCores: Int,
    val hasNpuAcceleration: Boolean,
    val isLowRamDevice: Boolean,
    val isBatteryLowOrThermalsThrottling: Boolean
)
