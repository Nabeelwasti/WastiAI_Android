package com.example.data.memory.embedding

import com.example.data.ai.AIManager
import com.example.data.ai.model.ProviderCapability
import com.example.data.ai.runtime.EmbeddingEngineType
import com.example.data.ai.runtime.WastiEmbeddingRuntime
import com.example.data.memory.model.EmbeddingVector

interface EmbeddingService {
    suspend fun generateEmbedding(
        text: String,
        preferredProviderId: String? = null
    ): EmbeddingVector
}

class DefaultEmbeddingService : EmbeddingService {

    override suspend fun generateEmbedding(
        text: String,
        preferredProviderId: String?
    ): EmbeddingVector {
        val targetProvider = preferredProviderId ?: "gemini"
        
        // 1. Try requesting embedding from AI Provider via AIManager
        val provider = AIManager.capabilityRegistry.getProvider(targetProvider)
        if (provider != null && provider.isAvailable()) {
            val floatArray = provider.embeddings(text)
            if (floatArray.isNotEmpty()) {
                val isNeural = provider.capabilities.contains(ProviderCapability.EMBEDDINGS)
                val engineType = if (isNeural) EmbeddingEngineType.CLOUD_API_NEURAL.name else EmbeddingEngineType.DETERMINISTIC_MATHEMATICAL_FALLBACK.name
                val modelName = if (isNeural) "${provider.id}-embedding-v1" else "${provider.id}-embedding-fallback [NON_NEURAL]"
                return EmbeddingVector(
                    providerId = provider.id,
                    modelName = modelName,
                    vectorLength = floatArray.size,
                    values = floatArray,
                    isNeuralEmbedding = isNeural,
                    engineType = engineType
                )
            }
        }

        // 2. On-device Deterministic Mathematical Projection Fallback (384-dim normalized trigonometric subspace embedding)
        val detailed = WastiEmbeddingRuntime.encodeDetailed(text)

        return EmbeddingVector(
            providerId = "wasti-deterministic",
            modelName = detailed.modelIdentifier,
            vectorLength = detailed.vector.size,
            values = detailed.vector,
            isNeuralEmbedding = false,
            engineType = detailed.engineType.name
        )
    }
}
