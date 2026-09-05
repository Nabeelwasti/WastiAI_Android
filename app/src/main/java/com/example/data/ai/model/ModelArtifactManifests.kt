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
    val isChecksumVerifiedPublished: Boolean = false
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

