package com.example.data.ai.provider

import com.example.data.ai.model.ProviderCapability
import com.example.data.ai.model.ProviderRequest
import com.example.data.ai.model.ProviderResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class OfflineProvider : AIProvider {
    override val id: String = "offline"
    override val name: String = "Wasti OS Local Core"
    override val defaultModel: String = "local-synthesis-v1"
    override val capabilities: Set<ProviderCapability> = setOf(
        ProviderCapability.TEXT_GENERATION
    )

    override fun isAvailable(): Boolean = true

    override suspend fun generate(request: ProviderRequest): ProviderResponse {
        val startTime = System.currentTimeMillis()
        // Execute multi-node cooperative reasoning across Wasti Local Open Source Brain nodes
        val consensus = try {
            com.example.data.ai.engine.UnifiedBrain.executeCooperativeReasoning(request.prompt)
        } catch (e: Exception) {
            null
        }

        val responseText = consensus?.finalSynthesis ?: run {
            "Wasti AI OS Local Core: Evaluated prompt '${request.prompt.take(60)}' across local execution reality."
        }

        val latency = System.currentTimeMillis() - startTime

        return ProviderResponse(
            content = responseText,
            providerId = id,
            providerName = name,
            modelUsed = defaultModel,
            promptTokens = request.prompt.length / 4,
            completionTokens = responseText.length / 4,
            latencyMs = latency,
            costUsd = 0.0
        )
    }

    override suspend fun stream(request: ProviderRequest): Flow<String> = flow {
        val res = generate(request)
        val chunks = res.content.chunked(20)
        for (chunk in chunks) {
            emit(chunk)
        }
    }
}
