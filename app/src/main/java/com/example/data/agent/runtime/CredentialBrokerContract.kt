package com.example.data.agent.runtime

import java.util.concurrent.ConcurrentHashMap

data class CredentialRef(val keyName: String)

enum class SecretAvailabilityScope {
    SECRET_CONFIGURED_IN_CI,
    SECRET_AVAILABLE_TO_RUNTIME,
    SECRET_AUTHENTICATED,
    SECRET_LAST_VERIFIED,
    SECRET_MISSING
}

data class CredentialHealth(
    val keyName: String,
    val availabilityScope: SecretAvailabilityScope,
    val isAuthenticated: Boolean,
    val lastVerifiedTimestamp: Long = System.currentTimeMillis(),
    val diagnosticMessage: String
)

data class CredentialUsagePolicy(
    val keyName: String,
    val allowAutonomousRead: Boolean = true,
    val requireBiometricForSecretAccess: Boolean = true,
    val maxCallRatePerMinute: Int = 60
)

interface CredentialProvider {
    fun hasCredential(ref: CredentialRef): Boolean
    fun getCredentialHealth(ref: CredentialRef): CredentialHealth
    fun getUsagePolicy(ref: CredentialRef): CredentialUsagePolicy
}

class WastiCredentialBroker : CredentialProvider {

    private val credentialHealthMap = ConcurrentHashMap<String, CredentialHealth>()
    private val policies = ConcurrentHashMap<String, CredentialUsagePolicy>()

    init {
        // Default health statuses for standard credential refs without exposing raw keys
        registerHealth(
            CredentialHealth(
                keyName = "GEMINI_API_KEY",
                availabilityScope = SecretAvailabilityScope.SECRET_AVAILABLE_TO_RUNTIME,
                isAuthenticated = true,
                diagnosticMessage = "Secret available via BuildConfig environment secrets"
            )
        )
        registerHealth(
            CredentialHealth(
                keyName = "OPENAI_API_KEY",
                availabilityScope = SecretAvailabilityScope.SECRET_MISSING,
                isAuthenticated = false,
                diagnosticMessage = "Secret not configured in runtime environment"
            )
        )
        registerHealth(
            CredentialHealth(
                keyName = "GITHUB_ACTIONS_TOKEN",
                availabilityScope = SecretAvailabilityScope.SECRET_CONFIGURED_IN_CI,
                isAuthenticated = false,
                diagnosticMessage = "Secret configured in CI environment only; not injected into runtime APK"
            )
        )
    }

    fun registerHealth(health: CredentialHealth) {
        credentialHealthMap[health.keyName] = health
    }

    fun hasValidCredentials(capabilityId: String): Boolean {
        val mappedKey = when (capabilityId.lowercase()) {
            "gemini_pro", "gemini_flash", "gemini" -> "GEMINI_API_KEY"
            "openai" -> "OPENAI_API_KEY"
            "github_sync", "github_pr" -> "GITHUB_ACTIONS_TOKEN"
            else -> capabilityId
        }
        return hasCredential(CredentialRef(mappedKey))
    }

    override fun hasCredential(ref: CredentialRef): Boolean {
        val health = credentialHealthMap[ref.keyName]
        return health?.availabilityScope == SecretAvailabilityScope.SECRET_AVAILABLE_TO_RUNTIME ||
                health?.availabilityScope == SecretAvailabilityScope.SECRET_AUTHENTICATED
    }

    override fun getCredentialHealth(ref: CredentialRef): CredentialHealth {
        return credentialHealthMap[ref.keyName] ?: CredentialHealth(
            keyName = ref.keyName,
            availabilityScope = SecretAvailabilityScope.SECRET_MISSING,
            isAuthenticated = false,
            diagnosticMessage = "Credential ref unknown"
        )
    }

    override fun getUsagePolicy(ref: CredentialRef): CredentialUsagePolicy {
        return policies[ref.keyName] ?: CredentialUsagePolicy(keyName = ref.keyName)
    }
}
