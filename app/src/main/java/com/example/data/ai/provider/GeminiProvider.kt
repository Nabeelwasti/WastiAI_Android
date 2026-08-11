package com.example.data.ai.provider

import com.example.data.ai.model.ProviderCapability
import com.example.data.ai.model.ProviderRequest
import com.example.data.ai.model.ProviderResponse
import com.example.data.api.GeminiClient
import com.example.data.credential.CredentialRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class GeminiProvider : AIProvider {
    override val id: String = "gemini"
    override val name: String = "Google Gemini Engine"
    override val defaultModel: String = "gemini-3.6-flash"
    override val capabilities: Set<ProviderCapability> = setOf(
        ProviderCapability.TEXT_GENERATION,
        ProviderCapability.VISION,
        ProviderCapability.STREAMING,
        ProviderCapability.EMBEDDINGS,
        ProviderCapability.TOOL_CALLING,
        ProviderCapability.MULTI_TURN
    )

    override fun isAvailable(): Boolean {
        val key = CredentialRegistry.getRawValue("GEMINI_API_KEY")
        return !key.isNullOrBlank() && key != "MY_GEMINI_API_KEY"
    }

    override suspend fun generate(request: ProviderRequest): ProviderResponse {
        val startTime = System.currentTimeMillis()
        return try {
            val model = request.modelName ?: defaultModel
            val output = GeminiClient.generateText(
                prompt = request.prompt,
                systemInstruction = request.systemInstruction,
                modelName = model,
                history = request.history,
                imageInlineData = request.imageInlineData,
                mimeType = request.mimeType,
                mediaList = request.mediaList
            )
            val latency = System.currentTimeMillis() - startTime
            val promptTokens = (request.prompt.length + request.systemInstruction.length) / 4
            val compTokens = output.length / 4
            val cost = ((promptTokens + compTokens) / 1000.0) * 0.0001

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
                errorMessage = e.message ?: "Gemini API error"
            )
        }
    }

    override suspend fun stream(request: ProviderRequest): Flow<String> = flow {
        val res = generate(request)
        if (res.isError) {
            throw IllegalStateException(res.errorMessage ?: "Gemini Stream Failed")
        }
        val chunks = res.content.chunked(32)
        for (chunk in chunks) {
            emit(chunk)
        }
    }
}
