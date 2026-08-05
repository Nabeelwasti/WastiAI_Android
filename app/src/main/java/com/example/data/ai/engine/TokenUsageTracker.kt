package com.example.data.ai.engine

import com.example.data.ai.model.UsageStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

class TokenUsageTracker {
    private val usageMap = ConcurrentHashMap<String, UsageStats>()
    private val _usageFlow = MutableStateFlow<Map<String, UsageStats>>(emptyMap())
    val usageFlow: StateFlow<Map<String, UsageStats>> = _usageFlow.asStateFlow()

    fun recordUsage(providerId: String, providerName: String, promptTokens: Int, completionTokens: Int, costUsd: Double) {
        val current = usageMap[providerId] ?: UsageStats(providerId = providerId, providerName = providerName)
        val updated = current.copy(
            promptTokens = current.promptTokens + promptTokens,
            completionTokens = current.completionTokens + completionTokens,
            totalTokens = current.totalTokens + promptTokens + completionTokens,
            estimatedCostUsd = current.estimatedCostUsd + costUsd
        )
        usageMap[providerId] = updated
        _usageFlow.value = usageMap.toMap()
    }

    fun getTotalUsage(): UsageStats {
        var prompt = 0L
        var comp = 0L
        var total = 0L
        var cost = 0.0
        usageMap.values.forEach {
            prompt += it.promptTokens
            comp += it.completionTokens
            total += it.totalTokens
            cost += it.estimatedCostUsd
        }
        return UsageStats(
            providerId = "aggregate",
            providerName = "Total Aggregate Usage",
            promptTokens = prompt,
            completionTokens = comp,
            totalTokens = total,
            estimatedCostUsd = cost
        )
    }
}
