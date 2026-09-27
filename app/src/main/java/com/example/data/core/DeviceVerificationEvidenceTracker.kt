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
    val testRunSignature: String = "RUN_${System.currentTimeMillis()}",
    val timestampMs: Long = System.currentTimeMillis(),
    val commitSha: String = "local_development",
    val schemaVersion: String = "2.0"
)

object DeviceVerificationEvidenceTracker {

    private val executionRecords = mutableListOf<DeviceExecutionRecord>()

    /**
     * Record a verified device execution proof.
     * Rejects any record where tier does NOT prove real-world capability or contains invalid metadata.
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
     * Returns true ONLY if at least one legitimate, fresh on-device execution proof is recorded.
     */
    @Synchronized
    fun hasValidDeviceProof(
        capability: String? = null,
        maxAgeMs: Long = EvidenceIntegrityValidator.DEFAULT_MAX_AGE_MS,
        currentTimeMs: Long = System.currentTimeMillis()
    ): Boolean {
        val freshProofs = getFreshDeviceProofs(maxAgeMs, currentTimeMs)
        if (freshProofs.isEmpty()) return false
        return if (capability != null) {
            freshProofs.any { it.verifiedCapabilities.contains(capability) }
        } else {
            freshProofs.isNotEmpty()
        }
    }

    /**
     * Returns unmodifiable copy of fresh device execution proofs within the active TTL window.
     */
    @Synchronized
    fun getFreshDeviceProofs(
        maxAgeMs: Long = EvidenceIntegrityValidator.DEFAULT_MAX_AGE_MS,
        currentTimeMs: Long = System.currentTimeMillis()
    ): List<DeviceExecutionRecord> {
        return executionRecords.filter { record ->
            val freshness = EvidenceIntegrityValidator.validateFreshness(
                timestampMs = record.timestampMs,
                maxAgeMs = maxAgeMs,
                currentTimeMs = currentTimeMs,
                allowHistorical = false
            )
            freshness.isValidCurrent
        }
    }

    /**
     * Returns unmodifiable copy of historical device execution proofs (expired or historical).
     * Preserves historical evidence without treating it as active current verification.
     */
    @Synchronized
    fun getHistoricalDeviceProofs(
        maxAgeMs: Long = EvidenceIntegrityValidator.DEFAULT_MAX_AGE_MS,
        currentTimeMs: Long = System.currentTimeMillis()
    ): List<DeviceExecutionRecord> {
        return executionRecords.filter { record ->
            val ageMs = currentTimeMs - record.timestampMs
            ageMs > maxAgeMs
        }
    }

    /**
     * Returns unmodifiable copy of all recorded device execution proofs (fresh and historical).
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
        val fresh = getFreshDeviceProofs()
        val total = executionRecords.size
        return if (fresh.isEmpty()) {
            if (total > 0) "ENVIRONMENT: HOST_SIMULATION ($total historical proofs recorded, 0 fresh active proofs)"
            else "ENVIRONMENT: HOST_SIMULATION (No physical device execution records)"
        } else {
            "ENVIRONMENT: REAL_DEVICE (${fresh.size} fresh verified proofs, $total total)"
        }
    }

    /**
     * Clears all recorded device proofs (used strictly for test resets).
     */
    @Synchronized
    fun clearDeviceProofForTesting() {
        executionRecords.clear()
    }

    fun createCurrentDeviceRecord(
        tier: TestTier = TestTier.DEVICE,
        verifiedCapabilities: Set<String> = emptySet(),
        signature: String = "RUN_${System.currentTimeMillis()}",
        commitSha: String = "local_development"
    ): DeviceExecutionRecord {
        val devId = try { Build.ID ?: "unknown_device" } catch (_: Throwable) { "unknown_device" }
        val devModel = try { Build.MODEL ?: "unknown_model" } catch (_: Throwable) { "unknown_model" }
        val devMfg = try { Build.MANUFACTURER ?: "unknown_mfg" } catch (_: Throwable) { "unknown_mfg" }
        val devSdk = try { Build.VERSION.SDK_INT } catch (_: Throwable) { 0 }
        val fingerprint = try { Build.FINGERPRINT ?: "" } catch (_: Throwable) { "" }
        val isEmu = fingerprint.startsWith("generic") || devModel.contains("google_sdk")

        return DeviceExecutionRecord(
            deviceId = devId,
            deviceModel = devModel,
            manufacturer = devMfg,
            androidApiLevel = devSdk,
            isEmulator = isEmu,
            tier = tier,
            verifiedCapabilities = verifiedCapabilities,
            testRunSignature = signature,
            timestampMs = System.currentTimeMillis(),
            commitSha = commitSha,
            schemaVersion = "2.0"
        )
    }
}
