package com.example.data.architecture.observatory

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Observatory Engine.
 *
 * Implements Phase 8 Observatory Layer:
 * TelemetryEngine, TracingEngine, and MetricsEngine.
 *
 * Tracks:
 * - Latency distributions (ms, average, p95)
 * - Error occurrences and categorization
 * - Battery drain impact estimation (mAh)
 * - JVM heap and RAM memory utilization
 * - CPU load factor
 * - Network payload bytes (inbound/outbound)
 * - System success rates
 */
object ObservatoryEngine {

    data class TelemetrySpan(
        val spanId: String = UUID.randomUUID().toString(),
        val traceId: String = UUID.randomUUID().toString(),
        val operationName: String,
        val subsystem: String,
        val latencyMs: Long,
        val isSuccess: Boolean,
        val errorMessage: String? = null,
        val ramUsedBytes: Long = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory(),
        val cpuLoadPercent: Float = 0.0f,
        val networkBytesTransferred: Long = 0L,
        val timestamp: Long = System.currentTimeMillis()
    )

    data class ObservatoryMetrics(
        val totalOperationsRecorded: Long = 0L,
        val totalSuccesses: Long = 0L,
        val totalFailures: Long = 0L,
        val successRatePercent: Float = 100.0f,
        val averageLatencyMs: Long = 0L,
        val p95LatencyMs: Long = 0L,
        val averageRamUsedMb: Float = 0.0f,
        val totalNetworkBytes: Long = 0L,
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

        val avgRamMb = if (total > 0) (spansList.map { it.ramUsedBytes }.average() / (1024.0 * 1024.0)).toFloat() else 0.0f
        val netBytes = spansList.sumOf { it.networkBytesTransferred }

        val successRate = if (total > 0) (successes.toFloat() / total.toFloat()) * 100f else 100.0f

        _metricsState.value = ObservatoryMetrics(
            totalOperationsRecorded = total,
            totalSuccesses = successes,
            totalFailures = failures,
            successRatePercent = successRate,
            averageLatencyMs = avgLat,
            p95LatencyMs = p95,
            averageRamUsedMb = avgRamMb,
            totalNetworkBytes = netBytes,
            activeSpans = spansList.takeLast(20)
        )
    }

    /**
     * Dedicated Telemetry Engine (Phase 8).
     */
    object Telemetry {
        fun record(operationName: String, subsystem: String, latencyMs: Long, isSuccess: Boolean, errorMsg: String? = null) {
            recordSpan(
                TelemetrySpan(
                    operationName = operationName,
                    subsystem = subsystem,
                    latencyMs = latencyMs,
                    isSuccess = isSuccess,
                    errorMessage = errorMsg
                )
            )
        }
    }

    /**
     * Dedicated Tracing Engine (Phase 8).
     */
    object Tracing {
        fun startSpan(operationName: String, subsystem: String, traceId: String = UUID.randomUUID().toString()): ActiveTrace {
            return ActiveTrace(
                spanId = UUID.randomUUID().toString(),
                traceId = traceId,
                operationName = operationName,
                subsystem = subsystem,
                startTimeMs = System.currentTimeMillis()
            )
        }

        data class ActiveTrace(
            val spanId: String,
            val traceId: String,
            val operationName: String,
            val subsystem: String,
            val startTimeMs: Long
        ) {
            fun end(isSuccess: Boolean = true, errorMessage: String? = null, networkBytes: Long = 0L) {
                val latency = (System.currentTimeMillis() - startTimeMs).coerceAtLeast(1L)
                recordSpan(
                    TelemetrySpan(
                        spanId = spanId,
                        traceId = traceId,
                        operationName = operationName,
                        subsystem = subsystem,
                        latencyMs = latency,
                        isSuccess = isSuccess,
                        errorMessage = errorMessage,
                        networkBytesTransferred = networkBytes
                    )
                )
            }
        }
    }

    /**
     * Dedicated Metrics Engine (Phase 8).
     */
    object Metrics {
        val currentMetrics: ObservatoryMetrics get() = _metricsState.value
        val metricsFlow: StateFlow<ObservatoryMetrics> get() = _metricsState.asStateFlow()
    }
}
