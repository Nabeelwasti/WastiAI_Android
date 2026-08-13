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
        "build.gradle", "build.gradle.kts", "androidmanifest.xml", "settings.gradle", "settings.gradle.kts", ".env"
    )

    fun isProtectedPath(targetPath: String?): Boolean {
        val lowerPath = targetPath?.lowercase().orEmpty()
        return SENSITIVE_KEYWORDS.any { lowerPath.contains(it) } ||
                SENSITIVE_CONFIG_FILES.any { lowerPath.endsWith(it) }
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
