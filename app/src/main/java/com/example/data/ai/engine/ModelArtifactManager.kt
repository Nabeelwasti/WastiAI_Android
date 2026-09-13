package com.example.data.ai.engine

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.util.Log
import com.example.data.ai.model.AcceleratorExecutionStatus
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

    private const val TAG = "HardwareDetector"
    private var lastRecordedEvidence: String? = null

    fun recordAcceleratorExecutionEvidence(evidenceString: String) {
        lastRecordedEvidence = evidenceString
    }

    fun clearAcceleratorExecutionEvidence() {
        lastRecordedEvidence = null
    }

    fun detectHardwareEnvironment(context: Context?): HardwareEnvironmentSpecs {
        if (context != null) {
            try {
                val deepProfile = com.example.data.core.WastiDeepHardwareProfiler.profileSystem(context)
                val totalRam = deepProfile.memory.totalRamMb
                val availRam = deepProfile.memory.availableRamMb
                val freeStorage = deepProfile.storage.freeInternalStorageMb
                val totalStorage = deepProfile.storage.totalInternalStorageMb
                val cores = deepProfile.cpu.availableCores
                val arch = deepProfile.cpu.primaryArchitecture
                val isThrottling = deepProfile.power.isThermalThrottling
                val isLowBattery = deepProfile.power.batteryPercentage <= 15 && !deepProfile.power.isCharging

                val vulkanSupported = try {
                    context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL)
                } catch (_: Throwable) {
                    false
                }

                val nnapiLib = File("/system/lib64/libneuralnetworks.so")
                val vendorNpuNode = File("/dev/npu_dev")
                val isNpuDetected = (nnapiLib.exists() || vendorNpuNode.exists()) && !deepProfile.memory.isLowMemory
                val isGpuDetected = vulkanSupported || File("/system/lib64/libvulkan.so").exists() || File("/system/lib64/libOpenCL.so").exists()

                val currentEvidence = lastRecordedEvidence
                val acceleratorStatus = when {
                    currentEvidence != null -> AcceleratorExecutionStatus.ACTIVE_VERIFIED_ACCELERATION
                    isNpuDetected || isGpuDetected -> AcceleratorExecutionStatus.DRIVER_DETECTED_UNVERIFIED
                    else -> AcceleratorExecutionStatus.NOT_DETECTED
                }

                val viability = when {
                    totalRam >= 6000 && availRam >= 2000 && freeStorage >= 3000 -> "EXCELLENT: Capable of 1.5B–3B GGUF Models Locally"
                    totalRam >= 3500 && freeStorage >= 1500 -> "GOOD: Capable of Compact 1B SmolLM2 Locally"
                    else -> "CONSTRAINED: Cloud & Hybrid Routing Recommended"
                }

                return HardwareEnvironmentSpecs(
                    totalRamMb = totalRam,
                    availableRamMb = availRam,
                    availableStorageMb = freeStorage,
                    cpuCores = cores,
                    isNpuHardwareDetected = isNpuDetected,
                    isGpuHardwareDetected = isGpuDetected,
                    acceleratorStatus = acceleratorStatus,
                    isLowRamDevice = deepProfile.memory.isLowMemory,
                    isBatteryLowOrThermalsThrottling = isThrottling || isLowBattery,
                    verifiedExecutionEvidence = currentEvidence,
                    hasNpuAcceleration = acceleratorStatus == AcceleratorExecutionStatus.ACTIVE_VERIFIED_ACCELERATION,
                    isBatteryLow = isLowBattery,
                    isThermalThrottling = isThrottling,
                    isLowRam = deepProfile.memory.isLowMemory,
                    cpuArchitecture = arch,
                    totalStorageMb = totalStorage,
                    batteryPercentage = deepProfile.power.batteryPercentage,
                    isCharging = deepProfile.power.isCharging,
                    deviceModel = "${deepProfile.identity.manufacturer.uppercase()} ${deepProfile.identity.model}",
                    androidVersion = "Android ${deepProfile.os.androidVersion} (API ${deepProfile.os.apiLevel})",
                    hasVulkanSupport = vulkanSupported,
                    inferenceViabilityScore = viability
                )
            } catch (e: Exception) {
                Log.w(TAG, "Deep hardware profiling error: ${e.message}, falling back to basic checks")
            }
        }

        val rt = Runtime.getRuntime()
        val totalMemoryMb = rt.totalMemory() / (1024 * 1024)
        val freeMemoryMb = rt.freeMemory() / (1024 * 1024)
        val maxMemoryMb = rt.maxMemory() / (1024 * 1024)

        var availableStorageMb = 2048L
        try {
            val stat = StatFs(Environment.getDataDirectory().path)
            availableStorageMb = (stat.availableBlocksLong * stat.blockSizeLong) / (1024 * 1024)
        } catch (_: Throwable) {}

        val cores = Runtime.getRuntime().availableProcessors()
        val currentEvidence = lastRecordedEvidence

        return HardwareEnvironmentSpecs(
            totalRamMb = maxMemoryMb,
            availableRamMb = freeMemoryMb,
            availableStorageMb = availableStorageMb,
            cpuCores = cores,
            isNpuHardwareDetected = false,
            isGpuHardwareDetected = false,
            acceleratorStatus = if (currentEvidence != null) AcceleratorExecutionStatus.ACTIVE_VERIFIED_ACCELERATION else AcceleratorExecutionStatus.NOT_DETECTED,
            isLowRamDevice = maxMemoryMb < 512,
            isBatteryLowOrThermalsThrottling = false,
            verifiedExecutionEvidence = currentEvidence,
            hasNpuAcceleration = currentEvidence != null,
            isBatteryLow = false,
            isThermalThrottling = false,
            isLowRam = maxMemoryMb < 512,
            cpuArchitecture = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a",
            totalStorageMb = availableStorageMb * 2,
            batteryPercentage = 100,
            isCharging = true,
            deviceModel = "${Build.MANUFACTURER.uppercase()} ${Build.MODEL}",
            androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            hasVulkanSupport = false,
            inferenceViabilityScore = "BALANCED: Local & Cloud Hybrid"
        )
    }

    fun canRunModelLocally(manifest: ModelArtifactManifest, specs: HardwareEnvironmentSpecs): Pair<Boolean, String> {
        if (specs.totalRamMb < manifest.minRamRequiredMb) {
            return false to "Insufficient RAM: Requires ${manifest.minRamRequiredMb}MB, system provides ${specs.totalRamMb}MB"
        }
        if (specs.availableStorageMb < (manifest.byteSize / (1024 * 1024)) + 500) {
            return false to "Insufficient storage space for model weights."
        }
        if (specs.isBatteryLowOrThermalsThrottling) {
            return false to "Device is battery-constrained or thermally throttling. Heavy local neural inference paused for safety."
        }
        return true to "Hardware meets requirements for local neural inference."
    }
}

