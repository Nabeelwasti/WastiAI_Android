package com.example.data.architecture.observatory

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Cost Intelligence Engine.
 *
 * Implements Phase 8 Cost Intelligence Layer:
 * Tracks API costs, token consumption, and energy trade-offs before routing requests.
 *
 * Enables Wasti to autonomously decide when to execute locally for $0.00 cost
 * versus escalating to cloud cortex models.
 */
object CostIntelligenceEngine {

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
        // Estimate savings: ~$0.002 per 1k tokens if local inference replaced cloud
        val estimatedSaved = localFreeInferences * 0.0015

        _costLedgerState.value = SystemCostLedger(
            totalEstimatedCostUsd = totalCost,
            totalCloudTokensConsumed = totalTokens,
            localFreeInferencesExecuted = localFreeInferences,
            totalMoneySavedUsd = estimatedSaved,
            providerCosts = providerStats.toMap()
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
