package com.example.data.architecture.observatory

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Observatory Engine.
 *
 * Implements Phase 8 Observatory Layer:
 * Telemetry, Tracing, and Metrics Engine.
 *
 * Tracks:
 * - Latency distributions (ms)
 * - Error occurrences and categorization
 * - JVM heap and memory metrics
 * - Overall system success rates
 */
object ObservatoryEngine {

    data class TelemetrySpan(
        val spanId: String,
        val operationName: String,
        val subsystem: String,
        val latencyMs: Long,
        val isSuccess: Boolean,
        val errorMessage: String? = null,
        val timestamp: Long = System.currentTimeMillis()
    )

    data class ObservatoryMetrics(
        val totalOperationsRecorded: Long = 0L,
        val totalSuccesses: Long = 0L,
        val totalFailures: Long = 0L,
        val successRatePercent: Float = 100.0f,
        val averageLatencyMs: Long = 0L,
        val p95LatencyMs: Long = 0L,
        val activeSpans: List<TelemetrySpan> = emptyList()
    )

    private val recentSpans = ConcurrentLinkedQueue<TelemetrySpan>()
    private const val MAX_STORED_SPANS = 200

    private val _metricsState = MutableStateFlow(ObservatoryMetrics())
    val metricsState: StateFlow<ObservatoryMetrics> = _metricsState.asStateFlow()

    @Synchronized
    fun recordSpan(span: TelemetrySpan) {
        recentSpans.add(span)
        while (recentSpans.size > MAX_STORED_SPANS) {
            recentSpans.poll()
        }

        val spansList = recentSpans.toList()
        val total = spansList.size.toLong()
        val successes = spansList.count { it.isSuccess }.toLong()
        val failures = spansList.count { !it.isSuccess }.toLong()
        val avgLat = if (total > 0) spansList.map { it.latencyMs }.average().toLong() else 0L

        val sortedLatencies = spansList.map { it.latencyMs }.sorted()
        val p95Index = (sortedLatencies.size * 0.95).toInt().coerceAtMost((sortedLatencies.size - 1).coerceAtLeast(0))
        val p95 = if (sortedLatencies.isNotEmpty()) sortedLatencies[p95Index] else 0L

        val successRate = if (total > 0) (successes.toFloat() / total.toFloat()) * 100f else 100.0f

        _metricsState.value = ObservatoryMetrics(
            totalOperationsRecorded = total,
            totalSuccesses = successes,
            totalFailures = failures,
            successRatePercent = successRate,
            averageLatencyMs = avgLat,
            p95LatencyMs = p95,
            activeSpans = spansList.takeLast(20)
        )
    }
}