object ModelArtifactManager {

    private val _modelStatuses = MutableStateFlow<Map<String, ModelRuntimeStatus>>(emptyMap())
    val modelStatuses: StateFlow<Map<String, ModelRuntimeStatus>> = _modelStatuses.asStateFlow()

    private val manifests = mapOf(
        "wasti-smollm" to ModelArtifactManifest(
            modelId = "wasti-smollm",
            canonicalFileName = "SmolLM2-1.7B-Instruct-Q4_K_M.gguf",
            expectedSha256 = "decd2598bc2c8ed08c19adc3c8fdd461ee19ed5708679d1c54ef54a5a30d4f33",
            byteSize = 1055609536L, // ~1.05 GB
            quantization = QuantizationType.Q4_K_M,
            downloadUrl = "https://huggingface.co/HuggingFaceTB/SmolLM2-1.7B-Instruct-GGUF/resolve/main/smollm2-1.7b-instruct-q4_k_m.gguf",
            mirrorDownloadUrl = "https://hf-mirror.com/HuggingFaceTB/SmolLM2-1.7B-Instruct-GGUF/resolve/main/smollm2-1.7b-instruct-q4_k_m.gguf",
            license = "Apache 2.0",
            minRamRequiredMb = 256,
            requiredHardwareBackend = LocalExecutionBackend.MOBILE_NPU_CPU_TENSOR,
            isChecksumVerifiedPublished = true
        ),
        "wasti-llama" to ModelArtifactManifest(
            modelId = "wasti-llama",
            canonicalFileName = "Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            expectedSha256 = "6f85a640a97cf2bf5b8e764087b1e83da0fdb51d7c9fab7d0fece9385611df83",
            byteSize = 807694464L, // ~808 MB
            quantization = QuantizationType.Q4_K_M,
            downloadUrl = "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            mirrorDownloadUrl = "https://hf-mirror.com/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            license = "Llama 3.2 Community License",
            minRamRequiredMb = 256,
            requiredHardwareBackend = LocalExecutionBackend.MOBILE_NPU_CPU_TENSOR,
            isChecksumVerifiedPublished = true
        ),
        "wasti-gemma" to ModelArtifactManifest(
            modelId = "wasti-gemma",
            canonicalFileName = "gemma-2-2b-it-Q4_K_M.gguf",
            expectedSha256 = "e0aee85060f168f0f2d8473d7ea41ce2f3230c1bc1374847505ea599288a7787",
            byteSize = 1708582752L, // ~1.71 GB
            quantization = QuantizationType.Q4_K_M,
            downloadUrl = "https://huggingface.co/bartowski/gemma-2-2b-it-GGUF/resolve/main/gemma-2-2b-it-Q4_K_M.gguf",
            mirrorDownloadUrl = "https://hf-mirror.com/bartowski/gemma-2-2b-it-GGUF/resolve/main/gemma-2-2b-it-Q4_K_M.gguf",
            license = "Gemma Terms of Use",
            minRamRequiredMb = 384,
            requiredHardwareBackend = LocalExecutionBackend.MOBILE_NPU_CPU_TENSOR,
            isChecksumVerifiedPublished = true
        ),
        "wasti-phi" to ModelArtifactManifest(
            modelId = "wasti-phi",
            canonicalFileName = "Phi-3.5-mini-instruct-Q4_K_M.gguf",
            expectedSha256 = "e4165e3a71af97f1b4820da61079826d8752a2088e313af0c7d346796c38eff5",
            byteSize = 2393232672L, // ~2.39 GB
            quantization = QuantizationType.Q4_K_M,
            downloadUrl = "https://huggingface.co/bartowski/Phi-3.5-mini-instruct-GGUF/resolve/main/Phi-3.5-mini-instruct-Q4_K_M.gguf",
            mirrorDownloadUrl = "https://hf-mirror.com/bartowski/Phi-3.5-mini-instruct-GGUF/resolve/main/Phi-3.5-mini-instruct-Q4_K_M.gguf",
            license = "MIT",
            minRamRequiredMb = 512,
            requiredHardwareBackend = LocalExecutionBackend.LLAMA_CPP_EMBEDDED,
            isChecksumVerifiedPublished = true
        ),
        "wasti-qwen" to ModelArtifactManifest(
            modelId = "wasti-qwen",
            canonicalFileName = "qwen2.5-coder-1.5b-instruct-q4_k_m.gguf",
            expectedSha256 = "cc324af070c2ecbfd324a30884d2f951a7ff756aba85cb811a6ec436933bb046",
            byteSize = 1117320768L, // ~1.12 GB
            quantization = QuantizationType.Q4_K_M,
            downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF/resolve/main/qwen2.5-coder-1.5b-instruct-q4_k_m.gguf",
            mirrorDownloadUrl = "https://hf-mirror.com/Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF/resolve/main/qwen2.5-coder-1.5b-instruct-q4_k_m.gguf",
            license = "Apache 2.0",
            minRamRequiredMb = 384,
            requiredHardwareBackend = LocalExecutionBackend.LLAMA_CPP_EMBEDDED,
            isChecksumVerifiedPublished = true
        ),
        "wasti-deepseek" to ModelArtifactManifest(
            modelId = "wasti-deepseek",
            canonicalFileName = "DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M.gguf",
            expectedSha256 = "ba9acb0bcdb38fa9b8c20fc3133d15797087583ec06149849f28c0768c9998d7",
            byteSize = 1117320768L,
            quantization = QuantizationType.Q4_K_M,
            downloadUrl = "https://huggingface.co/bartowski/DeepSeek-R1-Distill-Qwen-1.5B-GGUF/resolve/main/DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M.gguf",
            mirrorDownloadUrl = "https://hf-mirror.com/bartowski/DeepSeek-R1-Distill-Qwen-1.5B-GGUF/resolve/main/DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M.gguf",
            license = "MIT",
            minRamRequiredMb = 384,
            requiredHardwareBackend = LocalExecutionBackend.LLAMA_CPP_EMBEDDED,
            isChecksumVerifiedPublished = true
        ),
        "wasti-mistral" to ModelArtifactManifest(
            modelId = "wasti-mistral",
            canonicalFileName = "Mistral-7B-Instruct-v0.3-Q4_K_M.gguf",
            expectedSha256 = "fa6747812bcb2a4b06ab74b2aa610b00147b9d6e73db35a680ef7b2fd109b750",
            byteSize = 4368437248L,
            quantization = QuantizationType.Q4_K_M,
            downloadUrl = "https://huggingface.co/bartowski/Mistral-7B-Instruct-v0.3-GGUF/resolve/main/Mistral-7B-Instruct-v0.3-Q4_K_M.gguf",
            mirrorDownloadUrl = "https://hf-mirror.com/bartowski/Mistral-7B-Instruct-v0.3-GGUF/resolve/main/Mistral-7B-Instruct-v0.3-Q4_K_M.gguf",
            license = "Apache 2.0",
            minRamRequiredMb = 1024,
            requiredHardwareBackend = LocalExecutionBackend.LOCAL_OLLAMA_SERVER,
            isChecksumVerifiedPublished = true
        ),
        "wasti-granite" to ModelArtifactManifest(
            modelId = "wasti-granite",
            canonicalFileName = "granite-3.0-2b-instruct-Q4_K_M.gguf",
            expectedSha256 = "25704b2701341c9d917663e6334035a0b0b10fdf57946238cd5554b5517dffad",
            byteSize = 1530235904L,
            quantization = QuantizationType.Q4_K_M,
            downloadUrl = "https://huggingface.co/bartowski/granite-3.0-2b-instruct-GGUF/resolve/main/granite-3.0-2b-instruct-Q4_K_M.gguf",
            mirrorDownloadUrl = "https://hf-mirror.com/bartowski/granite-3.0-2b-instruct-GGUF/resolve/main/granite-3.0-2b-instruct-Q4_K_M.gguf",
            license = "Apache 2.0",
            minRamRequiredMb = 512,
            requiredHardwareBackend = LocalExecutionBackend.MOBILE_NPU_CPU_TENSOR,
            isChecksumVerifiedPublished = true
        ),
        "wasti-glm" to ModelArtifactManifest(
            modelId = "wasti-glm",
            canonicalFileName = "glm-4-9b-chat-Q4_K_M.gguf",
            expectedSha256 = "79484cdc3b60985b2f8023d72418fc6d64eb77c170566fb7c87a4094bfdd1797",
            byteSize = 5543153664L,
            quantization = QuantizationType.Q4_K_M,
            downloadUrl = "https://huggingface.co/THUDM/glm-4-9b-chat-GGUF/resolve/main/glm-4-9b-chat-Q4_K_M.gguf",
            mirrorDownloadUrl = "https://hf-mirror.com/THUDM/glm-4-9b-chat-GGUF/resolve/main/glm-4-9b-chat-Q4_K_M.gguf",
            license = "GLM-4 License",
            minRamRequiredMb = 1536,
            requiredHardwareBackend = LocalExecutionBackend.LOCAL_OLLAMA_SERVER,
            isChecksumVerifiedPublished = true
        ),
        "wasti-commandr" to ModelArtifactManifest(
            modelId = "wasti-commandr",
            canonicalFileName = "c4ai-command-r-v01-Q4_K_M.gguf",
            expectedSha256 = "eb51571cec874d650ca72cceed8c299b390dbae9b1edc2ca0b743fefabdad34b",
            byteSize = 4294967296L,
            quantization = QuantizationType.Q4_K_M,
            downloadUrl = "https://huggingface.co/CohereForAI/c4ai-command-r-v01-GGUF/resolve/main/c4ai-command-r-v01-Q4_K_M-00001-of-00005.gguf",
            mirrorDownloadUrl = "https://hf-mirror.com/CohereForAI/c4ai-command-r-v01-GGUF/resolve/main/c4ai-command-r-v01-Q4_K_M-00001-of-00005.gguf",
            license = "CC-BY-NC 4.0",
            minRamRequiredMb = 2048,
            requiredHardwareBackend = LocalExecutionBackend.WASTI_MESH_FEDERATION,
            isChecksumVerifiedPublished = true
        ),
        "wasti-falcon" to ModelArtifactManifest(
            modelId = "wasti-falcon",
            canonicalFileName = "Falcon3-7B-Instruct-Q4_K_M.gguf",
            expectedSha256 = "7a3ece746facd5da3d2622093ee2c0f785c63002aaf0f4eba4036b2f75734099",
            byteSize = 4452093952L,
            quantization = QuantizationType.Q4_K_M,
            downloadUrl = "https://huggingface.co/tiiuae/Falcon3-7B-Instruct-GGUF/resolve/main/Falcon3-7B-Instruct-Q4_K_M.gguf",
            mirrorDownloadUrl = "https://hf-mirror.com/tiiuae/Falcon3-7B-Instruct-GGUF/resolve/main/Falcon3-7B-Instruct-Q4_K_M.gguf",
            license = "TII Falcon License",
            minRamRequiredMb = 1024,
            requiredHardwareBackend = LocalExecutionBackend.LOCAL_OLLAMA_SERVER,
            isChecksumVerifiedPublished = true
        ),
        "wasti-stablelm" to ModelArtifactManifest(
            modelId = "wasti-stablelm",
            canonicalFileName = "stablelm-2-1_6b-chat-Q4_K_M.gguf",
            expectedSha256 = "fd5a2a9cd60ccb5ac855e2f800ead83c88d9e72944b25adae4bfafa95e2b995e",
            byteSize = 1042317312L,
            quantization = QuantizationType.Q4_K_M,
            downloadUrl = "https://huggingface.co/stabilityai/stablelm-2-1_6b-chat-GGUF/resolve/main/stablelm-2-1_6b-chat-Q4_K_M.gguf",
            mirrorDownloadUrl = "https://hf-mirror.com/stabilityai/stablelm-2-1_6b-chat-GGUF/resolve/main/stablelm-2-1_6b-chat-Q4_K_M.gguf",
            license = "Stability AI Non-Commercial",
            minRamRequiredMb = 256,
            requiredHardwareBackend = LocalExecutionBackend.MOBILE_NPU_CPU_TENSOR,
            isChecksumVerifiedPublished = true
        )
    )

