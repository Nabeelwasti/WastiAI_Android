package com.example.data.ai.provider

import com.example.data.ai.model.ProviderCapability
import com.example.data.ai.model.ProviderRequest
import com.example.data.ai.model.ProviderResponse
import com.example.data.api.HuggingFaceClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class HuggingFaceProvider : AIProvider {
    override val id: String = "huggingface"
    override val name: String = "Hugging Face Open Source Hub"
    override val defaultModel: String = "meta-llama/Llama-3.2-3B-Instruct"
    override val capabilities: Set<ProviderCapability> = setOf(
        ProviderCapability.TEXT_GENERATION,
        ProviderCapability.STREAMING,
        ProviderCapability.MULTI_TURN
    )

    override fun isAvailable(): Boolean {
        return HuggingFaceClient.isConfigured()
    }

    override suspend fun generate(request: ProviderRequest): ProviderResponse {
        val startTime = System.currentTimeMillis()
        if (!isAvailable()) {
            return ProviderResponse(
                content = "",
                providerId = id,
                providerName = name,
                modelUsed = defaultModel,
                promptTokens = 0,
                completionTokens = 0,
                latencyMs = System.currentTimeMillis() - startTime,
                costUsd = 0.0,
                isError = true,
                errorMessage = "HUGGINGFACE_ACCESS_TOKEN is not configured or contains placeholder."
            )
        }

        return try {
            val output = HuggingFaceClient.generateText(
                prompt = request.prompt,
                systemInstruction = request.systemInstruction,
                modelId = defaultModel
            )
            val latency = System.currentTimeMillis() - startTime
            if (output.isNotBlank()) {
                ProviderResponse(
                    content = output,
                    providerId = id,
                    providerName = name,
                    modelUsed = "$defaultModel [HUGGINGFACE_NEURAL]",
                    promptTokens = request.prompt.length / 4,
                    completionTokens = output.length / 4,
                    latencyMs = latency,
                    costUsd = 0.0
                )
            } else {
                ProviderResponse(
                    content = "",
                    providerId = id,
                    providerName = name,
                    modelUsed = defaultModel,
                    latencyMs = latency,
                    isError = true,
                    errorMessage = "Hugging Face API returned empty response for $defaultModel"
                )
            }
        } catch (e: Exception) {
            val latency = System.currentTimeMillis() - startTime
            ProviderResponse(
                content = "",
                providerId = id,
                providerName = name,
                modelUsed = defaultModel,
                latencyMs = latency,
                isError = true,
                errorMessage = e.message ?: "Hugging Face API communication error"
            )
        }
    }

    override suspend fun stream(request: ProviderRequest): Flow<String> = flow {
        val res = generate(request)
        if (res.isError) throw IllegalStateException(res.errorMessage ?: "HuggingFace Stream Failed")
        val chunks = res.content.chunked(32)
        for (chunk in chunks) {
            emit(chunk)
        }
    }
}
