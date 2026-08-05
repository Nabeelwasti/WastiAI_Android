package com.example.data.ai.engine

import com.example.data.ai.model.ProviderCapability
import com.example.data.ai.provider.AIProvider

class CapabilityRegistry {
    private val providers = mutableListOf<AIProvider>()

    fun registerProvider(provider: AIProvider) {
        if (providers.none { it.id == provider.id }) {
            providers.add(provider)
        }
    }

    fun getAllProviders(): List<AIProvider> = providers.toList()

    fun getAvailableProviders(): List<AIProvider> {
        return providers.filter { it.isAvailable() }
    }

    fun findProvidersWithCapabilities(requiredCapabilities: Set<ProviderCapability>): List<AIProvider> {
        return getAvailableProviders().filter { provider ->
            provider.capabilities.containsAll(requiredCapabilities)
        }
    }

    fun getProvider(id: String): AIProvider? {
        return providers.firstOrNull { it.id == id }
    }
}
