package com.example.data.architecture.observatory

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Cost Intelligence Engine.
 *
 * Implements Phase 8 Cost Intelligence Layer:
 * Tracks API costs, token consumption, battery energy trade-offs, and storage delta
 * BEFORE cognitive and execution decisions are finalized.
 *
 * Enables Wasti to autonomously decide when to execute locally for $0.00 cost
 * versus escalating to cloud cortex models.
 */
object CostIntelligenceEngine {

    enum class CostOptimalRoute {
        LOCAL_SOVEREIGN_FREE,  // $0.00 USD, 0 network, minimal battery
        CLOUD_FAST_CHEAP,      // Low latency, < $0.001 (e.g. Groq Llama-3 8B)
        CLOUD_DEEP_CORTEX,     // Full synthesis, $0.01+ (e.g. Claude 3.5 Sonnet / GPT-4o)
        MESH_SWARM_OFFLOAD     // Offloaded to local muscle peer device (zero cloud cost)
    }

    data class PreExecutionCostEstimate(
        val promptLengthChars: Int,
        val estimatedOpenAICostUsd: Double,
        val estimatedGeminiCostUsd: Double,
        val estimatedBatteryDrainMah: Float,
        val estimatedStorageDeltaBytes: Long,
        val recommendedRoute: CostOptimalRoute,
        val rationale: String
    )

    data class ProviderCostSummary(
        val providerId: String,
        val totalPromptTokens: Long = 0L,
        val totalCompletionTokens: Long = 0L,
        val estimatedTotalCostUsd: Double = 0.0,
        val totalCalls: Long = 0L
    )

    data class SystemCostLedger(
        val totalEstimatedCostUsd: Double = 0.0,
        val totalCloudTokensConsumed: Long = 0L,
        val localFreeInferencesExecuted: Long = 0L,
        val totalMoneySavedUsd: Double = 0.0,
        val totalBatteryDrainMahSaved: Float = 0.0f,
        val providerCosts: Map<String, ProviderCostSummary> = emptyMap()
    )

    private val providerStats = mutableMapOf<String, ProviderCostSummary>()
    private var localFreeInferences = 0L

    private val _costLedgerState = MutableStateFlow(SystemCostLedger())
    val costLedgerState: StateFlow<SystemCostLedger> = _costLedgerState.asStateFlow()

    @Synchronized
    fun recordCloudUsage(
        providerId: String,
        promptTokens: Int,
        completionTokens: Int,
        costUsd: Double
    ) {
        val current = providerStats[providerId] ?: ProviderCostSummary(providerId = providerId)
        val updated = current.copy(
            totalPromptTokens = current.totalPromptTokens + promptTokens,
            totalCompletionTokens = current.totalCompletionTokens + completionTokens,
            estimatedTotalCostUsd = current.estimatedTotalCostUsd + costUsd,
            totalCalls = current.totalCalls + 1
        )
        providerStats[providerId] = updated
        publishLedger()
    }

    @Synchronized
    fun recordLocalInferenceFreeOfCharge() {
        localFreeInferences++
        publishLedger()
    }

    private fun publishLedger() {
        val totalCost = providerStats.values.sumOf { it.estimatedTotalCostUsd }
        val totalTokens = providerStats.values.sumOf { it.totalPromptTokens + it.totalCompletionTokens }
        // Estimate savings: ~$0.0015 per local inference replaced cloud
        val estimatedSavedUsd = localFreeInferences * 0.0015
        val batteryMahSaved = localFreeInferences * 0.25f

        _costLedgerState.value = SystemCostLedger(
            totalEstimatedCostUsd = totalCost,
            totalCloudTokensConsumed = totalTokens,
            localFreeInferencesExecuted = localFreeInferences,
            totalMoneySavedUsd = estimatedSavedUsd,
            totalBatteryDrainMahSaved = batteryMahSaved,
            providerCosts = providerStats.toMap()
        )
    }

    /**
     * Estimates cost BEFORE execution is made across OpenAI, Gemini, Battery, and Cloud.
     */
    fun estimatePreExecutionCost(prompt: String): PreExecutionCostEstimate {
        val length = prompt.length
        val estTokens = (length / 4) + 100

        val estOpenAICost = (estTokens / 1000.0) * 0.005
        val estGeminiCost = (estTokens / 1000.0) * 0.0015
        val estBatteryDrain = (estTokens * 0.0004f).coerceAtLeast(0.05f)
        val estStorageDelta = (length * 2L).coerceAtLeast(256L)

        val route = when {
            shouldPreferLocalSovereignty(prompt) -> CostOptimalRoute.LOCAL_SOVEREIGN_FREE
            length > 2000 || prompt.contains("architecture", ignoreCase = true) -> CostOptimalRoute.CLOUD_DEEP_CORTEX
            else -> CostOptimalRoute.CLOUD_FAST_CHEAP
        }

        val rationale = when (route) {
            CostOptimalRoute.LOCAL_SOVEREIGN_FREE -> "Zero-cost sovereign edge execution. Preserves battery and privacy."
            CostOptimalRoute.CLOUD_FAST_CHEAP -> "Low token volume fast inference via cached high-throughput provider."
            CostOptimalRoute.CLOUD_DEEP_CORTEX -> "Complex multi-perspective architectural synthesis requires extended cloud cortex."
            CostOptimalRoute.MESH_SWARM_OFFLOAD -> "Workload routed to nearby compute muscle node over LAN/Wi-Fi Direct."
        }

        return PreExecutionCostEstimate(
            promptLengthChars = length,
            estimatedOpenAICostUsd = estOpenAICost,
            estimatedGeminiCostUsd = estGeminiCost,
            estimatedBatteryDrainMah = estBatteryDrain,
            estimatedStorageDeltaBytes = estStorageDelta,
            recommendedRoute = route,
            rationale = rationale
        )
    }

    /**
     * Determines whether an intent should be routed locally to avoid cost.
     */
    fun shouldPreferLocalSovereignty(prompt: String): Boolean {
        val lower = prompt.lowercase()
        // Code generation, basic queries, device actions, notes, memory recalls should stay 100% free locally
        return lower.length < 500 && !lower.contains("deep web research") && !lower.contains("compare latest news")
    }
}
