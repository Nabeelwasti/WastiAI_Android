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
        // Check for leaked private tokens or keys
        val containsSecretPattern = output.contains("AIzaSy", ignoreCase = false) ||
                output.contains("sk-proj-", ignoreCase = false) ||
                output.contains("ghp_", ignoreCase = false)

        if (containsSecretPattern) {
            _blockedThreatCount.value += 1
            val sanitized = output.replace(Regex("AIzaSy[A-Za-z0-9_-]{33}"), "[REDACTED_API_KEY]")
                .replace(Regex("sk-proj-[A-Za-z0-9_-]{48}"), "[REDACTED_TOKEN]")
                .replace(Regex("ghp_[A-Za-z0-9]{36}"), "[REDACTED_GITHUB_PAT]")

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
