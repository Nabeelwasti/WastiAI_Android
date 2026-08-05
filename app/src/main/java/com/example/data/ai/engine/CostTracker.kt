package com.example.data.ai.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class CostTracker(private val tokenTracker: TokenUsageTracker) {
    private val _dailyCostFlow = MutableStateFlow(0.0)
    val dailyCostFlow: StateFlow<Double> = _dailyCostFlow.asStateFlow()

    fun updateCost() {
        val total = tokenTracker.getTotalUsage()
        _dailyCostFlow.value = total.estimatedCostUsd
    }

    fun getTodayCostUsd(): Double {
        return tokenTracker.getTotalUsage().estimatedCostUsd
    }
}
