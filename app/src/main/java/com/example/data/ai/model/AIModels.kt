package com.example.data.ai.model

import com.example.data.api.GeminiContent

enum class ProviderCapability {
    TEXT_GENERATION,
    VISION,
    STREAMING,
    EMBEDDINGS,
    TOOL_CALLING,
    MULTI_TURN
}

enum class HealthStatus {
    HEALTHY,
    DEGRADED,
    UNHEALTHY
}

data class ProviderRequest(
    val prompt: String,
    val systemInstruction: String = "You are Wasti OS, an advanced AI Operating System.",
    val history: List<GeminiContent> = emptyList(),
    val imageInlineData: String? = null,
    val mimeType: String = "image/jpeg",
    val modelName: String? = null,
    val temperature: Float = 0.7f,
    val requiredCapabilities: Set<ProviderCapability> = setOf(ProviderCapability.TEXT_GENERATION)
)

data class ProviderResponse(
    val content: String,
    val providerId: String,
    val providerName: String,
    val modelUsed: String,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val latencyMs: Long = 0,
    val costUsd: Double = 0.0,
    val isError: Boolean = false,
    val errorMessage: String? = null
)

data class ProviderHealth(
    val providerId: String,
    val providerName: String,
    val status: HealthStatus = HealthStatus.HEALTHY,
    val latencyMs: Long = 0,
    val successRatePercentage: Float = 100.0f,
    val rateLimitUsagePercentage: Float = 0.0f,
    val totalRequests: Long = 0,
    val failedRequests: Long = 0,
    val lastCheckTimestamp: Long = System.currentTimeMillis()
)

data class UsageStats(
    val providerId: String,
    val providerName: String,
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    val totalTokens: Long = 0,
    val estimatedCostUsd: Double = 0.0
)

data class ToolCallDefinition(
    val name: String,
    val description: String,
    val parametersJsonSchema: String
)

data class ToolCallResult(
    val toolName: String,
    val success: Boolean,
    val resultJson: String
)
