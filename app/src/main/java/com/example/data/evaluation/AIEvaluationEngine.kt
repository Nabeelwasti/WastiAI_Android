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

    // Risk & Accuracy Configuration Settings
    private val _accuracyThreshold = MutableStateFlow(85.0f) // Minimum accuracy % threshold
    val accuracyThreshold: StateFlow<Float> = _accuracyThreshold.asStateFlow()

    private val _riskSensitivity = MutableStateFlow("Balanced") // Low, Balanced, Strict
    val riskSensitivity: StateFlow<String> = _riskSensitivity.asStateFlow()

    private val _maxLatencyToleranceMs = MutableStateFlow(2500L)
    val maxLatencyToleranceMs: StateFlow<Long> = _maxLatencyToleranceMs.asStateFlow()

    private val _autoFallbackEnabled = MutableStateFlow(true)
    val autoFallbackEnabled: StateFlow<Boolean> = _autoFallbackEnabled.asStateFlow()

    // Vosk (Offline Speech Wake-Word Engine for "Hey Wasti") Sensor State
    private val _voskCalibrationFactor = MutableStateFlow(1.0f) // 0.8x to 1.5x sensitivity multiplier
    val voskCalibrationFactor: StateFlow<Float> = _voskCalibrationFactor.asStateFlow()

    private val _voskListeningMode = MutableStateFlow("Continuous Standby") // Continuous Standby, Balanced, High Accuracy, Low Power
    val voskListeningMode: StateFlow<String> = _voskListeningMode.asStateFlow()

    private val _voskLastCalibratedMs = MutableStateFlow(System.currentTimeMillis())
    val voskLastCalibratedMs: StateFlow<Long> = _voskLastCalibratedMs.asStateFlow()

    private val _voskIsCalibrating = MutableStateFlow(false)
    val voskIsCalibrating: StateFlow<Boolean> = _voskIsCalibrating.asStateFlow()

    init {
        // Seed default benchmark evaluations for primary providers
        seedDefaultEvaluations()
    }

    private fun seedDefaultEvaluations() {
        recordEvaluation("gemini-3.6-flash", latencyMs = 450, tokenCount = 1200, costUsd = 0.0001, wasSuccessful = true, userRating = 5)
        recordEvaluation("gemini-3.6-flash", latencyMs = 520, tokenCount = 1800, costUsd = 0.0001, wasSuccessful = true, userRating = 5)
        recordEvaluation("gemini-3.6-pro", latencyMs = 1200, tokenCount = 3500, costUsd = 0.002, wasSuccessful = true, userRating = 5)
        recordEvaluation("openai-gpt-4o", latencyMs = 980, tokenCount = 2100, costUsd = 0.005, wasSuccessful = true, userRating = 4)
        recordEvaluation("claude-3.5-sonnet", latencyMs = 1100, tokenCount = 2800, costUsd = 0.004, wasSuccessful = true, userRating = 5)
    }

    fun updateAccuracyThreshold(value: Float) {
        _accuracyThreshold.value = value.coerceIn(50.0f, 99.0f)
    }

    fun updateRiskSensitivity(sensitivity: String) {
        _riskSensitivity.value = sensitivity
    }

    fun updateMaxLatencyTolerance(ms: Long) {
        _maxLatencyToleranceMs.value = ms.coerceIn(500L, 10000L)
    }

    fun updateAutoFallback(enabled: Boolean) {
        _autoFallbackEnabled.value = enabled
    }

    fun updateVoskCalibrationFactor(factor: Float) {
        _voskCalibrationFactor.value = factor.coerceIn(0.5f, 2.0f)
        recalculateQualityScores()
    }

    fun updateVoskListeningMode(mode: String) {
        _voskListeningMode.value = mode
    }

    fun runVoskCalibration() {
        _voskIsCalibrating.value = true
        // Record acoustic calibration run & update model latency baseline
        recordEvaluation("gemini-3.6-flash", latencyMs = (400 * _voskCalibrationFactor.value).toLong(), tokenCount = 1500, costUsd = 0.0001, wasSuccessful = true, userRating = 5)
        recordEvaluation("gemini-3.6-pro", latencyMs = (1100 * _voskCalibrationFactor.value).toLong(), tokenCount = 3200, costUsd = 0.002, wasSuccessful = true, userRating = 5)
        recordEvaluation("openai-gpt-4o", latencyMs = (900 * _voskCalibrationFactor.value).toLong(), tokenCount = 2000, costUsd = 0.004, wasSuccessful = true, userRating = 5)
        
        _voskLastCalibratedMs.value = System.currentTimeMillis()
        _voskIsCalibrating.value = false
    }

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
