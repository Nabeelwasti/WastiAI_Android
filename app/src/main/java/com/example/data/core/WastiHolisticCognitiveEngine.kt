package com.example.data.core

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Environment
import android.util.Log
import com.example.data.db.MemoryEntity
import com.example.data.db.WastiDatabase
import com.example.data.node.WastiNearbyHardwareEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.Calendar

/**
 * [The Eternal Manifesto: The Memory of the Human & Resource Intelligence Law]
 *
 * "Preserve knowledge, goals, preferences, decisions and creativity by consent.
 * Optimize CPU, RAM, battery, network, storage, latency, cost and execution placement."
 *
 * WastiHolisticCognitiveEngine:
 * Unifies 5 dimensions of real-time situational awareness:
 * 1. Environmental Reality (Battery, Thermals, Network, Circadian phase).
 * 2. Silicon & Device Reality (CPU, RAM, Internal Storage, Media index).
 * 3. User Identity & Cognitive Profile (Role, Communication Style, Lifestyle, Likings).
 * 4. Multimodal Biometrics & Face Recognition Signature.
 * 5. Proactive Suggestion & Self-Training Pipeline.
 */

enum class CircadianPhase {
    MORNING_FOCUS,
    AFTERNOON_COLLABORATION,
    EVENING_REVIEW,
    SLEEP_DREAMING_CONSOLIDATION
}

enum class CommunicationStyle {
    CONCISE_EXECUTIVE,
    DEEP_TECHNICAL,
    DIRECT_PRACTICAL
}

enum class BiometricRecognitionStatus {
    NOT_ENROLLED,
    ENROLLED_VERIFIED,
    AUTHENTICATED_ACTIVE,
    SENSOR_UNAVAILABLE
}

data class EnvironmentalReality(
    val batteryPercentage: Int,
    val isCharging: Boolean,
    val isThermalThrottling: Boolean,
    val networkState: String, // "WIFI", "CELLULAR", "MESH_PEER_GATEWAY", "OFFLINE"
    val circadianPhase: CircadianPhase,
    val isInternetReachable: Boolean
)

data class UserCognitiveProfile(
    val displayName: String,
    val primaryRole: UserPrimaryRole,
    val communicationStyle: CommunicationStyle,
    val lifestyleNotes: String,
    val preferredLanguages: List<String>,
    val biometricStatus: BiometricRecognitionStatus,
    val biometricSignatureHash: String? = null
)

data class DeviceMediaInventory(
    val photoCountApprox: Int,
    val videoCountApprox: Int,
    val documentCountApprox: Int,
    val totalStorageGb: Long,
    val freeStorageGb: Long
)

data class ProactiveCognitiveSuggestion(
    val id: String,
    val title: String,
    val description: String,
    val priority: String, // "HIGH", "MEDIUM", "LOW"
    val suggestedActionCommand: String? = null
)

data class HolisticCognitiveSnapshot(
    val environment: EnvironmentalReality,
    val userProfile: UserCognitiveProfile,
    val hardware: SystemHardwareProfile,
    val media: DeviceMediaInventory,
    val activeSuggestions: List<ProactiveCognitiveSuggestion>,
    val timestamp: Long = System.currentTimeMillis()
)

object WastiHolisticCognitiveEngine {

    private const val TAG = "HolisticCognitiveEngine"

    private val _cognitiveSnapshot = MutableStateFlow<HolisticCognitiveSnapshot?>(null)
    val cognitiveSnapshot: StateFlow<HolisticCognitiveSnapshot?> = _cognitiveSnapshot.asStateFlow()

    /**
     * Synthesizes full 5-dimensional awareness snapshot.
     */
    suspend fun inspectHolisticReality(context: Context): HolisticCognitiveSnapshot = withContext(Dispatchers.IO) {
        // 1. Environmental Reality
        val env = inspectEnvironment(context)

        // 2. Hardware Silicon Reality
        val hw = WastiDeepHardwareProfiler.profileSystem(context)

        // 3. User Cognitive Profile
        val user = loadUserCognitiveProfile(context)

        // 4. Media & Storage Reality
        val media = inspectMediaInventory(context)

        // 5. Compute Active Proactive Suggestions
        val suggestions = generateProactiveSuggestions(env, hw, user, media)

        val snapshot = HolisticCognitiveSnapshot(
            environment = env,
            userProfile = user,
            hardware = hw,
            media = media,
            activeSuggestions = suggestions
        )

        _cognitiveSnapshot.value = snapshot
        snapshot
    }

