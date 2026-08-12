package com.example.data.agent.runtime

import java.util.concurrent.ConcurrentHashMap

/**
 * Advertisement describing execution provider capabilities, supported environments,
 * limits, authentication needs, and reliability.
 */
data class ProviderCapabilityAdvertisement(
    val providerId: String,
    val providerName: String,
    val supportedLanguages: List<String>,
    val supportedExecutables: List<String>,
    val requiresNetwork: Boolean = false,
    val maxDurationMs: Long = 60000L,
    val maxOutputSizeBytes: Long = 1048576L, // 1MB
    val supportedOS: String = "ANDROID_LINUX",
    val requiresAuthentication: Boolean = false,
    val costPerExecution: Double = 0.0,
    val reliabilityRating: Double = 0.99
)

interface ExecutionProviderRegistry {
    fun registerProvider(provider: CodeExecutionProvider, advertisement: ProviderCapabilityAdvertisement)
    fun getProvider(providerId: String): CodeExecutionProvider?
    fun findBestProvider(request: ExecutionRequest): Pair<CodeExecutionProvider, ProviderCapabilityAdvertisement>?
    fun getAllAdvertisements(): List<ProviderCapabilityAdvertisement>
}

class WastiExecutionProviderRegistry : ExecutionProviderRegistry {

    private val providers = ConcurrentHashMap<String, Pair<CodeExecutionProvider, ProviderCapabilityAdvertisement>>()

    override fun registerProvider(
        provider: CodeExecutionProvider,
        advertisement: ProviderCapabilityAdvertisement
    ) {
        providers[advertisement.providerId] = Pair(provider, advertisement)
    }

    override fun getProvider(providerId: String): CodeExecutionProvider? {
        return providers[providerId]?.first
    }

    override fun findBestProvider(request: ExecutionRequest): Pair<CodeExecutionProvider, ProviderCapabilityAdvertisement>? {
        // Filter providers that match the request criteria
        val matching = providers.values.filter { (_, ad) ->
            val langMatch = request.language == null || ad.supportedLanguages.contains(request.language.lowercase())
            val execMatch = ad.supportedExecutables.contains(request.executable.lowercase()) || ad.supportedExecutables.contains("*")
            val timeoutMatch = request.timeoutMs <= ad.maxDurationMs
            langMatch && execMatch && timeoutMatch
        }

        // Return highest reliability provider
        return matching.maxByOrNull { it.second.reliabilityRating }
    }

    override fun getAllAdvertisements(): List<ProviderCapabilityAdvertisement> {
        return providers.values.map { it.second }
    }
}
