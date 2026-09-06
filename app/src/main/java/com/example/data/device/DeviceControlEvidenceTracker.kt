package com.example.data.device

import java.util.concurrent.ConcurrentLinkedQueue

/**
 * [P0-37] Verifiable execution evidence tracker for accessibility and device control actions.
 * Maintains an immutable, observable ledger of physical and IPC device actions executed by Wasti AI OS.
 */
data class DeviceActionEvidence(
    val actionType: String,
    val target: String,
    val isSuccess: Boolean,
    val details: String,
    val timestamp: Long = System.currentTimeMillis()
)

object DeviceControlEvidenceTracker {
    private val evidenceHistory = ConcurrentLinkedQueue<DeviceActionEvidence>()

    fun recordAction(
        actionType: String,
        target: String,
        isSuccess: Boolean,
        details: String
    ): DeviceActionEvidence {
        val evidence = DeviceActionEvidence(
            actionType = actionType,
            target = target,
            isSuccess = isSuccess,
            details = details
        )
        evidenceHistory.add(evidence)
        while (evidenceHistory.size > 100) {
            evidenceHistory.poll()
        }
        return evidence
    }

    fun getLastEvidence(): DeviceActionEvidence? {
        return evidenceHistory.lastOrNull()
    }

    fun getEvidenceHistory(): List<DeviceActionEvidence> {
        return evidenceHistory.toList()
    }

    fun clearEvidenceForTesting() {
        evidenceHistory.clear()
    }
}
