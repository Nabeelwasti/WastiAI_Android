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

        // 1. Separate online providers from offline fallback provider
        val allAvailable = capabilityRegistry.getAvailableProviders()
        val onlineAvailable = allAvailable.filter { it.id != "offline" }

        val onlineCandidates = if (!preferredProviderId.isNullOrBlank()) {
            val preferred = capabilityRegistry.getProvider(preferredProviderId)
            if (preferred != null && preferred.id != "offline" && preferred.isAvailable()) {
                listOf(preferred) + onlineAvailable.filter { it.id != preferredProviderId }
            } else {
                onlineAvailable
            }
        } else {
            if (request.requiredCapabilities.isEmpty()) onlineAvailable
            else capabilityRegistry.findProvidersWithCapabilities(request.requiredCapabilities).filter { it.id != "offline" }
        }

        // 2. Sort online providers by HealthStatus and Latency, maintaining preferred provider at top if set
        val sortedOnline = if (!preferredProviderId.isNullOrBlank() && onlineCandidates.firstOrNull()?.id == preferredProviderId) {
            val head = onlineCandidates.first()
            val tail = onlineCandidates.drop(1).sortedBy { provider ->
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
            listOf(head) + tail
        } else {
            onlineCandidates.sortedBy { provider ->
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
        }

        // 3. Sequential Cascading Execution across online providers
        var lastErrorMsg = ""
        for (provider in sortedOnline) {
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
                    lastErrorMsg = response.errorMessage ?: "Empty response from ${provider.name}"
                }
            } catch (e: Exception) {
                healthMonitor.recordFailure(provider.id, provider.name)
                lastErrorMsg = e.message ?: "Execution exception on ${provider.name}"
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
