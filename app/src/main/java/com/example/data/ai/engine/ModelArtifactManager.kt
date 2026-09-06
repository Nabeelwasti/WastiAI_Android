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
        var isLowRam = maxMemoryMb < 512
        var isThermalThrottling = false
        var isBatteryLow = false
        var isNpuDetected = false
        var isGpuDetected = false

        if (context != null) {
            try {
                val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                if (actManager != null) {
                    val memInfo = ActivityManager.MemoryInfo()
                    actManager.getMemoryInfo(memInfo)
                    isLowRam = memInfo.lowMemory || actManager.isLowRamDevice
                }
            } catch (e: Exception) {
                Log.d(TAG, "ActivityManager query error: ${e.message}")
            }

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                    if (powerManager != null) {
                        val status = powerManager.currentThermalStatus
                        isThermalThrottling = status >= PowerManager.THERMAL_STATUS_SEVERE
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Thermal status query error: ${e.message}")
            }

            try {
                val ifilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
                val batteryStatus = context.registerReceiver(null, ifilter)
                val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                if (level >= 0 && scale > 0) {
                    val batteryPct = (level * 100) / scale
                    isBatteryLow = batteryPct <= 15
                }
            } catch (e: Exception) {
                Log.d(TAG, "Battery level query error: ${e.message}")
            }

            // Real NPU / NNAPI driver detection:
            // Check for presence of NNAPI driver libraries or vendor NPU driver nodes
            try {
                val nnapiLib = File("/system/lib64/libneuralnetworks.so")
                val vendorNpuNode = File("/dev/npu_dev")
                val vendorHexagonNode = File("/dev/fastrpc-cdsp-secure")
                isNpuDetected = (nnapiLib.exists() || vendorNpuNode.exists() || vendorHexagonNode.exists()) && !isLowRam
            } catch (e: Exception) {
                isNpuDetected = false
            }

            // Real GPU driver detection (Vulkan / OpenCL):
            try {
                val vulkanLib = File("/system/lib64/libvulkan.so")
                val openClLib = File("/system/lib64/libOpenCL.so")
                isGpuDetected = vulkanLib.exists() || openClLib.exists()
            } catch (e: Exception) {
                isGpuDetected = false
            }
        }

        // Truthful accelerator state: passive driver file detection does NOT equal active verified acceleration
        val currentEvidence = lastRecordedEvidence
        val acceleratorStatus = when {
            currentEvidence != null -> AcceleratorExecutionStatus.ACTIVE_VERIFIED_ACCELERATION
            isNpuDetected || isGpuDetected -> AcceleratorExecutionStatus.DRIVER_DETECTED_UNVERIFIED
            else -> AcceleratorExecutionStatus.NOT_DETECTED
        }

        return HardwareEnvironmentSpecs(
            totalRamMb = maxMemoryMb,
            availableRamMb = freeMemoryMb,
            availableStorageMb = availableStorageMb,
            cpuCores = cores,
            isNpuHardwareDetected = isNpuDetected,
            isGpuHardwareDetected = isGpuDetected,
            acceleratorStatus = acceleratorStatus,
            isLowRamDevice = isLowRam,
            isBatteryLowOrThermalsThrottling = isThermalThrottling || isBatteryLow,
            verifiedExecutionEvidence = currentEvidence,
            hasNpuAcceleration = acceleratorStatus == AcceleratorExecutionStatus.ACTIVE_VERIFIED_ACCELERATION
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
            license = "Apache 2.0",
            minRamRequiredMb = 384,
            requiredHardwareBackend = LocalExecutionBackend.LLAMA_CPP_EMBEDDED,
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
