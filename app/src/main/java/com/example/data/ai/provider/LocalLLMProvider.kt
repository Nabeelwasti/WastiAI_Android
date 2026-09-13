package com.example.data.ai.provider

import com.example.data.ai.model.ProviderCapability
import com.example.data.ai.model.ProviderRequest
import com.example.data.ai.model.ProviderResponse
import com.example.data.api.LocalLLMClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class LocalLLMProvider : AIProvider {
    override val id: String = "local_llm"
    override val name: String = "Local Sovereign LLM (Ollama / llama.cpp)"
    override val defaultModel: String = "llama3.2:latest"
    override val capabilities: Set<ProviderCapability> = setOf(
        ProviderCapability.TEXT_GENERATION,
        ProviderCapability.STREAMING,
        ProviderCapability.MULTI_TURN
    )

    override fun isAvailable(): Boolean {
        // Truthfully available when local server is configured or reachable
        return com.example.data.credential.CredentialRegistry.getRawValue("LOCAL_LLM_URL")?.isNotBlank() == true
    }

    override suspend fun generate(request: ProviderRequest): ProviderResponse {
        val startTime = System.currentTimeMillis()
        val prompt = request.prompt
        val systemInstruction = request.systemInstruction

        return try {
            val output = LocalLLMClient.generateText(
                prompt = prompt,
                systemInstruction = systemInstruction,
                modelName = defaultModel
            )
            val latency = System.currentTimeMillis() - startTime
            if (output.isNotBlank()) {
                ProviderResponse(
                    content = output,
                    providerId = id,
                    providerName = name,
                    modelUsed = "$defaultModel [LOCAL_NEURAL]",
                    promptTokens = prompt.length / 4,
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
                    errorMessage = "Local LLM server (Ollama/llama.cpp) was not reachable on candidate endpoints (127.0.0.1:11434, 127.0.0.1:8080)."
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
                errorMessage = e.message ?: "Local LLM server communication error"
            )
        }
    }

    override suspend fun stream(request: ProviderRequest): Flow<String> = flow {
        val res = generate(request)
        if (res.isError) throw IllegalStateException(res.errorMessage ?: "Local LLM Stream Failed")
        val chunks = res.content.chunked(32)
        for (chunk in chunks) {
            emit(chunk)
        }
    }
}
