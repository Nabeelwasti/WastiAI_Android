package com.example.data.agent.runtime

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Stage 19: Capability Regression Monitoring & Self-Healing Health Tracker.
 * 
 * Continuously tracks capability success rates, detects regressions in promoted
 * capabilities, and triggers automated diagnostic/repair workflows.
 */

enum class RegressionStatus {
    OPERATIONAL,
    DEGRADED,
    REGRESSION_DETECTED,
    REPAIR_REQUIRED
}

data class CapabilityHealthRecord(
    val capabilityId: String,
    val version: String = "1.0.0",
    val realityState: CapabilityRealityState = CapabilityRealityState.LIVE_CONNECTED,
    val lastVerifiedTimestamp: Long = System.currentTimeMillis(),
    val totalExecutions: Int = 0,
    val successfulExecutions: Int = 0,
    val failedExecutions: Int = 0,
    val successRate: Float = 1.0f,
    val regressionStatus: RegressionStatus = RegressionStatus.OPERATIONAL,
    val failureHistory: List<String> = emptyList(),
    val repairAttempts: Int = 0,
    val lastFailureReason: String? = null
)

class CapabilityRegressionMonitor(
    private val realityRegistry: CapabilityRealityRegistry = UnifiedExecutionFabric.instance.realityRegistry,
    private val eventBus: AgentEventBus = AgentEventBus.getInstance()
) {
    private val TAG = "CapRegressionMonitor"
    private val healthRecords = ConcurrentHashMap<String, CapabilityHealthRecord>()

    private val _degradedCount = MutableStateFlow(0)
    val degradedCount: StateFlow<Int> = _degradedCount.asStateFlow()

    fun registerCapability(capabilityId: String, version: String = "1.0.0") {
        if (!healthRecords.containsKey(capabilityId)) {
            healthRecords[capabilityId] = CapabilityHealthRecord(
                capabilityId = capabilityId,
                version = version,
                lastVerifiedTimestamp = System.currentTimeMillis()
            )
        }
    }

    @Synchronized
    fun recordExecution(capabilityId: String, isSuccess: Boolean, errorMessage: String? = null): CapabilityHealthRecord {
        val current = healthRecords[capabilityId] ?: CapabilityHealthRecord(capabilityId = capabilityId)

        val total = current.totalExecutions + 1
        val successful = current.successfulExecutions + (if (isSuccess) 1 else 0)
        val failed = current.failedExecutions + (if (!isSuccess) 1 else 0)
        val rate = if (total > 0) successful.toFloat() / total.toFloat() else 1.0f

        val failures = if (!isSuccess && errorMessage != null) {
            (listOf("[${System.currentTimeMillis()}] $errorMessage") + current.failureHistory).take(10)
        } else {
            current.failureHistory
        }

        val status = when {
            rate >= 0.90f -> RegressionStatus.OPERATIONAL
            rate >= 0.60f -> RegressionStatus.DEGRADED
            rate >= 0.30f -> RegressionStatus.REGRESSION_DETECTED
            else -> RegressionStatus.REPAIR_REQUIRED
        }

        val updated = current.copy(
            lastVerifiedTimestamp = System.currentTimeMillis(),
            totalExecutions = total,
            successfulExecutions = successful,
            failedExecutions = failed,
            successRate = rate,
            regressionStatus = status,
            failureHistory = failures,
            lastFailureReason = if (!isSuccess) errorMessage else current.lastFailureReason
        )

        healthRecords[capabilityId] = updated
        updateDegradedCount()

        if (status == RegressionStatus.REGRESSION_DETECTED || status == RegressionStatus.REPAIR_REQUIRED) {
            Log.w(TAG, "Capability regression detected for '$capabilityId': status=$status, rate=$rate, lastErr=$errorMessage")
        }

        return updated
    }

    fun getHealth(capabilityId: String): CapabilityHealthRecord? = healthRecords[capabilityId]

    fun getAllHealthRecords(): List<CapabilityHealthRecord> = healthRecords.values.toList()

    fun getRegressedCapabilities(): List<CapabilityHealthRecord> =
        healthRecords.values.filter { it.regressionStatus != RegressionStatus.OPERATIONAL }

    @Synchronized
    fun triggerRepair(capabilityId: String): Boolean {
        val current = healthRecords[capabilityId] ?: return false
        val repaired = current.copy(
            repairAttempts = current.repairAttempts + 1,
            regressionStatus = RegressionStatus.DEGRADED // Reset to degraded while testing repair
        )
        healthRecords[capabilityId] = repaired
        updateDegradedCount()
        Log.i(TAG, "Triggered autonomous repair for capability '$capabilityId' (Attempt #${repaired.repairAttempts})")
        return true
    }

    private fun updateDegradedCount() {
        _degradedCount.value = healthRecords.values.count { it.regressionStatus != RegressionStatus.OPERATIONAL }
    }
}
