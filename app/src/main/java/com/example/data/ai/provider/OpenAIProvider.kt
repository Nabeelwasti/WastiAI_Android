package com.example.data.ai.provider

import com.example.data.ai.model.ProviderCapability
import com.example.data.ai.model.ProviderRequest
import com.example.data.ai.model.ProviderResponse
import com.example.data.api.OpenAIClient
import com.example.data.credential.CredentialRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class OpenAIProvider : AIProvider {
    override val id: String = "openai"
    override val name: String = "OpenAI GPT Core"
    override val defaultModel: String = "gpt-3.5-turbo"
    override val capabilities: Set<ProviderCapability> = setOf(
        ProviderCapability.TEXT_GENERATION,
        ProviderCapability.STREAMING,
        ProviderCapability.TOOL_CALLING,
        ProviderCapability.MULTI_TURN
    )

    override fun isAvailable(): Boolean {
        val key = CredentialRegistry.getRawValue("OPENAI_API_KEY")
        return !key.isNullOrBlank() && !CredentialRegistry.isPlaceholder(key)
    }

    override suspend fun generate(request: ProviderRequest): ProviderResponse {
        val startTime = System.currentTimeMillis()
        val model = request.modelName ?: defaultModel
        val key = CredentialRegistry.getRawValue("OPENAI_API_KEY")
        if (key.isNullOrBlank() || CredentialRegistry.isPlaceholder(key)) {
            return ProviderResponse(
                content = "",
                providerId = id,
                providerName = name,
                modelName = model,
                tokensUsed = 0,
                latencyMs = System.currentTimeMillis() - startTime,
                costUsd = 0.0,
                success = false,
                errorMessage = "OpenAI provider is unavailable: OPENAI_API_KEY is not configured or contains placeholder."
            )
        }
        return try {
            val output = OpenAIClient.generateText(
                prompt = request.prompt,
                systemInstruction = request.systemInstruction,
                apiKey = key,
                modelName = model
            )
            val latency = System.currentTimeMillis() - startTime
            val promptTokens = (request.prompt.length + request.systemInstruction.length) / 4
            val compTokens = output.length / 4
            val cost = ((promptTokens * 0.0015) + (compTokens * 0.002)) / 1000.0

            ProviderResponse(
                content = output,
                providerId = id,
                providerName = name,
                modelUsed = model,
                promptTokens = promptTokens,
                completionTokens = compTokens,
                latencyMs = latency,
                costUsd = cost
            )
        } catch (e: Exception) {
            val latency = System.currentTimeMillis() - startTime
            ProviderResponse(
                content = "",
                providerId = id,
                providerName = name,
                modelUsed = request.modelName ?: defaultModel,
                latencyMs = latency,
                isError = true,
                errorMessage = e.message ?: "OpenAI API error"
            )
        }
    }

    override suspend fun stream(request: ProviderRequest): Flow<String> = flow {
        val res = generate(request)
        if (res.isError) throw IllegalStateException(res.errorMessage ?: "OpenAI Stream Failed")
        val chunks = res.content.chunked(32)
        for (chunk in chunks) {
            emit(chunk)
        }
    }
}