    private fun inspectEnvironment(context: Context): EnvironmentalReality {
        // Battery
        val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { filter ->
            context.registerReceiver(null, filter)
        }
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: 50
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: 100
        val batteryPct = if (scale > 0) (level * 100) / scale else 50
        val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

        // Thermals
        val isThrottling = batteryPct < 15 && !isCharging

        // Connectivity
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val net = cm?.activeNetwork
        val caps = cm?.getNetworkCapabilities(net)

        val isWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val isCellular = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        val hasInternet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

        val swarmState = WastiNearbyHardwareEngine.swarmState.value
        val networkState = when {
            isWifi && hasInternet -> "WIFI"
            isCellular && hasInternet -> "CELLULAR"
            swarmState.discoveredPeers.any { it.isAvailableForHeavyCompute } -> "MESH_PEER_GATEWAY"
            else -> "OFFLINE"
        }

        // Circadian Phase
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val phase = when (hour) {
            in 6..11 -> CircadianPhase.MORNING_FOCUS
            in 12..17 -> CircadianPhase.AFTERNOON_COLLABORATION
            in 18..22 -> CircadianPhase.EVENING_REVIEW
            else -> CircadianPhase.SLEEP_DREAMING_CONSOLIDATION
        }

        return EnvironmentalReality(
            batteryPercentage = batteryPct,
            isCharging = isCharging,
            isThermalThrottling = isThrottling,
            networkState = networkState,
            circadianPhase = phase,
            isInternetReachable = hasInternet
        )
    }

    private suspend fun loadUserCognitiveProfile(context: Context): UserCognitiveProfile {
        val db = WastiDatabase.getDatabase(context)
        val memories = try { db.memoryDao().getMemoriesList() } catch (_: Exception) { emptyList() }

        val role = PersonalizedOnboardingEngine.getUserRole(context)
        val customMission = memories.firstOrNull { it.key == "user_primary_mission" }?.value ?: ""

        val bioMemory = memories.firstOrNull { it.key == "user_biometric_face_signature" }
        val bioStatus = if (bioMemory != null && bioMemory.value.isNotBlank()) {
            BiometricRecognitionStatus.AUTHENTICATED_ACTIVE
        } else {
            BiometricRecognitionStatus.ENROLLED_VERIFIED
        }

        return UserCognitiveProfile(
            displayName = "Sovereign Commander",
            primaryRole = role,
            communicationStyle = CommunicationStyle.CONCISE_EXECUTIVE,
            lifestyleNotes = customMission.ifBlank { "Optimizes for maximum privacy, sovereign computing, and high throughput." },
            preferredLanguages = listOf("Kotlin", "Python", "JavaScript", "C++", "SQL"),
            biometricStatus = bioStatus,
            biometricSignatureHash = bioMemory?.value
        )
    }

    private fun inspectMediaInventory(context: Context): DeviceMediaInventory {
        val totalStorage = Environment.getDataDirectory().totalSpace / (1024 * 1024 * 1024)
        val freeStorage = Environment.getDataDirectory().freeSpace / (1024 * 1024 * 1024)

        // Count items in standard user media directories safely
        var photoCount = 0
        var videoCount = 0
        var docCount = 0

        try {
            val dcim = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
            if (dcim != null && dcim.exists()) {
                photoCount += dcim.walk().maxDepth(2).count { it.isFile && it.extension.lowercase() in listOf("jpg", "jpeg", "png", "webp") }
                videoCount += dcim.walk().maxDepth(2).count { it.isFile && it.extension.lowercase() in listOf("mp4", "mkv", "mov") }
            }
            val docs = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            if (docs != null && docs.exists()) {
                docCount += docs.walk().maxDepth(2).count { it.isFile && it.extension.lowercase() in listOf("pdf", "txt", "md", "json", "doc") }
            }
        } catch (_: Throwable) {
            // Permission boundary protected
        }

        return DeviceMediaInventory(
            photoCountApprox = photoCount,
            videoCountApprox = videoCount,
            documentCountApprox = docCount,
            totalStorageGb = totalStorage,
            freeStorageGb = freeStorage
        )
    }

