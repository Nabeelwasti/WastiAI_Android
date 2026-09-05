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
        val runtime = Runtime.getRuntime()
        val maxMemoryMb = runtime.maxMemory() / (1024 * 1024)
        val freeMemoryMb = runtime.freeMemory() / (1024 * 1024)

        var availableStorageMb = 2048L
        try {
            val stat = StatFs(Environment.getDataDirectory().path)
            availableStorageMb = (stat.availableBlocksLong * stat.blockSizeLong) / (1024 * 1024)
        } catch (_: Throwable) {
            // Unknown storage is represented conservatively; callers must not treat it as verified.
        }

        return HardwareEnvironmentSpecs(
            totalRamMb = maxMemoryMb,
            availableRamMb = freeMemoryMb,
            availableStorageMb = availableStorageMb,
            cpuCores = runtime.availableProcessors(),
            // API level alone does not prove that an NPU is exposed to this app/runtime.
            hasNpuAcceleration = false,
            isLowRamDevice = maxMemoryMb < 512,
            // Thermal/battery state must be sampled by the execution layer at run time.
            isBatteryLowOrThermalsThrottling = false
        )
    }

    fun canRunModelLocally(
        manifest: ModelArtifactManifest,
        specs: HardwareEnvironmentSpecs
    ): Pair<Boolean, String> {
        if (specs.totalRamMb < manifest.minRamRequiredMb) {
            return false to "Insufficient RAM: requires ${manifest.minRamRequiredMb}MB, runtime exposes ${specs.totalRamMb}MB"
        }
        if (specs.availableStorageMb < (manifest.byteSize / (1024 * 1024)) + 500) {
            return false to "Insufficient storage space for model weights."
        }
        if (!isTrustedSha256(manifest.expectedSha256)) {
            return false to "Model artifact checksum is not independently verified."
        }
        return true to "Declared hardware requirements and integrity metadata are satisfied."
    }

    private fun isTrustedSha256(value: String): Boolean =
        value.length == 64 && value.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }
}

object ModelArtifactManager {

    private val _modelStatuses = MutableStateFlow<Map<String, ModelRuntimeStatus>>(emptyMap())
    val modelStatuses: StateFlow<Map<String, ModelRuntimeStatus>> = _modelStatuses.asStateFlow()

    private val manifests = mapOf(
        "wasti-smollm" to ModelArtifactManifest(
            modelId = "wasti-smollm",
            canonicalFileName = "SmolLM2-1.7B-Instruct-Q4_K_M.gguf",
            expectedSha256 = "",
            byteSize = 1048576000L,
            quantization = QuantizationType.Q4_K_M,
            downloadUrl = "https://huggingface.co/HuggingFaceTB/SmolLM2-1.7B-Instruct-GGUF/resolve/main/smollm2-1.7b-instruct-q4_k_m.gguf",
            license = "Apache 2.0",
            minRamRequiredMb = 256,
            requiredHardwareBackend = LocalExecutionBackend.MOBILE_NPU_CPU_TENSOR
        ),
        "wasti-phi" to ModelArtifactManifest(
            modelId = "wasti-phi",
            canonicalFileName = "Phi-3.5-mini-instruct-Q4_K_M.gguf",
            expectedSha256 = "",
            byteSize = 2147483648L,
            quantization = QuantizationType.Q4_K_M,
            downloadUrl = "https://huggingface.co/microsoft/Phi-3.5-mini-instruct-gguf/resolve/main/Phi-3.5-mini-instruct-Q4_K_M.gguf",
            license = "MIT",
            minRamRequiredMb = 512,
            requiredHardwareBackend = LocalExecutionBackend.LLAMA_CPP_EMBEDDED
        ),
        "wasti-qwen" to ModelArtifactManifest(
            modelId = "wasti-qwen",
            canonicalFileName = "Qwen2.5-Coder-1.5B-Instruct-Q4_K_M.gguf",
            expectedSha256 = "",
            byteSize = 1100000000L,
            quantization = QuantizationType.Q4_K_M,
            downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF/resolve/main/qwen2.5-coder-1.5b-instruct-q4_k_m.gguf",
            license = "Apache 2.0",
            minRamRequiredMb = 384,
            requiredHardwareBackend = LocalExecutionBackend.LLAMA_CPP_EMBEDDED
        )
    )

    init {
        val initialMap = mutableMapOf<String, ModelRuntimeStatus>()
        OpenSourceModelCatalog.ALL_MODELS.forEach { model ->
            val manifest = manifests[model.id]
            initialMap[model.id] = if (manifest != null && isTrustedSha256(manifest.expectedSha256)) {
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
        if (!modelsDir.exists()) modelsDir.mkdirs()
        val fileName = manifests[modelId]?.canonicalFileName ?: "$modelId.gguf"
        return File(modelsDir, fileName)
    }

    fun isWeightsPresent(context: Context, modelId: String): Boolean {
        val file = getModelFile(context, modelId)
        return file.isFile && file.length() > 0L
    }

    suspend fun verifyModelIntegrity(file: File, expectedSha256: String): Boolean = withContext(Dispatchers.IO) {
        if (!file.isFile || !isTrustedSha256(expectedSha256)) return@withContext false
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(8192)
            FileInputStream(file).use { input ->
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    digest.update(buffer, 0, read)
                }
            }
            val calculated = digest.digest().joinToString("") { "%02x".format(it) }
            calculated.equals(expectedSha256, ignoreCase = true)
        } catch (_: Throwable) {
            false
        }
    }

    fun updateStatus(modelId: String, status: ModelRuntimeStatus) {
        val current = _modelStatuses.value.toMutableMap()
        current[modelId] = status
        _modelStatuses.value = current
    }

    private fun isTrustedSha256(value: String): Boolean =
        value.length == 64 && value.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }
}
