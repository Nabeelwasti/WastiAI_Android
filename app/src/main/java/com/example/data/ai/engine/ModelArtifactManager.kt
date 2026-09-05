package com.example.data.ai.engine

import android.content.Context
import android.os.Environment
import android.os.StatFs
import com.example.data.ai.model.HardwareEnvironmentSpecs
import com.example.data.ai.model.LocalExecutionBackend
import com.example.data.ai.model.ModelArtifactManifest
import com.example.data.ai.model.ModelRuntimeStatus
import com.example.data.ai.model.OpenSourceModelCatalog
import com.example.data.ai.model.QuantizationType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

object HardwareCapabilityDetector {

    fun detectHardwareEnvironment(context: Context?): HardwareEnvironmentSpecs {
        val rt = Runtime.getRuntime()
        val maxMemoryMb = rt.maxMemory() / (1024 * 1024)
        val freeMemoryMb = rt.freeMemory() / (1024 * 1024)

        val availableStorageMb = runCatching {
            val stat = StatFs(Environment.getDataDirectory().path)
            (stat.availableBlocksLong * stat.blockSizeLong) / (1024 * 1024)
        }.getOrDefault(0L)

        val cores = rt.availableProcessors()
        val isLowRam = maxMemoryMb < 512

        // Do not infer NPU availability from Android API level. A real accelerator
        // probe/provider is required before advertising NPU execution.
        val hasNpuAcceleration = false

        // This detector does not own battery/thermal state. Keep this conservative
        // value until a real device-state probe is wired into the router.
        val batteryOrThermalLimited = false

        return HardwareEnvironmentSpecs(
            totalRamMb = maxMemoryMb,
            availableRamMb = freeMemoryMb,
            availableStorageMb = availableStorageMb,
            cpuCores = cores,
            hasNpuAcceleration = hasNpuAcceleration,
            isLowRamDevice = isLowRam,
            isBatteryLowOrThermalsThrottling = batteryOrThermalLimited
        )
    }

    fun canRunModelLocally(
        manifest: ModelArtifactManifest,
        specs: HardwareEnvironmentSpecs
    ): Pair<Boolean, String> {
        if (specs.totalRamMb < manifest.minRamRequiredMb) {
            return false to
                "Insufficient RAM: requires ${manifest.minRamRequiredMb}MB, system provides ${specs.totalRamMb}MB"
        }
        if (specs.availableStorageMb < (manifest.byteSize / (1024 * 1024)) + 500) {
            return false to "Insufficient storage space for model weights."
        }
        if (specs.isBatteryLowOrThermalsThrottling) {
            return false to "Device reports battery/thermal limits that prevent local model execution."
        }
        return true to "Hardware meets declared resource requirements; runtime compatibility must still be verified."
    }
}

object ModelArtifactManager {

    private val _modelStatuses = MutableStateFlow<Map<String, ModelRuntimeStatus>>(emptyMap())
    val modelStatuses: StateFlow<Map<String, ModelRuntimeStatus>> = _modelStatuses.asStateFlow()

    private val manifests = mapOf(
        "wasti-smollm" to ModelArtifactManifest(
            modelId = "wasti-smollm",
            canonicalFileName = "smollm2-1.7b-instruct-q4_k_m.gguf",
            expectedSha256 = "decd2598bc2c8ed08c19adc3c8fdd461ee19ed5708679d1c54ef54a5a30d4f33",
            byteSize = 1055609536L,
            quantization = QuantizationType.Q4_K_M,
            downloadUrl = "https://huggingface.co/HuggingFaceTB/SmolLM2-1.7B-Instruct-GGUF/resolve/main/smollm2-1.7b-instruct-q4_k_m.gguf",
            license = "Apache-2.0",
            minRamRequiredMb = 3072,
            requiredHardwareBackend = LocalExecutionBackend.LLAMA_CPP_EMBEDDED
        ),
        "wasti-phi" to ModelArtifactManifest(
            modelId = "wasti-phi",
            canonicalFileName = "phi-3.5-mini-instruct-q4_k_m.gguf",
            expectedSha256 = "9c547fc0b8ecc2ff64f11e3180e00f8ba0e823a56c591efd9f1773fe7537a526",
            byteSize = 2393232384L,
            quantization = QuantizationType.Q4_K_M,
            downloadUrl = "https://huggingface.co/itlwas/Phi-3.5-mini-instruct-Q4_K_M-GGUF/resolve/main/phi-3.5-mini-instruct-q4_k_m.gguf",
            license = "MIT",
            minRamRequiredMb = 4096,
            requiredHardwareBackend = LocalExecutionBackend.LLAMA_CPP_EMBEDDED
        ),
        "wasti-qwen" to ModelArtifactManifest(
            modelId = "wasti-qwen",
            canonicalFileName = "qwen2.5-coder-1.5b-instruct-q4_k_m.gguf",
            expectedSha256 = "cc324af070c2ecbfd324a30884d2f951a7ff756aba85cb811a6ec436933bb046",
            byteSize = 1117320768L,
            quantization = QuantizationType.Q4_K_M,
            downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF/resolve/main/qwen2.5-coder-1.5b-instruct-q4_k_m.gguf",
            license = "Apache-2.0",
            minRamRequiredMb = 3072,
            requiredHardwareBackend = LocalExecutionBackend.LLAMA_CPP_EMBEDDED
        )
    )

    init {
        val initialMap = mutableMapOf<String, ModelRuntimeStatus>()
        OpenSourceModelCatalog.ALL_MODELS.forEach { model ->
            initialMap[model.id] = if (manifests.containsKey(model.id)) {
                ModelRuntimeStatus.AVAILABLE_PENDING_DOWNLOAD
            } else {
                ModelRuntimeStatus.DECLARED
            }
        }
        _modelStatuses.value = initialMap
    }

    fun getManifest(modelId: String): ModelArtifactManifest? = manifests[modelId]

    fun getModelFile(context: Context, modelId: String): File {
        val modelsDir = File(context.filesDir, "wasti_models")
        if (!modelsDir.exists() && !modelsDir.mkdirs()) {
            throw IllegalStateException("Unable to create local model directory")
        }
        val manifest = manifests[modelId]
        val fileName = manifest?.canonicalFileName ?: "$modelId.gguf"
        return File(modelsDir, fileName)
    }

    fun isWeightsPresent(context: Context, modelId: String): Boolean {
        val file = getModelFile(context, modelId)
        val manifest = manifests[modelId] ?: return false
        return file.isFile() && file.length() == manifest.byteSize && file.length() > 0
    }

    suspend fun verifyModelIntegrity(file: File, expectedSha256: String): Boolean =
        withContext(Dispatchers.IO) {
            if (!file.isFile() || file.length() <= 0) return@withContext false
            if (!expectedSha256.matches(Regex("[0-9a-fA-F]{64}"))) return@withContext false
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(1024 * 1024)
                FileInputStream(file).use { fis ->
                    var read: Int
                    while (fis.read(buffer).also { read = it } != -1) {
                        if (read > 0) digest.update(buffer, 0, read)
                    }
                }
                val calculated = digest.digest().joinToString("") { "%02x".format(it) }
                calculated.equals(expectedSha256, ignoreCase = true)
            } catch (_: Exception) {
                false
            }
        }

    fun updateStatus(modelId: String, status: ModelRuntimeStatus) {
        val current = _modelStatuses.value.toMutableMap()
        current[modelId] = status
        _modelStatuses.value = current
    }
}
