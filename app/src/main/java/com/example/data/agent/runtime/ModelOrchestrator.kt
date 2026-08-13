package com.example.data.agent.runtime

import java.util.concurrent.ConcurrentHashMap

enum class ProviderHealthStatus {
    HEALTHY,
    DEGRADED,
    RATE_LIMITED,
    UNAVAILABLE,
    UNAUTHENTICATED
}

data class ModelProviderDescriptor(
    val providerId: String,
    val name: String,
    val credentialRef: CredentialRef,
    val isPrimaryPreference: Boolean = false,
    val supportedCapabilities: List<TaskCategory> = emptyList()
)

data class ProviderHealth(
    val providerId: String,
    val status: ProviderHealthStatus,
    val lastPingTimestamp: Long = System.currentTimeMillis(),
    val activeQuotaRemainingPercent: Int = 100,
    val latencyMs: Long = 120,
    val errorMessage: String? = null
)

class ProviderHealthMonitor {
    private val healthMap = ConcurrentHashMap<String, ProviderHealth>()

    init {
        // Register default health status
        healthMap["GEMINI"] = ProviderHealth("GEMINI", ProviderHealthStatus.HEALTHY)
        healthMap["OPENAI"] = ProviderHealth("OPENAI", ProviderHealthStatus.UNAUTHENTICATED, errorMessage = "Key not configured")
        healthMap["ANTHROPIC"] = ProviderHealth("ANTHROPIC", ProviderHealthStatus.UNAUTHENTICATED, errorMessage = "Key not configured")
        healthMap["GROQ"] = ProviderHealth("GROQ", ProviderHealthStatus.UNAUTHENTICATED)
        healthMap["LOCAL_ON_DEVICE"] = ProviderHealth("LOCAL_ON_DEVICE", ProviderHealthStatus.HEALTHY)
    }

    fun getHealth(providerId: String): ProviderHealth {
        return healthMap[providerId] ?: ProviderHealth(providerId, ProviderHealthStatus.UNAVAILABLE, errorMessage = "Provider unknown")
    }

    fun updateHealth(health: ProviderHealth) {
        healthMap[health.providerId] = health
    }
}

class ModelProviderRegistry(
    private val healthMonitor: ProviderHealthMonitor = ProviderHealthMonitor(),
    private val credentialBroker: WastiCredentialBroker = WastiCredentialBroker()
) {
    private val providers = ConcurrentHashMap<String, ModelProviderDescriptor>()

    init {
        registerDefaults()
    }

    private fun registerDefaults() {
        providers["GEMINI"] = ModelProviderDescriptor(
            providerId = "GEMINI",
            name = "Google Gemini AI",
            credentialRef = CredentialRef("GEMINI_API_KEY"),
            isPrimaryPreference = true,
            supportedCapabilities = TaskCategory.values().toList()
        )
        providers["OPENAI"] = ModelProviderDescriptor(
            providerId = "OPENAI",
            name = "OpenAI",
            credentialRef = CredentialRef("OPENAI_API_KEY"),
            supportedCapabilities = listOf(TaskCategory.FAST_CHAT, TaskCategory.CODE_GENERATION, TaskCategory.DEEP_REASONING)
        )
        providers["LOCAL_ON_DEVICE"] = ModelProviderDescriptor(
            providerId = "LOCAL_ON_DEVICE",
            name = "Wasti Local Engine",
            credentialRef = CredentialRef("NONE"),
            supportedCapabilities = listOf(TaskCategory.FAST_CHAT, TaskCategory.PLANNING, TaskCategory.DIAGNOSIS, TaskCategory.SELF_CORRECTION)
        )
    }

    fun registerProvider(descriptor: ModelProviderDescriptor) {
        providers[descriptor.providerId] = descriptor
    }

    fun getAvailableProvidersForTask(category: TaskCategory): List<ModelProviderDescriptor> {
        return providers.values.filter { desc ->
            desc.supportedCapabilities.contains(category) &&
                    credentialBroker.hasCredential(desc.credentialRef) &&
                    healthMonitor.getHealth(desc.providerId).status == ProviderHealthStatus.HEALTHY
        }
    }
}

class ModelOrchestrator(
    private val providerRegistry: ModelProviderRegistry,
    private val geminiCatalog: GeminiModelCatalog,
    private val healthMonitor: ProviderHealthMonitor
) {
    fun selectBestModelAndProvider(taskCategory: TaskCategory): Pair<ModelProviderDescriptor, GeminiModelMetadata?> {
        val availableProviders = providerRegistry.getAvailableProvidersForTask(taskCategory)
        val primaryGemini = availableProviders.firstOrNull { it.providerId == "GEMINI" }

        return if (primaryGemini != null) {
            val model = geminiCatalog.findBestModelForTask(taskCategory)
            Pair(primaryGemini, model)
        } else {
            val fallback = availableProviders.firstOrNull()
                ?: ModelProviderDescriptor(
                    providerId = "LOCAL_ON_DEVICE",
                    name = "Wasti Fallback Engine",
                    credentialRef = CredentialRef("NONE")
                )
            Pair(fallback, null)
        }
    }
}
