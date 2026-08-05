package com.example.data.evaluation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

data class ProviderQualityScore(
    val providerId: String,
    val providerName: String,
    val accuracyScorePercentage: Float,
    val averageLatencyMs: Double,
    val costEfficiencyRating: Float, // 0.0 to 1.0 (1.0 = highly cost efficient)
    val hallucinationIndexPercentage: Float, // lower is better
    val compositeQualityIndex: Float, // Overall score 0.0 to 100.0
    val totalEvaluationsCount: Int
)

data class EvaluationRecord(
    val id: String,
    val providerId: String,
    val latencyMs: Long,
    val tokenCount: Int,
    val costUsd: Double,
    val userRating: Int? = null, // 1 to 5 stars
    val wasSuccessful: Boolean,
    val timestampMs: Long = System.currentTimeMillis()
)

object AIEvaluationEngine {

    private val providerRecordsMap = ConcurrentHashMap<String, MutableList<EvaluationRecord>>()

    private val _qualityScoresFlow = MutableStateFlow<List<ProviderQualityScore>>(emptyList())
    val qualityScoresFlow: StateFlow<List<ProviderQualityScore>> = _qualityScoresFlow.asStateFlow()

    fun recordEvaluation(
        providerId: String,
        latencyMs: Long,
        tokenCount: Int,
        costUsd: Double,
        wasSuccessful: Boolean,
        userRating: Int? = null
    ) {
        val list = providerRecordsMap.getOrPut(providerId) { mutableListOf() }
        synchronized(list) {
            list.add(
                EvaluationRecord(
                    id = "eval_${System.currentTimeMillis()}_${list.size}",
                    providerId = providerId,
                    latencyMs = latencyMs,
                    tokenCount = tokenCount,
                    costUsd = costUsd,
                    wasSuccessful = wasSuccessful,
                    userRating = userRating
                )
            )
            // Keep last 100 records per provider for sliding window statistics
            if (list.size > 100) {
                list.removeAt(0)
            }
        }
        recalculateQualityScores()
    }

    fun getProviderQualityScore(providerId: String): ProviderQualityScore? {
        return _qualityScoresFlow.value.find { it.providerId == providerId }
    }

    private fun recalculateQualityScores() {
        val scoresList = mutableListOf<ProviderQualityScore>()

        providerRecordsMap.forEach { (providerId, records) ->
            val snapshot = synchronized(records) { records.toList() }
            if (snapshot.isNotEmpty()) {
                val totalCount = snapshot.size
                val successCount = snapshot.count { it.wasSuccessful }
                val accuracyPct = (successCount.toFloat() / totalCount.toFloat()) * 100.0f

                val avgLatency = snapshot.map { it.latencyMs }.average()
                val avgCost = snapshot.map { it.costUsd }.average()

                val costRating = when {
                    avgCost <= 0.0001 -> 1.0f
                    avgCost <= 0.001 -> 0.8f
                    avgCost <= 0.01 -> 0.5f
                    else -> 0.2f
                }

                // Hallucination estimate inversely correlated with success rate and user ratings
                val ratedRecords = snapshot.filter { it.userRating != null }
                val avgUserRating = if (ratedRecords.isNotEmpty()) ratedRecords.map { it.userRating!! }.average() else 4.0
                val hallucinationPct = ((5.0 - avgUserRating) / 5.0 * 100.0).toFloat().coerceIn(0.0f, 100.0f)

                // Composite Index Calculation (Accuracy 40%, Speed 30%, Cost 20%, Satisfaction 10%)
                val latencyRating = when {
                    avgLatency <= 500 -> 100.0f
                    avgLatency <= 1500 -> 80.0f
                    avgLatency <= 3000 -> 60.0f
                    else -> 40.0f
                }

                val compositeScore = (accuracyPct * 0.40f) +
                        (latencyRating * 0.30f) +
                        (costRating * 100.0f * 0.20f) +
                        ((avgUserRating.toFloat() / 5.0f * 100.0f) * 0.10f)

                scoresList.add(
                    ProviderQualityScore(
                        providerId = providerId,
                        providerName = providerId.replaceFirstChar { it.uppercase() },
                        accuracyScorePercentage = accuracyPct,
                        averageLatencyMs = avgLatency,
                        costEfficiencyRating = costRating,
                        hallucinationIndexPercentage = hallucinationPct,
                        compositeQualityIndex = compositeScore,
                        totalEvaluationsCount = totalCount
                    )
                )
            }
        }

        _qualityScoresFlow.value = scoresList
    }
}
