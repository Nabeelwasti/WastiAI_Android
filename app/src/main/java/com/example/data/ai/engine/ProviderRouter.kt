package com.example.data.ai.engine

import com.example.data.ai.model.HealthStatus
import com.example.data.ai.model.ProviderCapability
import com.example.data.ai.model.ProviderRequest
import com.example.data.ai.model.ProviderResponse
import com.example.data.ai.provider.AIProvider
import kotlin.coroutines.cancellation.CancellationException

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

        // 1. Separate online providers from offline fallback provider, strictly filtered by requiredCapabilities
        val allAvailable = capabilityRegistry.getAvailableProviders()
        val onlineAvailable = allAvailable.filter { it.id != "offline" }

        val capableOnline = if (request.requiredCapabilities.isEmpty()) {
            onlineAvailable
        } else {
            capabilityRegistry.findProvidersWithCapabilities(request.requiredCapabilities).filter { it.id != "offline" }
        }

        val onlineCandidates = if (!preferredProviderId.isNullOrBlank()) {
            val preferred = capableOnline.firstOrNull { it.id == preferredProviderId }
            if (preferred != null && preferred.isAvailable()) {
                listOf(preferred) + capableOnline.filter { it.id != preferredProviderId }
            } else {
                capableOnline
            }
        } else {
            capableOnline
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
        // [The Eternal Manifesto: Resource Intelligence Law]
        // Optimize CPU, RAM, battery, network, storage, latency, cost and execution placement
        val hwSpecs = HardwareCapabilityDetector.detectHardwareEnvironment(null)
        val isConstrained = hwSpecs.isBatteryLow || hwSpecs.isThermalThrottling || hwSpecs.isLowRam

        val resourceOptimizedOnline = if (isConstrained) {
            // Prioritize lightweight, low-latency, or local edge providers to preserve thermals and battery
            sortedOnline.sortedBy { provider ->
                if (provider.id.contains("local") || provider.id.contains("groq")) 0 else 1
            }
        } else {
            sortedOnline
        }

        val attemptedProviders = mutableListOf<String>()
        val providerErrors = mutableListOf<String>()
        var lastErrorMsg = ""

        // 3. Sequential Cascading Execution across online providers
        for (provider in resourceOptimizedOnline) {
            attemptedProviders.add(provider.id)
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

                    val isFailover = attemptedProviders.size > 1
                    return response.copy(
                        isFallback = if (isFailover) true else response.isFallback,
                        fallbackReason = if (isFailover) "Failover after errors on: ${attemptedProviders.dropLast(1).joinToString(", ")}" else response.fallbackReason,
                        attemptedProviders = attemptedProviders.toList()
                    )
                } else {
                    healthMonitor.recordFailure(provider.id, provider.name)
                    val err = response.errorMessage ?: "Empty response from ${provider.name}"
                    lastErrorMsg = err
                    providerErrors.add("${provider.id}: $err")
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                healthMonitor.recordFailure(provider.id, provider.name)
                val err = e.message ?: "Execution exception on ${provider.name}"
                lastErrorMsg = err
                providerErrors.add("${provider.id}: $err")
            }
        }

        // 4. Autonomous Local Brain Fallback if all remote providers fail or are unconfigured
        val localBrainProviders = UnifiedBrain.getAllLocalProviders()
        val capableLocal = localBrainProviders.filter { local ->
            request.requiredCapabilities.isEmpty() || local.capabilities.containsAll(request.requiredCapabilities)
        }
        val preferredLocal = capableLocal.firstOrNull { it.isAvailable() }
            ?: capableLocal.firstOrNull { it.id.contains("llama") || it.id.contains("qwen") || it.id.contains("deepseek") }
            ?: capableLocal.firstOrNull()

        if (preferredLocal != null) {
            attemptedProviders.add(preferredLocal.id)
            try {
                val localResp = preferredLocal.generate(request)
                if (!localResp.isError && localResp.content.isNotBlank()) {
                    val fallbackReasonStr = if (providerErrors.isNotEmpty()) {
                        "Cloud providers unavailable (${providerErrors.joinToString("; ")}). Routed to Sovereign Local Brain (${preferredLocal.name})."
                    } else {
                        "Direct Sovereign Local Brain Routing (${preferredLocal.name})."
                    }
                    return localResp.copy(
                        isFallback = true,
                        fallbackReason = fallbackReasonStr,
                        attemptedProviders = attemptedProviders.toList()
                    )
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                val err = e.message ?: "Exception on ${preferredLocal.name}"
                providerErrors.add("${preferredLocal.id}: $err")
            }
        }

        // 5. Final Fallback to Offline Core if local neural nodes and remote providers are exhausted
        val offlineProvider = capabilityRegistry.getProvider("offline")
        if (offlineProvider != null && offlineProvider.isAvailable()) {
            val missingCapabilities = request.requiredCapabilities.filter { it !in offlineProvider.capabilities }
            if (missingCapabilities.isNotEmpty()) {
                // Offline fallback provider lacks required capabilities (e.g. IMAGE_UNDERSTANDING, TOOL_USE, AUDIO_TRANSCRIPTION)
                // Fail closed truthfully without pretending offline core can handle it!
                attemptedProviders.add(offlineProvider.id)
                return ProviderResponse(
                    content = "Capability mismatch: Request requires [${missingCapabilities.joinToString(", ")}], but offline fallback provider '${offlineProvider.name}' only supports [${offlineProvider.capabilities.joinToString(", ")}]. Failing closed.",
                    providerId = "offline",
                    providerName = offlineProvider.name,
                    modelUsed = "none",
                    isError = true,
                    isFallback = true,
                    fallbackReason = "Offline core lacks required capabilities: ${missingCapabilities.joinToString(", ")}",
                    errorMessage = "Capability Mismatch Error: Missing ${missingCapabilities.joinToString(", ")}",
                    attemptedProviders = attemptedProviders.toList()
                )
            }

            attemptedProviders.add(offlineProvider.id)
            val offlineResp = offlineProvider.generate(request)
            val fallbackReasonStr = if (providerErrors.isNotEmpty()) {
                "All online and local providers failed (${providerErrors.joinToString("; ")}). Routed to offline fallback."
            } else {
                "Offline routing invoked."
            }
            return offlineResp.copy(
                isFallback = true,
                fallbackReason = fallbackReasonStr,
                attemptedProviders = attemptedProviders.toList()
            )
        }

        return ProviderResponse(
            content = "All AI provider attempts failed. Last error: $lastErrorMsg",
            providerId = "failed",
            providerName = "Provider Router",
            modelUsed = "none",
            isError = true,
            isFallback = attemptedProviders.isNotEmpty(),
            fallbackReason = if (attemptedProviders.isNotEmpty()) "All attempted providers failed: ${attemptedProviders.joinToString(", ")}" else null,
            errorMessage = lastErrorMsg,
            attemptedProviders = attemptedProviders.toList()
        )
    }
}