    private fun generateProactiveSuggestions(
        env: EnvironmentalReality,
        hw: SystemHardwareProfile,
        user: UserCognitiveProfile,
        media: DeviceMediaInventory
    ): List<ProactiveCognitiveSuggestion> {
        val list = mutableListOf<ProactiveCognitiveSuggestion>()

        // 1. Constrained Battery / Thermals
        if (env.batteryPercentage < 25 && !env.isCharging) {
            list.add(
                ProactiveCognitiveSuggestion(
                    id = "sug_power_save",
                    title = "Battery Low (${env.batteryPercentage}%): Enable Swarm Offload",
                    description = "Offload compute-heavy neural inference and code compilation to nearby Desktop PC via Wi-Fi/Bluetooth.",
                    priority = "HIGH",
                    suggestedActionCommand = "swarm status"
                )
            )
        }

        // 2. Offline Mode & Mesh Gateway
        if (!env.isInternetReachable) {
            list.add(
                ProactiveCognitiveSuggestion(
                    id = "sug_offline_mesh",
                    title = "Offline Sovereign Mode Active",
                    description = "100% on-device neural brain active. You can bridge internet connectivity through nearby mesh nodes.",
                    priority = "MEDIUM",
                    suggestedActionCommand = "swarm scan"
                )
            )
        }

        // 3. Nighttime Cognitive Dreaming
        if (env.circadianPhase == CircadianPhase.SLEEP_DREAMING_CONSOLIDATION && env.isCharging) {
            list.add(
                ProactiveCognitiveSuggestion(
                    id = "sug_memory_dreaming",
                    title = "Circadian Window: Memory Dreaming Ready",
                    description = "Device is charging and idle. Ready to consolidate daily experiences, resolve contradictions, and prune noise.",
                    priority = "LOW",
                    suggestedActionCommand = "ai dreaming"
                )
            )
        }

        // 4. Low Free Storage Warning
        if (media.freeStorageGb < 4) {
            list.add(
                ProactiveCognitiveSuggestion(
                    id = "sug_storage_compact",
                    title = "Storage Low (${media.freeStorageGb} GB Free)",
                    description = "Run autonomous memory index compaction and temporary cache cleanup.",
                    priority = "HIGH",
                    suggestedActionCommand = "wre cache clean"
                )
            )
        }

        // 5. Polyglot Readiness
        if (hw.toolchains.hasPython && hw.toolchains.hasNode) {
            list.add(
                ProactiveCognitiveSuggestion(
                    id = "sug_polyglot_ready",
                    title = "Polyglot Coding Studio Ready",
                    description = "Python 3 and Node.js toolchains verified on-device. Polyglot Terminal and Code Studio operational.",
                    priority = "LOW",
                    suggestedActionCommand = "sysinfo"
                )
            )
        }

        return list
    }

    /**
     * Enrolls or updates the on-device biometric / face recognition signature hash.
     * All computations remain strictly local on-device.
     */
    suspend fun recordBiometricSignature(
        context: Context,
        rawBiometricFeatureData: ByteArray
    ) = withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(rawBiometricFeatureData).joinToString("") { "%02x".format(it) }

        val db = WastiDatabase.getDatabase(context)
        val entity = MemoryEntity(
            id = "user_biometric_face_signature",
            key = "user_biometric_face_signature",
            category = "BiometricIdentity",
            value = hash,
            importanceScore = 1.0f
        )
        db.memoryDao().insertMemory(entity)
        Log.i(TAG, "Biometric signature hash securely recorded locally in Room memory.")
    }
}
