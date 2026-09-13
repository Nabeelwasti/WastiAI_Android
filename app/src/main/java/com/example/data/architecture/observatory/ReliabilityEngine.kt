package com.example.data.architecture.observatory

import com.example.data.core.WastiSystemResilienceGovernor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Reliability & Resilience Intelligence Engine.
 *
 * Implements Phase 8 Reliability Layer:
 * - HealthScoreEngine (Blended 0 to 100%)
 * - FailurePredictionEngine & Anomaly Warning (Tracks why failures happen, how often)
 * - RecoveryAnalytics (Recovery success rate, self-healing metrics)
 */
object ReliabilityEngine {

    enum class HealthVerdict {
        EXCELLENT,
        HEALTHY,
        DEGRADED,
        CRITICAL
    }

    data class FailureEvent(
        val subsystemId: String,
        val reason: String,
        val failureCategory: String, // NETWORK, MEMORY_OOM, TIMEOUT, PERMISSION, MALFORMED_DATA
        val timestamp: Long = System.currentTimeMillis()
    )

    data class ReliabilityStatus(
        val overallHealthScorePercent: Float = 100.0f,
        val verdict: HealthVerdict = HealthVerdict.EXCELLENT,
        val memoryHealthScore: Float = 100.0f,
        val operationsShielded: Long = 0L,
        val recoveredFailures: Long = 0L,
        val recoverySuccessRatePercent: Float = 100.0f,
        val isFailureImminent: Boolean = false,
        val failureWarningReason: String? = null,
        val recentFailureEvents: List<FailureEvent> = emptyList()
    )

    private val _failureEvents = mutableListOf<FailureEvent>()
    private var recoveredFailureCount = 0L
    private var totalShieldedOperations = 0L

    private val _reliabilityState = MutableStateFlow(ReliabilityStatus())
    val reliabilityState: StateFlow<ReliabilityStatus> = _reliabilityState.asStateFlow()

    @Synchronized
    fun recordFailureEvent(subsystemId: String, reason: String, category: String) {
        _failureEvents.add(FailureEvent(subsystemId, reason, category))
        while (_failureEvents.size > 100) {
            _failureEvents.removeAt(0)
        }
        evaluateReliability()
    }

    @Synchronized
    fun recordSuccessfulRecovery() {
        recoveredFailureCount++
        totalShieldedOperations++
        evaluateReliability()
    }

    fun evaluateReliability(): ReliabilityStatus {
        val telemetry = WastiSystemResilienceGovernor.assessSystemHealth()
        val observatory = ObservatoryEngine.metricsState.value

        // Compute memory component
        val memScore = (100.0f - telemetry.memoryUsagePercent).coerceIn(0.0f, 100.0f)
        val successScore = observatory.successRatePercent

        // Blended health score
        val overallScore = (memScore * 0.4f) + (successScore * 0.6f)

        val isImminent = telemetry.loadState == WastiSystemResilienceGovernor.SystemLoadState.CRITICAL_PRESSURE ||
                telemetry.memoryUsagePercent > 88.0f ||
                observatory.successRatePercent < 60.0f

        val warningReason = when {
            telemetry.memoryUsagePercent > 88.0f -> "Extreme memory pressure (>88% JVM heap). Proactive trim active."
            observatory.successRatePercent < 60.0f -> "Elevated failure rate detected across recent operations."
            else -> null
        }

        val verdict = when {
            overallScore >= 90.0f -> HealthVerdict.EXCELLENT
            overallScore >= 75.0f -> HealthVerdict.HEALTHY
            overallScore >= 50.0f -> HealthVerdict.DEGRADED
            else -> HealthVerdict.CRITICAL
        }

        val recoveryRate = if (_failureEvents.isNotEmpty()) {
            (recoveredFailureCount.toFloat() / _failureEvents.size.toFloat()).coerceIn(0.0f, 1.0f) * 100.0f
        } else {
            100.0f
        }

        val updated = ReliabilityStatus(
            overallHealthScorePercent = overallScore,
            verdict = verdict,
            memoryHealthScore = memScore,
            operationsShielded = totalShieldedOperations,
            recoveredFailures = recoveredFailureCount,
            recoverySuccessRatePercent = recoveryRate,
            isFailureImminent = isImminent,
            failureWarningReason = warningReason,
            recentFailureEvents = _failureEvents.takeLast(10)
        )

        _reliabilityState.value = updated
        return updated
    }

    /**
     * Dedicated Health Score Engine (Phase 8).
     */
    object HealthScoreEngine {
        fun getHealthScore(): Float = evaluateReliability().overallHealthScorePercent
        fun getVerdict(): HealthVerdict = evaluateReliability().verdict
    }

    /**
     * Dedicated Failure Prediction Engine (Phase 8).
     */
    object FailurePredictionEngine {
        fun isFailureImminent(): Boolean = evaluateReliability().isFailureImminent
        fun getWarningReason(): String? = evaluateReliability().failureWarningReason
        fun getRecentFailures(): List<FailureEvent> = _failureEvents.toList()
    }

    /**
     * Dedicated Recovery Analytics Engine (Phase 8).
     */
    object RecoveryAnalytics {
        val totalRecovered: Long get() = recoveredFailureCount
        val shieldedOps: Long get() = totalShieldedOperations
        fun getSuccessRate(): Float = evaluateReliability().recoverySuccessRatePercent
    }
}
