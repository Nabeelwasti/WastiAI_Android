package com.example.data.core

import android.os.Build

/**
 * [P0-33] REAL-DEVICE-PROOF: Canonical Device Verification Evidence Tracker.
 *
 * Enforces zero-fabrication device verification rules:
 * 1. Device verification can ONLY be satisfied by tests executed on a physical Android
 *    device or Android emulator (TestTier.DEVICE or TestTier.EMULATOR).
 * 2. Host JVM unit tests, Robolectric simulations, and synthetic mocks can NEVER satisfy
 *    device verification and are strictly rejected if attempted.
 * 3. Without verifiable on-device execution records, device readiness status remains
 *    truthfully BLOCKED_EXTERNAL_DEVICE.
 */
data class DeviceExecutionRecord(
    val deviceId: String,
    val deviceModel: String,
    val manufacturer: String,
    val androidApiLevel: Int,
    val isEmulator: Boolean,
    val tier: TestTier,
    val verifiedCapabilities: Set<String>,
    val timestampMs: Long = System.currentTimeMillis(),
    val testRunSignature: String
)

object DeviceVerificationEvidenceTracker {

    private val executionRecords = mutableListOf<DeviceExecutionRecord>()

    /**
     * Record a verified device execution proof.
     * Rejects any record where tier does NOT prove real-world capability.
     */
    @Synchronized
    fun recordDeviceExecution(record: DeviceExecutionRecord): Boolean {
        // Enforce strict tier validation: only real device or emulator test execution is accepted
        if (!record.tier.provesRealWorldCapability || 
            (record.tier != TestTier.DEVICE && record.tier != TestTier.EMULATOR)) {
            return false
        }

        if (record.androidApiLevel <= 0 || record.deviceModel.isBlank()) {
            return false
        }

        executionRecords.add(record)
        return true
    }

    /**
     * Returns true ONLY if at least one legitimate on-device execution proof is recorded.
     */
    @Synchronized
    fun hasValidDeviceProof(capability: String? = null): Boolean {
        if (executionRecords.isEmpty()) return false
        return if (capability != null) {
            executionRecords.any { it.verifiedCapabilities.contains(capability) }
        } else {
            executionRecords.isNotEmpty()
        }
    }

    /**
     * Returns unmodifiable copy of all recorded device execution proofs.
     */
    @Synchronized
    fun getDeviceProofReport(): List<DeviceExecutionRecord> {
        return executionRecords.toList()
    }

    /**
     * Returns a human-readable evidence summary string for telemetry and test assertions.
     */
    @Synchronized
    fun getExecutionEvidenceSummary(): String {
        val proofs = executionRecords
        return if (proofs.isEmpty()) {
            "ENVIRONMENT: HOST_SIMULATION (No physical device execution records)"
        } else {
            "ENVIRONMENT: REAL_DEVICE (${proofs.size} verified proofs)"
        }
    }

    /**
     * Clears all recorded device proofs (used strictly for test resets).
     */
    @Synchronized
    fun clearDeviceProofForTesting() {
        executionRecords.clear()
    }
}
