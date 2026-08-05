package com.example.data.memory.embedding

import com.example.data.ai.AIManager
import com.example.data.memory.model.EmbeddingVector
import kotlin.math.abs

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
                return EmbeddingVector(
                    providerId = provider.id,
                    modelName = "${provider.id}-embedding-v1",
                    vectorLength = floatArray.size,
                    values = floatArray
                )
            }
        }

        // 2. On-device Deterministic Semantic Hashing Vector Fallback (768-dim normalized embedding)
        val dimension = 768
        val vector = FloatArray(dimension)
        val words = text.lowercase().split(Regex("\\s+"))

        for ((index, word) in words.withIndex()) {
            val hash = word.hashCode()
            val dimIndex = abs(hash % dimension)
            val weight = 1.0f / (index + 1)
            vector[dimIndex] += weight
        }

        // Normalize L2 norm
        var sumSquares = 0.0f
        for (v in vector) {
            sumSquares += v * v
        }
        val norm = Math.sqrt(sumSquares.toDouble()).toFloat()
        if (norm > 0) {
            for (i in vector.indices) {
                vector[i] /= norm
            }
        }

        return EmbeddingVector(
            providerId = "local-hash",
            modelName = "wasti-semantic-hash-768",
            vectorLength = dimension,
            values = vector
        )
    }
}
