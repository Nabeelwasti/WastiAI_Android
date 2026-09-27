package com.example.data.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Deterministic unit test suite for Evidence Freshness, Integrity, and Provenance.
 * Verifies that stale, mismatched, forged, or ungrounded evidence is truthfully rejected,
 * while fresh authentic verification is validated.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class EvidenceFreshnessAndIntegrityTest {

    @Before
    fun setUp() {
        DeviceVerificationEvidenceTracker.clearDeviceProofForTesting()
    }

    @Test
    fun testFreshValidEvidencePassesValidation() {
        val now = System.currentTimeMillis()
        val commit = "a9e91d2abc1234567890abcdef"
        val artifactHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        val payloadHash = EvidenceIntegrityValidator.sha256("canonical_payload_test")

        val result = EvidenceIntegrityValidator.validateEvidenceRecord(
            schemaVersion = "2.0",
            commitSha = commit,
            artifactHash = artifactHash,
            timestampMs = now - 5000L, // 5 seconds old
            overallStatus = "VERIFIED",
            evidenceHash = payloadHash,
            expectedPayloadHash = payloadHash,
            expectedCommit = commit,
            expectedArtifactHash = artifactHash,
            currentTimeMs = now
        )

        assertEquals(EvidenceValidationState.VALID_CURRENT, result.state)
        assertTrue(result.isValidCurrent)
        assertTrue(result.state.isAcceptedAsCurrent)
    }

    @Test
    fun testStaleEvidenceIsRejected() {
        val now = System.currentTimeMillis()
        val commit = "commit_12345"
        val artifactHash = "hash_12345"
        val payloadHash = EvidenceIntegrityValidator.sha256("payload")

        // Evidence is 25 hours old (maxAge is 24 hours)
        val timestamp25HoursAgo = now - (25 * 3600 * 1000L)

        val result = EvidenceIntegrityValidator.validateEvidenceRecord(
            schemaVersion = "2.0",
            commitSha = commit,
            artifactHash = artifactHash,
            timestampMs = timestamp25HoursAgo,
            overallStatus = "VERIFIED",
            evidenceHash = payloadHash,
            expectedPayloadHash = payloadHash,
            expectedCommit = commit,
            expectedArtifactHash = artifactHash,
            maxAgeMs = EvidenceIntegrityValidator.DEFAULT_MAX_AGE_MS,
            allowHistorical = false,
            currentTimeMs = now
        )

        assertEquals(EvidenceValidationState.STALE, result.state)
        assertFalse(result.isValidCurrent)
        assertFalse(result.state.isAcceptedAsCurrent)
    }

    @Test
    fun testFutureTimestampBeyondClockSkewToleranceIsRejected() {
        val now = System.currentTimeMillis()
        val commit = "commit_12345"
        val artifactHash = "hash_12345"
        val payloadHash = EvidenceIntegrityValidator.sha256("payload")

        // Evidence claims to be from 10 minutes in the future (skew tolerance is 5 min)
        val futureTimestamp = now + (10 * 60 * 1000L)

        val result = EvidenceIntegrityValidator.validateEvidenceRecord(
            schemaVersion = "2.0",
            commitSha = commit,
            artifactHash = artifactHash,
            timestampMs = futureTimestamp,
            overallStatus = "VERIFIED",
            evidenceHash = payloadHash,
            expectedPayloadHash = payloadHash,
            expectedCommit = commit,
            expectedArtifactHash = artifactHash,
            currentTimeMs = now
        )

        assertEquals(EvidenceValidationState.INVALID_TIMESTAMP, result.state)
        assertFalse(result.isValidCurrent)
    }

    @Test
    fun testCommitShaMismatchIsRejected() {
        val now = System.currentTimeMillis()
        val evidenceCommit = "1111111111111111111111111111111111111111"
        val targetCommit = "2222222222222222222222222222222222222222"
        val artifactHash = "hash_12345"
        val payloadHash = EvidenceIntegrityValidator.sha256("payload")

        val result = EvidenceIntegrityValidator.validateEvidenceRecord(
            schemaVersion = "2.0",
            commitSha = evidenceCommit,
            artifactHash = artifactHash,
            timestampMs = now - 1000L,
            overallStatus = "VERIFIED",
            evidenceHash = payloadHash,
            expectedPayloadHash = payloadHash,
            expectedCommit = targetCommit,
            expectedArtifactHash = artifactHash,
            allowHistorical = false,
            currentTimeMs = now
        )

        assertEquals(EvidenceValidationState.COMMIT_MISMATCH, result.state)
        assertFalse(result.isValidCurrent)
    }

    @Test
    fun testArtifactSha256MismatchIsRejected() {
        val now = System.currentTimeMillis()
        val commit = "commit_12345"
        val evidenceArtifactHash = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        val actualArtifactHash = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        val payloadHash = EvidenceIntegrityValidator.sha256("payload")

        val result = EvidenceIntegrityValidator.validateEvidenceRecord(
            schemaVersion = "2.0",
            commitSha = commit,
            artifactHash = evidenceArtifactHash,
            timestampMs = now - 1000L,
            overallStatus = "VERIFIED",
            evidenceHash = payloadHash,
            expectedPayloadHash = payloadHash,
            expectedCommit = commit,
            expectedArtifactHash = actualArtifactHash,
            currentTimeMs = now
        )

        assertEquals(EvidenceValidationState.ARTIFACT_HASH_MISMATCH, result.state)
        assertFalse(result.isValidCurrent)
    }

    @Test
    fun testMissingOrMalformedMetadataIsRejected() {
        val now = System.currentTimeMillis()

        // Missing status
        val missingStatus = EvidenceIntegrityValidator.validateEvidenceRecord(
            schemaVersion = "2.0",
            commitSha = "commit_123",
            artifactHash = "hash_123",
            timestampMs = now,
            overallStatus = "",
            currentTimeMs = now
        )
        assertEquals(EvidenceValidationState.MALFORMED, missingStatus.state)

        // Unsupported schema version
        val badSchema = EvidenceIntegrityValidator.validateEvidenceRecord(
            schemaVersion = "99.0",
            commitSha = "commit_123",
            artifactHash = "hash_123",
            timestampMs = now,
            overallStatus = "VERIFIED",
            currentTimeMs = now
        )
        assertEquals(EvidenceValidationState.MALFORMED, badSchema.state)

        // Non-positive timestamp
        val badTimestamp = EvidenceIntegrityValidator.validateEvidenceRecord(
            schemaVersion = "2.0",
            commitSha = "commit_123",
            artifactHash = "hash_123",
            timestampMs = -100L,
            overallStatus = "VERIFIED",
            currentTimeMs = now
        )
        assertEquals(EvidenceValidationState.MALFORMED, badTimestamp.state)
    }

    @Test
    fun testTamperedPayloadHashIsRejected() {
        val now = System.currentTimeMillis()
        val commit = "commit_12345"
        val artifactHash = "hash_12345"
        val legitimatePayloadHash = EvidenceIntegrityValidator.sha256("legitimate_content")
        val tamperedEvidenceHash = EvidenceIntegrityValidator.sha256("forged_content")

        val result = EvidenceIntegrityValidator.validateEvidenceRecord(
            schemaVersion = "2.0",
            commitSha = commit,
            artifactHash = artifactHash,
            timestampMs = now - 1000L,
            overallStatus = "VERIFIED",
            evidenceHash = tamperedEvidenceHash,
            expectedPayloadHash = legitimatePayloadHash,
            expectedCommit = commit,
            expectedArtifactHash = artifactHash,
            currentTimeMs = now
        )

        assertEquals(EvidenceValidationState.HASH_TAMPERED, result.state)
        assertFalse(result.isValidCurrent)
    }

    @Test
    fun testHistoricalEvidencePreservationWithoutCurrentGateInflation() {
        val now = System.currentTimeMillis()
        val oldCommit = "old_historical_commit_001"
        val currentCommit = "new_current_commit_002"
        val artifactHash = "hash_12345"
        val payloadHash = EvidenceIntegrityValidator.sha256("payload")

        // 1. In strict mode (allowHistorical=false), commit mismatch is rejected as COMMIT_MISMATCH
        val strictResult = EvidenceIntegrityValidator.validateEvidenceRecord(
            schemaVersion = "2.0",
            commitSha = oldCommit,
            artifactHash = artifactHash,
            timestampMs = now - 1000L,
            overallStatus = "VERIFIED",
            evidenceHash = payloadHash,
            expectedPayloadHash = payloadHash,
            expectedCommit = currentCommit,
            expectedArtifactHash = artifactHash,
            allowHistorical = false,
            currentTimeMs = now
        )
        assertEquals(EvidenceValidationState.COMMIT_MISMATCH, strictResult.state)
        assertFalse(strictResult.isValidCurrent)

        // 2. In historical audit mode (allowHistorical=true), it is classified as HISTORICAL and preserved
        val auditResult = EvidenceIntegrityValidator.validateEvidenceRecord(
            schemaVersion = "2.0",
            commitSha = oldCommit,
            artifactHash = artifactHash,
            timestampMs = now - 1000L,
            overallStatus = "VERIFIED",
            evidenceHash = payloadHash,
            expectedPayloadHash = payloadHash,
            expectedCommit = currentCommit,
            expectedArtifactHash = artifactHash,
            allowHistorical = true,
            currentTimeMs = now
        )
        assertEquals(EvidenceValidationState.HISTORICAL, auditResult.state)
        assertFalse("Historical evidence must NOT be treated as active current verification", auditResult.isValidCurrent)
        assertFalse(auditResult.state.isAcceptedAsCurrent)
    }

    @Test
    fun testDeviceVerificationEvidenceTrackerFreshnessAndHistoricalSeparation() {
        val now = System.currentTimeMillis()

        // 1. Record an old historical execution proof (48 hours old)
        val oldTimestamp = now - (48 * 3600 * 1000L)
        val historicalRecord = DeviceExecutionRecord(
            deviceId = "test_device_old",
            deviceModel = "Pixel 8 Pro",
            manufacturer = "Google",
            androidApiLevel = 34,
            isEmulator = false,
            tier = TestTier.DEVICE,
            verifiedCapabilities = setOf("NEURAL_FORWARD_PASS", "SERVICES"),
            timestampMs = oldTimestamp,
            testRunSignature = "RUN_HISTORICAL_001",
            commitSha = "old_commit_001"
        )
        assertTrue(DeviceVerificationEvidenceTracker.recordDeviceExecution(historicalRecord))

        // 2. hasValidDeviceProof should return false because evidence is older than 24h TTL
        assertFalse(
            "Expired device proof must not satisfy fresh device gate",
            DeviceVerificationEvidenceTracker.hasValidDeviceProof(currentTimeMs = now)
        )
        assertEquals(0, DeviceVerificationEvidenceTracker.getFreshDeviceProofs(currentTimeMs = now).size)
        assertEquals(1, DeviceVerificationEvidenceTracker.getHistoricalDeviceProofs(currentTimeMs = now).size)
        assertEquals(1, DeviceVerificationEvidenceTracker.getDeviceProofReport().size)

        // 3. Record a fresh current execution proof (10 seconds old)
        val freshRecord = DeviceExecutionRecord(
            deviceId = "test_device_fresh",
            deviceModel = "Pixel 9",
            manufacturer = "Google",
            androidApiLevel = 35,
            isEmulator = false,
            tier = TestTier.DEVICE,
            verifiedCapabilities = setOf("NEURAL_FORWARD_PASS", "SERVICES", "EMERGENCY_STOP"),
            timestampMs = now - 10_000L,
            testRunSignature = "RUN_FRESH_002",
            commitSha = "current_commit_002"
        )
        assertTrue(DeviceVerificationEvidenceTracker.recordDeviceExecution(freshRecord))

        // 4. Now fresh proof exists and satisfies gate
        assertTrue(DeviceVerificationEvidenceTracker.hasValidDeviceProof(currentTimeMs = now))
        assertTrue(DeviceVerificationEvidenceTracker.hasValidDeviceProof("EMERGENCY_STOP", currentTimeMs = now))
        assertEquals(1, DeviceVerificationEvidenceTracker.getFreshDeviceProofs(currentTimeMs = now).size)
        assertEquals(1, DeviceVerificationEvidenceTracker.getHistoricalDeviceProofs(currentTimeMs = now).size)
        assertEquals(2, DeviceVerificationEvidenceTracker.getDeviceProofReport().size)
    }

    @Test
    fun testNonVerifiedOrUnavailableStatusNeverReportsValidCurrent() {
        val now = System.currentTimeMillis()
        val commit = "commit_12345"

        val unavailableResult = EvidenceIntegrityValidator.validateEvidenceRecord(
            schemaVersion = "2.0",
            commitSha = commit,
            artifactHash = null,
            timestampMs = now - 1000L,
            overallStatus = "UNAVAILABLE",
            currentTimeMs = now
        )
        assertEquals(EvidenceValidationState.UNAVAILABLE, unavailableResult.state)
        assertFalse(unavailableResult.isValidCurrent)

        val failedResult = EvidenceIntegrityValidator.validateEvidenceRecord(
            schemaVersion = "2.0",
            commitSha = commit,
            artifactHash = null,
            timestampMs = now - 1000L,
            overallStatus = "FAILED",
            currentTimeMs = now
        )
        assertEquals(EvidenceValidationState.UNVERIFIED, failedResult.state)
        assertFalse(failedResult.isValidCurrent)
    }
}
