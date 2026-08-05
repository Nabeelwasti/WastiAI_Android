package com.example.data.ai.engine

import com.example.data.ai.model.HealthStatus
import com.example.data.ai.model.ProviderHealth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

class HealthMonitor {
    private val healthMap = ConcurrentHashMap<String, ProviderHealth>()
    private val _healthFlow = MutableStateFlow<Map<String, ProviderHealth>>(emptyMap())
    val healthFlow: StateFlow<Map<String, ProviderHealth>> = _healthFlow.asStateFlow()

    fun initializeProvider(providerId: String, providerName: String) {
        if (!healthMap.containsKey(providerId)) {
            val initialHealth = ProviderHealth(
                providerId = providerId,
                providerName = providerName,
                status = HealthStatus.HEALTHY,
                latencyMs = 0,
                successRatePercentage = 100.0f
            )
            healthMap[providerId] = initialHealth
            updateFlow()
        }
    }

    fun recordSuccess(providerId: String, providerName: String, latencyMs: Long) {
        val current = healthMap[providerId] ?: ProviderHealth(providerId = providerId, providerName = providerName)
        val newTotal = current.totalRequests + 1
        val newFailed = current.failedRequests
        val successRate = ((newTotal - newFailed).toFloat() / newTotal.toFloat()) * 100.0f
        
        val newLatency = if (current.latencyMs == 0L) latencyMs else (current.latencyMs * 0.7 + latencyMs * 0.3).toLong()
        val newStatus = when {
            successRate >= 95.0f -> HealthStatus.HEALTHY
            successRate >= 70.0f -> HealthStatus.DEGRADED
            else -> HealthStatus.UNHEALTHY
        }

        val updated = current.copy(
            status = newStatus,
            latencyMs = newLatency,
            totalRequests = newTotal,
            successRatePercentage = successRate,
            lastCheckTimestamp = System.currentTimeMillis()
        )
        healthMap[providerId] = updated
        updateFlow()
    }

    fun recordFailure(providerId: String, providerName: String) {
        val current = healthMap[providerId] ?: ProviderHealth(providerId = providerId, providerName = providerName)
        val newTotal = current.totalRequests + 1
        val newFailed = current.failedRequests + 1
        val successRate = ((newTotal - newFailed).toFloat() / newTotal.toFloat()) * 100.0f

        val newStatus = when {
            successRate >= 95.0f -> HealthStatus.HEALTHY
            successRate >= 70.0f -> HealthStatus.DEGRADED
            else -> HealthStatus.UNHEALTHY
        }

        val updated = current.copy(
            status = newStatus,
            totalRequests = newTotal,
            failedRequests = newFailed,
            successRatePercentage = successRate,
            lastCheckTimestamp = System.currentTimeMillis()
        )
        healthMap[providerId] = updated
        updateFlow()
    }

    fun getHealth(providerId: String): ProviderHealth? = healthMap[providerId]

    private fun updateFlow() {
        _healthFlow.value = healthMap.toMap()
    }
}
