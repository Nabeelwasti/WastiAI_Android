package com.example.data.memory.storage

import com.example.data.memory.model.EmbeddingVector
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt

data class IndexedVector(
    val id: String,
    val vector: EmbeddingVector,
    val metadataJson: String = "{}"
)

class VectorIndex {
    private val index = ConcurrentHashMap<String, IndexedVector>()

    fun indexVector(id: String, vector: EmbeddingVector, metadataJson: String = "{}") {
        index[id] = IndexedVector(id, vector, metadataJson)
    }

    fun removeVector(id: String) {
        index.remove(id)
    }

    fun getVector(id: String): IndexedVector? = index[id]

    fun size(): Int = index.size

    fun searchNearest(
        queryVector: EmbeddingVector,
        topK: Int = 10,
        minSimilarity: Float = 0.0f
    ): List<Pair<IndexedVector, Float>> {
        val results = mutableListOf<Pair<IndexedVector, Float>>()

        index.values.forEach { indexed ->
            // Cosine similarity
            val similarity = calculateCosineSimilarity(queryVector.values, indexed.vector.values)
            if (similarity >= minSimilarity) {
                results.add(Pair(indexed, similarity))
            }
        }

        return results.sortedByDescending { it.second }.take(topK)
    }

    private fun calculateCosineSimilarity(v1: FloatArray, v2: FloatArray): Float {
        val minDim = minOf(v1.size, v2.size)
        if (minDim == 0) return 0.0f

        var dotProduct = 0.0f
        var normA = 0.0f
        var normB = 0.0f

        for (i in 0 until minDim) {
            val a = v1[i]
            val b = v2[i]
            dotProduct += a * b
            normA += a * a
            normB += b * b
        }

        val denominator = sqrt(normA.toDouble()) * sqrt(normB.toDouble())
        return if (denominator > 0) (dotProduct / denominator).toFloat() else 0.0f
    }

    fun clear() {
        index.clear()
    }
}
