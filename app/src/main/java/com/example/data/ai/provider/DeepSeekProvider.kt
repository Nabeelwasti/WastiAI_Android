package com.example.data.ai.provider

import com.example.data.ai.model.ProviderCapability
import com.example.data.ai.model.ProviderRequest
import com.example.data.ai.model.ProviderResponse
import com.example.data.api.DeepSeekClient
import com.example.data.credential.CredentialRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class DeepSeekProvider : AIProvider {
    override val id: String = "deepseek"
    override val name: String = "DeepSeek Reasoner Code"
    override val defaultModel: String = "deepseek-coder"
    override val capabilities: Set<ProviderCapability> = setOf(
        ProviderCapability.TEXT_GENERATION,
        ProviderCapability.STREAMING,
        ProviderCapability.TOOL_CALLING,
        ProviderCapability.MULTI_TURN
    )

    override fun isAvailable(): Boolean {
        val key = CredentialRegistry.getRawValue("DEEPSEEK_API_KEY")
        return key.isNotBlank() && key != "MY_DEEPSEEK_API_KEY"
    }

    override suspend fun generate(request: ProviderRequest): ProviderResponse {
        val startTime = System.currentTimeMillis()
        return try {
            val key = CredentialRegistry.getRawValue("DEEPSEEK_API_KEY")
            val output = DeepSeekClient.generateText(
                prompt = request.prompt,
                systemInstruction = request.systemInstruction,
                apiKey = key
            )
            val latency = System.currentTimeMillis() - startTime
            val promptTokens = (request.prompt.length + request.systemInstruction.length) / 4
            val compTokens = output.length / 4

            ProviderResponse(
                content = output,
                providerId = id,
                providerName = name,
                modelUsed = defaultModel,
                promptTokens = promptTokens,
                completionTokens = compTokens,
                latencyMs = latency,
                costUsd = 0.0001
            )
        } catch (e: Exception) {
            val latency = System.currentTimeMillis() - startTime
            ProviderResponse(
                content = "",
                providerId = id,
                providerName = name,
                modelUsed = defaultModel,
                latencyMs = latency,
                isError = true,
                errorMessage = e.message ?: "DeepSeek API error"
            )
        }
    }

    override suspend fun stream(request: ProviderRequest): Flow<String> = flow {
        val res = generate(request)
        if (res.isError) throw IllegalStateException(res.errorMessage ?: "DeepSeek Stream Failed")
        val chunks = res.content.chunked(32)
        for (chunk in chunks) {
            emit(chunk)
        }
    }
}
