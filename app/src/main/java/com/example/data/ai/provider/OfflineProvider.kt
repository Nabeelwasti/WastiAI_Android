package com.example.data.ai.provider

import com.example.data.ai.engine.BrainConsensusType
import com.example.data.ai.engine.UnifiedBrain
import com.example.data.ai.model.ProviderCapability
import com.example.data.ai.model.ProviderRequest
import com.example.data.ai.model.ProviderResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Offline Fallback Provider for Wasti AI OS.
 * Explicitly distinguishes local neural execution from deterministic heuristic synthesis.
 */
class OfflineProvider : AIProvider {
    override val id: String = "offline"
    override val name: String = "Wasti OS Local Core"
    override val defaultModel: String = "local-synthesis-v1"
    override val capabilities: Set<ProviderCapability> = setOf(
        ProviderCapability.TEXT_GENERATION
    )

    override fun isAvailable(): Boolean = true

    val isNeuralExecutionActive: Boolean
        get() = UnifiedBrain.getAllLocalProviders().any { it.isNeuralInferenceActive }

    val isHeuristicFallbackActive: Boolean
        get() = true

    override suspend fun generate(request: ProviderRequest): ProviderResponse {
        val startTime = System.currentTimeMillis()
        // Execute multi-node cooperative reasoning across Wasti Local Open Source Brain nodes
        val consensus = try {
            UnifiedBrain.executeCooperativeReasoning(request.prompt)
        } catch (e: Exception) {
            null
        }

        val responseText = consensus?.finalSynthesis ?: run {
            "Wasti AI OS Local Core: Evaluated prompt '${request.prompt.take(60)}' across local heuristic execution reality."
        }

        val latency = System.currentTimeMillis() - startTime

        val modelLabel = when (consensus?.consensusType) {
            BrainConsensusType.GENUINE_NEURAL_CONSENSUS -> "local-neural-consensus-v1"
            BrainConsensusType.HYBRID_NEURAL_HEURISTIC -> "local-hybrid-synthesis-v1"
            else -> "local-heuristic-synthesis-v1 [NON_NEURAL_FALLBACK]"
        }

        return ProviderResponse(
            content = responseText,
            providerId = id,
            providerName = name,
            modelUsed = modelLabel,
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
