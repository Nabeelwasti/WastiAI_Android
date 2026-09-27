package com.example.data.core

import java.security.MessageDigest

/**
 * Machine-verifiable truth states for evidence freshness, integrity, and authenticity.
 */
enum class EvidenceValidationState {
    VALID_CURRENT,
    STALE,
    COMMIT_MISMATCH,
    ARTIFACT_HASH_MISMATCH,
    HASH_TAMPERED,
    MALFORMED,
    INVALID_TIMESTAMP,
    UNAVAILABLE,
    HISTORICAL,
    UNVERIFIED;

    val isAcceptedAsCurrent: Boolean
        get() = this == VALID_CURRENT
}

/**
 * Result of evaluating an evidence artifact or execution record for freshness, integrity, and provenance.
 */
data class EvidenceValidationResult(
    val state: EvidenceValidationState,
    val isValidCurrent: Boolean,
    val reason: String,
    val checkedAtMs: Long = System.currentTimeMillis()
)

/**
 * Canonical Evidence Freshness, Integrity, and Provenance Validator.
 * Prevents stale, mismatched, forged, or ungrounded evidence artifacts from inflating system verification.
 */
object EvidenceIntegrityValidator {

    const val DEFAULT_MAX_AGE_MS = 86_400_000L // 24 hours
    const val CLOCK_SKEW_TOLERANCE_MS = 300_000L // 5 minutes

    private val VALID_SCHEMAS = setOf("1.0", "2.0")
    private val VERIFIED_STATUSES = setOf("VERIFIED", "RUNTIME_VERIFIED", "BUILD_VERIFIED", "DEVICE_RUNTIME_VERIFIED", "BACKEND_DEPLOYMENT_VERIFIED")

    /**
     * Evaluates timestamp freshness against maximum allowable age and clock skew tolerance.
     */
    fun validateFreshness(
        timestampMs: Long,
        maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
        currentTimeMs: Long = System.currentTimeMillis(),
        allowHistorical: Boolean = false
    ): EvidenceValidationResult {
        if (timestampMs <= 0L) {
            return EvidenceValidationResult(
                state = EvidenceValidationState.MALFORMED,
                isValidCurrent = false,
                reason = "Invalid or non-positive timestamp: $timestampMs"
            )
        }

        val ageMs = currentTimeMs - timestampMs
        if (ageMs < -CLOCK_SKEW_TOLERANCE_MS) {
            return EvidenceValidationResult(
                state = EvidenceValidationState.INVALID_TIMESTAMP,
                isValidCurrent = false,
                reason = "Evidence timestamp is in the future (${-ageMs}ms ahead of current clock)"
            )
        }

        if (ageMs > maxAgeMs) {
            return if (allowHistorical) {
                EvidenceValidationResult(
                    state = EvidenceValidationState.HISTORICAL,
                    isValidCurrent = false,
                    reason = "Historical evidence aged ${ageMs / 1000}s (exceeds active TTL of ${maxAgeMs / 1000}s)"
                )
            } else {
                EvidenceValidationResult(
                    state = EvidenceValidationState.STALE,
                    isValidCurrent = false,
                    reason = "Evidence expired: aged ${ageMs / 1000}s (max TTL: ${maxAgeMs / 1000}s)"
                )
            }
        }

        return EvidenceValidationResult(
            state = EvidenceValidationState.VALID_CURRENT,
            isValidCurrent = true,
            reason = "Evidence timestamp is fresh (age: ${ageMs / 1000}s)"
        )
    }

    /**
     * Evaluates commit alignment between evidence record and target/current codebase commit.
     */
    fun validateCommitMatch(
        evidenceCommitSha: String?,
        expectedCommitSha: String?,
        allowHistorical: Boolean = false
    ): EvidenceValidationResult {
        if (expectedCommitSha.isNullOrBlank() || expectedCommitSha == "local_development") {
            return EvidenceValidationResult(
                state = EvidenceValidationState.VALID_CURRENT,
                isValidCurrent = true,
                reason = "Commit validation skipped for unconstrained local environment"
            )
        }

        if (evidenceCommitSha.isNullOrBlank()) {
            return EvidenceValidationResult(
                state = EvidenceValidationState.MALFORMED,
                isValidCurrent = false,
                reason = "Evidence is missing commit SHA"
            )
        }

        val matches = evidenceCommitSha.startsWith(expectedCommitSha, ignoreCase = true) ||
                      expectedCommitSha.startsWith(evidenceCommitSha, ignoreCase = true)

        if (!matches) {
            return if (allowHistorical) {
                EvidenceValidationResult(
                    state = EvidenceValidationState.HISTORICAL,
                    isValidCurrent = false,
                    reason = "Historical evidence from commit '$evidenceCommitSha' (target is '$expectedCommitSha')"
                )
            } else {
                EvidenceValidationResult(
                    state = EvidenceValidationState.COMMIT_MISMATCH,
                    isValidCurrent = false,
                    reason = "Evidence commit '$evidenceCommitSha' does not match target commit '$expectedCommitSha'"
                )
            }
        }

        return EvidenceValidationResult(
            state = EvidenceValidationState.VALID_CURRENT,
            isValidCurrent = true,
            reason = "Evidence commit '$evidenceCommitSha' matches target commit"
        )
    }

