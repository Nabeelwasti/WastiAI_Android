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
        var hasNpu = false

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

            // Real NPU / NNAPI hardware detection check:
            // Check for presence of NNAPI driver libraries or vendor NPU driver nodes
            try {
                val nnapiLib = File("/system/lib64/libneuralnetworks.so")
                val vendorNpuNode = File("/dev/npu_dev")
                val vendorHexagonNode = File("/dev/fastrpc-cdsp-secure")
                hasNpu = (nnapiLib.exists() || vendorNpuNode.exists() || vendorHexagonNode.exists()) && !isLowRam
            } catch (e: Exception) {
                hasNpu = false
            }
        }

        return HardwareEnvironmentSpecs(
            totalRamMb = maxMemoryMb,
            availableRamMb = freeMemoryMb,
            availableStorageMb = availableStorageMb,
            cpuCores = cores,
            hasNpuAcceleration = hasNpu,
            isLowRamDevice = isLowRam,
            isBatteryLowOrThermalsThrottling = isThermalThrottling || isBatteryLow
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
            expectedSha256 = "PENDING_VERIFICATION",
            byteSize = 1048576000L, // ~1.0 GB
            quantization = QuantizationType.Q4_K_M,
            downloadUrl = "https://huggingface.co/HuggingFaceTB/SmolLM2-1.7B-Instruct-GGUF/resolve/main/smollm2-1.7b-instruct-q4_k_m.gguf",
            license = "Apache 2.0",
            minRamRequiredMb = 256,
            requiredHardwareBackend = LocalExecutionBackend.MOBILE_NPU_CPU_TENSOR,
            isChecksumVerifiedPublished = false
        ),
        "wasti-phi" to ModelArtifactManifest(
            modelId = "wasti-phi",
            canonicalFileName = "Phi-3.5-mini-instruct-Q4_K_M.gguf",
            expectedSha256 = "PENDING_VERIFICATION",
            byteSize = 2147483648L, // ~2.1 GB
            quantization = QuantizationType.Q4_K_M,
            downloadUrl = "https://huggingface.co/microsoft/Phi-3.5-mini-instruct-gguf/resolve/main/Phi-3.5-mini-instruct-Q4_K_M.gguf",
            license = "MIT",
            minRamRequiredMb = 512,
            requiredHardwareBackend = LocalExecutionBackend.LLAMA_CPP_EMBEDDED,
            isChecksumVerifiedPublished = false
        ),
        "wasti-qwen" to ModelArtifactManifest(
            modelId = "wasti-qwen",
            canonicalFileName = "Qwen2.5-Coder-1.5B-Instruct-Q4_K_M.gguf",
            expectedSha256 = "PENDING_VERIFICATION",
            byteSize = 1100000000L,
            quantization = QuantizationType.Q4_K_M,
            downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF/resolve/main/qwen2.5-coder-1.5b-instruct-q4_k_m.gguf",
            license = "Apache 2.0",
            minRamRequiredMb = 384,
            requiredHardwareBackend = LocalExecutionBackend.LLAMA_CPP_EMBEDDED,
            isChecksumVerifiedPublished = false
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