    init {
        // Initialize truthful initial state for all catalog models
        val initialMap = mutableMapOf<String, ModelRuntimeStatus>()
        OpenSourceModelCatalog.ALL_MODELS.forEach { model ->
            val manifest = manifests[model.id]
            initialMap[model.id] = when {
                manifest == null -> ModelRuntimeStatus.DECLARED
                !manifest.isChecksumVerifiedPublished -> ModelRuntimeStatus.PENDING_VERIFICATION
                else -> ModelRuntimeStatus.AVAILABLE_PENDING_DOWNLOAD
            }
        }
        _modelStatuses.value = initialMap
    }

    fun getManifest(modelId: String): ModelArtifactManifest? = manifests[modelId]

    fun getModelFile(context: Context, modelId: String): File {
        val modelsDir = File(context.filesDir, "wasti_models")
        if (!modelsDir.exists()) modelsDir.mkdirs()
        val manifest = manifests[modelId]
        val fileName = manifest?.canonicalFileName ?: "$modelId.gguf"
        return File(modelsDir, fileName)
    }

    fun isWeightsPresent(context: Context, modelId: String): Boolean {
        val file = getModelFile(context, modelId)
        return file.exists() && file.length() > 0
    }

    fun isModelRunnableLocally(context: Context, modelId: String): Pair<Boolean, String> {
        val manifest = manifests[modelId]
            ?: return false to "Model '$modelId' does not have a local on-device artifact manifest declared."

        if (!isWeightsPresent(context, modelId)) {
            return false to "Model weights for '$modelId' are not present locally on device. Download required via Model Manager."
        }

        val specs = HardwareCapabilityDetector.detectHardwareEnvironment(context)
        val hwCheck = HardwareCapabilityDetector.canRunModelLocally(manifest, specs)
        if (!hwCheck.first) {
            return hwCheck
        }

        return true to "Model weights present and device hardware meets requirements for local inference."
    }

    fun getModelStatus(context: Context, modelId: String): ModelRuntimeStatus {
        if (isWeightsPresent(context, modelId)) {
            return ModelRuntimeStatus.LOCAL_WEIGHTS_PRESENT
        }
        val manifest = manifests[modelId] ?: return ModelRuntimeStatus.DECLARED
        return if (manifest.isChecksumVerifiedPublished) {
            ModelRuntimeStatus.AVAILABLE_PENDING_DOWNLOAD
        } else {
            ModelRuntimeStatus.PENDING_VERIFICATION
        }
    }

    fun refreshStatuses(context: Context) {
        val current = _modelStatuses.value.toMutableMap()
        OpenSourceModelCatalog.ALL_MODELS.forEach { model ->
            current[model.id] = getModelStatus(context, model.id)
        }
        _modelStatuses.value = current
    }

    suspend fun verifyModelIntegrity(file: File, expectedSha256: String): Boolean = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext false
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(8192)
            FileInputStream(file).use { fis ->
                var read: Int
                while (fis.read(buffer).also { read = it } != -1) {
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
}
