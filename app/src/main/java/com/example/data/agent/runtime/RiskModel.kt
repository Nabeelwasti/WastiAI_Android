package com.example.data.agent.runtime

enum class RiskLevel {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}

object WastiRiskModel {

    private val SENSITIVE_KEYWORDS = listOf(
        "secret", "key", "password", "credential", "token", ".env", "id_rsa", "keystore", "auth", "private"
    )

    private val SENSITIVE_CONFIG_FILES = listOf(
        "build.gradle", "build.gradle.kts", "androidmanifest.xml", "settings.gradle", "settings.gradle.kts",
        "proguard-rules.pro", ".env"
    )

    private val SENSITIVE_COMPONENTS = listOf(
        "security/", "credential/", "ProductionReadinessGate", "PermissionManager",
        "WastiEmergencyStopController", "ZeroTrustSentinelEngine", "SelfModificationSafetyEngine",
        ".github/workflows", "WastiDatabase", "WastiCore", "MainActivity"
    )

    fun isProtectedPath(targetPath: String?): Boolean {
        if (targetPath.isNullOrBlank()) return false
        val lowerPath = targetPath.replace("\\", "/").lowercase()
        return SENSITIVE_KEYWORDS.any { lowerPath.contains(it) } ||
                SENSITIVE_CONFIG_FILES.any { lowerPath.endsWith(it) || lowerPath.contains("/$it") } ||
                SENSITIVE_COMPONENTS.any { lowerPath.contains(it.lowercase()) }
    }

    fun evaluateRisk(
        tool: AgentTool,
        input: Map<String, Any?>,
        targetPath: String?
    ): RiskLevel {
        val isSensitivePath = isProtectedPath(targetPath)

        return when (tool.permissionLevel) {
            PermissionLevel.SAFE -> {
                if (isSensitivePath) RiskLevel.HIGH else RiskLevel.LOW
            }
            PermissionLevel.CONTROLLED -> {
                if (isSensitivePath) RiskLevel.HIGH else RiskLevel.MEDIUM
            }
            PermissionLevel.PRIVILEGED -> {
                if (isSensitivePath) RiskLevel.CRITICAL else RiskLevel.HIGH
            }
        }
    }
}
