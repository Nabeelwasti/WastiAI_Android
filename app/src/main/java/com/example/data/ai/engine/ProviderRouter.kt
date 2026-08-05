package com.example.data.ai.engine

import com.example.data.ai.model.HealthStatus
import com.example.data.ai.model.ProviderCapability
import com.example.data.ai.model.ProviderRequest
import com.example.data.ai.model.ProviderResponse
import com.example.data.ai.provider.AIProvider

class ProviderRouter(
    private val capabilityRegistry: CapabilityRegistry,
    private val healthMonitor: HealthMonitor,
    private val tokenTracker: TokenUsageTracker,
    private val costTracker: CostTracker,
    private val retryManager: RetryManager
) {

    suspend fun routeAndExecute(
        request: ProviderRequest,
        preferredProviderId: String? = null
    ): ProviderResponse {

        // 1. Find providers matching required capabilities
        val eligibleProviders = if (!preferredProviderId.isNullOrBlank()) {
            val preferred = capabilityRegistry.getProvider(preferredProviderId)
            if (preferred != null && preferred.isAvailable()) {
                listOf(preferred) + capabilityRegistry.getAvailableProviders().filter { it.id != preferredProviderId }
            } else {
                capabilityRegistry.getAvailableProviders()
            }
        } else {
            capabilityRegistry.findProvidersWithCapabilities(request.requiredCapabilities)
        }

        if (eligibleProviders.isEmpty()) {
            val offline = capabilityRegistry.getProvider("offline")
            if (offline != null) {
                return offline.generate(request)
            }
            return ProviderResponse(
                content = "No AI providers currently available. Check system configuration or API keys.",
                providerId = "none",
                providerName = "System Router",
                modelUsed = "none",
                isError = true,
                errorMessage = "No available provider matching requirements"
            )
        }

        // 2. Sort providers by HealthStatus and Latency
        val sortedProviders = eligibleProviders.sortedBy { provider ->
            val health = healthMonitor.getHealth(provider.id)
            val score = when (health?.status) {
                HealthStatus.HEALTHY -> 0
                HealthStatus.DEGRADED -> 1
                HealthStatus.UNHEALTHY -> 2
                null -> 0
            }
            val latency = health?.latencyMs ?: 0L
            score * 100000 + latency
        }

        // 3. Sequential Cascading Execution with Retry and Failover
        var lastErrorMsg = ""
        for (provider in sortedProviders) {
            val startTime = System.currentTimeMillis()
            try {
                val response = retryManager.executeWithRetry(actionName = "Call ${provider.name}") {
                    provider.generate(request)
                }

                if (!response.isError && response.content.isNotBlank()) {
                    val latency = System.currentTimeMillis() - startTime
                    healthMonitor.recordSuccess(provider.id, provider.name, latency)
                    tokenTracker.recordUsage(
                        providerId = provider.id,
                        providerName = provider.name,
                        promptTokens = response.promptTokens,
                        completionTokens = response.completionTokens,
                        costUsd = response.costUsd
                    )
                    costTracker.updateCost()
                    return response
                } else {
                    healthMonitor.recordFailure(provider.id, provider.name)
                    lastErrorMsg = response.errorMessage ?: "Empty response"
                }
            } catch (e: Exception) {
                healthMonitor.recordFailure(provider.id, provider.name)
                lastErrorMsg = e.message ?: "Execution exception"
            }
        }

        // 4. Final Fallback to Offline Core if all remote providers fail
        val offlineProvider = capabilityRegistry.getProvider("offline")
        if (offlineProvider != null) {
            return offlineProvider.generate(request)
        }

        return ProviderResponse(
            content = "All AI provider attempts failed. Last error: $lastErrorMsg",
            providerId = "failed",
            providerName = "Provider Router",
            modelUsed = "none",
            isError = true,
            errorMessage = lastErrorMsg
        )
    }
}
