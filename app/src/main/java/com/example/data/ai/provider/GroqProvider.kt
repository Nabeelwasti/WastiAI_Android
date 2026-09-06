package com.example.data.ai.provider

import com.example.data.ai.model.ProviderCapability
import com.example.data.ai.model.ProviderRequest
import com.example.data.ai.model.ProviderResponse
import com.example.data.api.GroqClient
import com.example.data.credential.CredentialRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class GroqProvider : AIProvider {
    override val id: String = "groq"
    override val name: String = "Groq Llama 3.3 Engine"
    override val defaultModel: String = "llama-3.3-70b-versatile"
    override val capabilities: Set<ProviderCapability> = setOf(
        ProviderCapability.TEXT_GENERATION,
        ProviderCapability.STREAMING,
        ProviderCapability.MULTI_TURN
    )

    override fun isAvailable(): Boolean {
        val key = CredentialRegistry.getRawValue("GROQ_API_KEY")
        return !key.isNullOrBlank() && !CredentialRegistry.isPlaceholder(key)
    }

    override suspend fun generate(request: ProviderRequest): ProviderResponse {
        val startTime = System.currentTimeMillis()
        val model = request.modelName ?: defaultModel
        val key = CredentialRegistry.getRawValue("GROQ_API_KEY")
        if (key.isNullOrBlank() || CredentialRegistry.isPlaceholder(key)) {
            return ProviderResponse(
                content = "",
                providerId = id,
                providerName = name,
                modelUsed = model,
                promptTokens = 0,
                completionTokens = 0,
                totalTokens = 0,
                latencyMs = System.currentTimeMillis() - startTime,
                costUsd = 0.0,
                success = false,
                errorMessage = "Groq provider is unavailable: GROQ_API_KEY is not configured or contains placeholder."
            )
        }
        return try {
            val output = GroqClient.generateText(
                prompt = request.prompt,
                systemInstruction = request.systemInstruction,
                modelName = model,
                customApiKey = key
            )
            val latency = System.currentTimeMillis() - startTime
            val promptTokens = (request.prompt.length + request.systemInstruction.length) / 4
            val compTokens = output.length / 4

            ProviderResponse(
                content = output,
                providerId = id,
                providerName = name,
                modelUsed = model,
                promptTokens = promptTokens,
                completionTokens = compTokens,
                latencyMs = latency,
                costUsd = 0.0 // Free / Included Tier
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
                errorMessage = e.message ?: "Groq API error"
            )
        }
    }

    override suspend fun stream(request: ProviderRequest): Flow<String> = flow {
        val res = generate(request)
        if (res.isError) throw IllegalStateException(res.errorMessage ?: "Groq Stream Failed")
        val chunks = res.content.chunked(32)
        for (chunk in chunks) {
            emit(chunk)
        }
    }
}
