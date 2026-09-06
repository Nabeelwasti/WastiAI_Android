package com.example.data.security

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

data class SecurityInspectionResult(
    val inspectionId: String = UUID.randomUUID().toString(),
    val isClean: Boolean,
    val threatCategory: String = "NONE", // "PROMPT_INJECTION", "DATA_EXFILTRATION", "UNAUTHORIZED_ESCAPE", "NONE"
    val sanitizedContent: String,
    val riskScore: Float = 0.0f,
    val details: String = "Passed structural security checks."
)

object ZeroTrustSentinelEngine {

    private val _blockedThreatCount = MutableStateFlow(0)
    val blockedThreatCount: StateFlow<Int> = _blockedThreatCount.asStateFlow()

    private val INJECTION_PATTERNS = listOf(
        "ignore previous instructions",
        "ignore all prior instructions",
        "disregard system prompt",
        "system override",
        "developer mode enabled",
        "bypass security filter",
        "send api key to",
        "curl http://attacker"
    )

    private val googleApiKeyRegex by lazy {
        Regex("AIzaSy[A-Za-z0-9_-]{33}")
    }
    private val openAiTokenRegex by lazy {
        Regex("sk-[A-Za-z0-9_-]{32,}")
    }
    private val githubPatRegex by lazy {
        Regex("ghp_[A-Za-z0-9]{36}")
    }

    fun inspectInputPrompt(rawPrompt: String): SecurityInspectionResult {
        val lower = rawPrompt.lowercase()
        for (pattern in INJECTION_PATTERNS) {
            if (lower.contains(pattern)) {
                _blockedThreatCount.value += 1
                return SecurityInspectionResult(
                    isClean = false,
                    threatCategory = "PROMPT_INJECTION",
                    sanitizedContent = "[BLOCKED: Malicious instruction sequence detected]",
                    riskScore = 0.95f,
                    details = "Prompt contained prohibited injection pattern: '$pattern'"
                )
            }
        }

        return SecurityInspectionResult(
            isClean = true,
            threatCategory = "NONE",
            sanitizedContent = rawPrompt,
            riskScore = 0.05f,
            details = "Input verified safe for cognitive pipeline."
        )
    }

    fun inspectModelOutputForExfiltration(output: String): SecurityInspectionResult {
        var sanitized = output
        var threatDetected = false

        // 1. Redact any active credentials registered in CredentialRegistry
        try {
            val activeSecrets = com.example.data.credential.CredentialRegistry.getActiveConfiguredSecrets()
            for (secret in activeSecrets) {
                if (secret.length >= 8 && sanitized.contains(secret)) {
                    sanitized = sanitized.replace(secret, "[REDACTED_CREDENTIAL]")
                    threatDetected = true
                }
            }
        } catch (_: Throwable) {}

        // 2. Redact common model API key patterns if present in model output
        if (googleApiKeyRegex.containsMatchIn(sanitized)) {
            sanitized = googleApiKeyRegex.replace(sanitized, "[REDACTED_API_KEY]")
            threatDetected = true
        }
        if (openAiTokenRegex.containsMatchIn(sanitized)) {
            sanitized = openAiTokenRegex.replace(sanitized, "[REDACTED_TOKEN]")
            threatDetected = true
        }
        if (githubPatRegex.containsMatchIn(sanitized)) {
            sanitized = githubPatRegex.replace(sanitized, "[REDACTED_GITHUB_PAT]")
            threatDetected = true
        }

        if (threatDetected) {
            _blockedThreatCount.value += 1
            return SecurityInspectionResult(
                isClean = false,
                threatCategory = "DATA_EXFILTRATION",
                sanitizedContent = sanitized,
                riskScore = 0.99f,
                details = "Output contained potential credential exposure. Automatically redacted."
            )
        }

        return SecurityInspectionResult(
            isClean = true,
            threatCategory = "NONE",
            sanitizedContent = output,
            riskScore = 0.0f,
            details = "Output verified free of sensitive credentials."
        )
    }
}
