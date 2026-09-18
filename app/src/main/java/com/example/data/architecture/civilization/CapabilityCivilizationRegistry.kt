package com.example.data.architecture.civilization

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Capability Civilization Registry.
 *
 * Implements Phase 3 & Phase 7 of the Master Architecture:
 * Replaces scattered and brittle capabilities with a governed "Civilization of Capabilities".
 *
 * Each capability maintains:
 * - ID, Name, Version
 * - Health Score (0.0 to 1.0)
 * - Owner Layer
 * - Dependencies & Required Permissions
 * - Knowledge & Documentation
 * - Real-time Execution Metrics (count, successes, failures, latency)
 * - Verification Provenance & Lifecycle State
 */
enum class CapabilityLifecycleState {
    INCUBATING,
    STABLE,
    DEGRADED,
    EVOLVING,
    DEPRECATED
}

data class CapabilityMetrics(
    val totalExecutions: Long = 0L,
    val successfulExecutions: Long = 0L,
    val failedExecutions: Long = 0L,
    val averageLatencyMs: Long = 0L,
    val lastExecutedTimestamp: Long = 0L
)

data class CivilizedCapability(
    val id: String,
    val name: String,
    val version: String = "1.0.0",
    val healthScore: Float = 1.0f,
    val ownerLayer: String,
    val dependencies: List<String> = emptyList(),
    val requiredPermissions: List<String> = emptyList(),
    val knowledgeSummary: String = "",
    val lifecycleState: CapabilityLifecycleState = CapabilityLifecycleState.STABLE,
    val metrics: CapabilityMetrics = CapabilityMetrics(),
    val isSovereignOffline: Boolean = true
)

typealias CivilizationCapability = CivilizedCapability

object CapabilityCivilizationRegistry {

    private val capabilities = ConcurrentHashMap<String, CivilizedCapability>()
    private val _civilizationState = MutableStateFlow<List<CivilizedCapability>>(emptyList())
    val civilizationState: StateFlow<List<CivilizedCapability>> = _civilizationState.asStateFlow()

    init {
        bootstrapCoreCivilization()
    }

    private fun bootstrapCoreCivilization() {
        register(
            CivilizedCapability(
                id = "local_gguf_inference",
                name = "Sovereign GGUF Neural Inference",
                version = "2.4.0",
                ownerLayer = "BRAIN_REASONING_LAYER",
                dependencies = listOf("llama_cpp_jni", "model_downloader"),
                knowledgeSummary = "Runs 12 sovereign models (Llama, Qwen, DeepSeek, Mistral, Gemma, Phi) entirely offline on device",
                isSovereignOffline = true
            )
        )
        register(
            CivilizedCapability(
                id = "vosk_acoustic_wake",
                name = "Vosk Offline Acoustic Spotter",
                version = "1.5.0",
                ownerLayer = "DEVICE_CONTROL_LAYER",
                dependencies = listOf("audio_record", "vosk_model_downloader"),
                requiredPermissions = listOf("RECORD_AUDIO"),
                knowledgeSummary = "Continuous background listening for 'Hey Wasti' with automatic model bootstrap and zero network reliance",
                isSovereignOffline = true
            )
        )
        register(
            CivilizedCapability(
                id = "accessibility_reality_touch",
                name = "Physical Accessibility Screen & Touch Controller",
                version = "3.1.0",
                ownerLayer = "DEVICE_CONTROL_LAYER",
                dependencies = listOf("wasti_accessibility_service"),
                requiredPermissions = listOf("BIND_ACCESSIBILITY_SERVICE"),
                knowledgeSummary = "Extracts UI hierarchy and performs real touch gestures and taps without security apologies",
                isSovereignOffline = true
            )
        )
        register(
            CivilizedCapability(
                id = "wre_polyglot_compiler",
                name = "WRE Native Polyglot Development Engine",
                version = "2.0.0",
                ownerLayer = "EXECUTION_FABRIC_LAYER",
                dependencies = listOf("native_sandbox", "safe_file_tools"),
                knowledgeSummary = "Compiles and executes Python, Bash, Node.js, and Kotlin within secure Android sandbox",
                isSovereignOffline = true
            )
        )
        register(
            CivilizedCapability(
                id = "omni_consensus_fusion",
                name = "OmniBrain Multi-Model Consensus Fusion",
                version = "1.0.0",
                ownerLayer = "BRAIN_REASONING_LAYER",
                dependencies = listOf("local_gguf_inference", "cloud_api_bridge"),
                knowledgeSummary = "Fuses insights, code implementations, invariants, and actions across all participating models into one master thought",
                isSovereignOffline = true
            )
        )
        register(
            CivilizedCapability(
                id = "lead_radar_b2b",
                name = "Lead Radar & Autonomous B2B Opportunity Engine",
                version = "1.8.0",
                ownerLayer = "CAPABILITY_CIVILIZATION_LAYER",
                dependencies = listOf("web_scraper", "omni_consensus_fusion"),
                knowledgeSummary = "Scrapes, evaluates, and drafts proposals for client leads and freelance opportunities autonomously",
                isSovereignOffline = true
            )
        )
    }

    fun register(capability: CivilizedCapability) {
        capabilities[capability.id] = capability
        _civilizationState.value = capabilities.values.toList()
    }

    fun get(id: String): CivilizedCapability? = capabilities[id]

    fun getAll(): List<CivilizedCapability> = capabilities.values.toList()

    fun search(query: String): List<CivilizedCapability> {
        val q = query.lowercase().trim()
        return capabilities.values.filter {
            it.id.contains(q) || it.name.lowercase().contains(q) || it.knowledgeSummary.lowercase().contains(q)
        }
    }

    @Synchronized
    fun recordExecution(id: String, isSuccess: Boolean, latencyMs: Long) {
        val current = capabilities[id] ?: return
        val m = current.metrics
        val newTotal = m.totalExecutions + 1
        val newSuccess = if (isSuccess) m.successfulExecutions + 1 else m.successfulExecutions
        val newFailed = if (!isSuccess) m.failedExecutions + 1 else m.failedExecutions
        val newAvgLat = if (newTotal > 0) ((m.averageLatencyMs * m.totalExecutions) + latencyMs) / newTotal else latencyMs

        val newHealth = if (newTotal > 0) newSuccess.toFloat() / newTotal.toFloat() else 1.0f
        val newState = when {
            newHealth < 0.6f -> CapabilityLifecycleState.DEGRADED
            newHealth < 0.85f -> CapabilityLifecycleState.EVOLVING
            else -> CapabilityLifecycleState.STABLE
        }

        val updated = current.copy(
            healthScore = newHealth,
            lifecycleState = newState,
            metrics = CapabilityMetrics(
                totalExecutions = newTotal,
                successfulExecutions = newSuccess,
                failedExecutions = newFailed,
                averageLatencyMs = newAvgLat,
                lastExecutedTimestamp = System.currentTimeMillis()
            )
        )
        capabilities[id] = updated
        _civilizationState.value = capabilities.values.toList()
    }
}