    /**
     * Evaluates artifact SHA-256 hash match to prevent evidence swapping across builds.
     */
    fun validateArtifactHash(
        evidenceArtifactHash: String?,
        actualArtifactHash: String?
    ): EvidenceValidationResult {
        if (actualArtifactHash.isNullOrBlank()) {
            return EvidenceValidationResult(
                state = EvidenceValidationState.VALID_CURRENT,
                isValidCurrent = true,
                reason = "Artifact hash validation skipped (no expected hash specified)"
            )
        }

        if (evidenceArtifactHash.isNullOrBlank()) {
            return EvidenceValidationResult(
                state = EvidenceValidationState.MALFORMED,
                isValidCurrent = false,
                reason = "Evidence record is missing artifact SHA-256 hash"
            )
        }

        if (!evidenceArtifactHash.equals(actualArtifactHash, ignoreCase = true)) {
            return EvidenceValidationResult(
                state = EvidenceValidationState.ARTIFACT_HASH_MISMATCH,
                isValidCurrent = false,
                reason = "Evidence artifact hash '$evidenceArtifactHash' does not match expected hash '$actualArtifactHash'"
            )
        }

        return EvidenceValidationResult(
            state = EvidenceValidationState.VALID_CURRENT,
            isValidCurrent = true,
            reason = "Artifact SHA-256 hash verified authentic"
        )
    }

    /**
     * Comprehensive validation of an evidence record against schema, cryptographic hash, freshness,
     * commit, artifact hash, and truth state requirements.
     */
    fun validateEvidenceRecord(
        schemaVersion: String?,
        commitSha: String?,
        artifactHash: String?,
        timestampMs: Long,
        overallStatus: String?,
        evidenceHash: String? = null,
        expectedPayloadHash: String? = null,
        expectedCommit: String? = null,
        expectedArtifactHash: String? = null,
        maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
        allowHistorical: Boolean = false,
        currentTimeMs: Long = System.currentTimeMillis()
    ): EvidenceValidationResult {
        // 1. Mandatory Schema & Format
        val resolvedSchema = schemaVersion ?: "1.0"
        if (resolvedSchema !in VALID_SCHEMAS) {
            return EvidenceValidationResult(
                state = EvidenceValidationState.MALFORMED,
                isValidCurrent = false,
                reason = "Unsupported evidence schema version: '$resolvedSchema'"
            )
        }

        if (overallStatus.isNullOrBlank()) {
            return EvidenceValidationResult(
                state = EvidenceValidationState.MALFORMED,
                isValidCurrent = false,
                reason = "Missing overall verification status in evidence record"
            )
        }

        // 2. Cryptographic Tamper Check
        if (evidenceHash != null && expectedPayloadHash != null) {
            if (!evidenceHash.equals(expectedPayloadHash, ignoreCase = true)) {
                return EvidenceValidationResult(
                    state = EvidenceValidationState.HASH_TAMPERED,
                    isValidCurrent = false,
                    reason = "Cryptographic evidence hash mismatch (tampered payload)"
                )
            }
        }

        // 3. Artifact Hash Check
        val artifactCheck = validateArtifactHash(artifactHash, expectedArtifactHash)
        if (!artifactCheck.isValidCurrent) {
            return artifactCheck
        }

        // 4. Commit Match Check
        val commitCheck = validateCommitMatch(commitSha, expectedCommit, allowHistorical)
        if (!commitCheck.isValidCurrent) {
            return commitCheck
        }

        // 5. Freshness Check
        val freshnessCheck = validateFreshness(timestampMs, maxAgeMs, currentTimeMs, allowHistorical)
        if (!freshnessCheck.isValidCurrent) {
            return freshnessCheck
        }

        // 6. Status Truth Check
        val normStatus = overallStatus.uppercase().trim()
        if (normStatus !in VERIFIED_STATUSES) {
            val state = when (normStatus) {
                "UNAVAILABLE", "DEVICE_RUNTIME_UNAVAILABLE", "BACKEND_UNREACHABLE" -> EvidenceValidationState.UNAVAILABLE
                "FAILED", "DEVICE_RUNTIME_FAILED" -> EvidenceValidationState.UNVERIFIED
                else -> EvidenceValidationState.UNVERIFIED
            }
            return EvidenceValidationResult(
                state = state,
                isValidCurrent = false,
                reason = "Evidence reports non-verified status: '$normStatus'"
            )
        }

        return EvidenceValidationResult(
            state = EvidenceValidationState.VALID_CURRENT,
            isValidCurrent = true,
            reason = "Evidence verified fresh, commit-aligned, hash-intact, and in truthful state '$normStatus'"
        )
    }

    /**
     * Computes deterministic SHA-256 string for canonical payload strings.
     */
    fun sha256(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(content.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
