package com.example.data.agent.runtime

import com.example.data.security.WastiSecureStorage
import java.util.UUID

/**
 * Execution Environment Tier for Wasti AI OS.
 * Distinguishes host/Robolectric test execution from physical Android hardware reality.
 */
enum class ExecutionEnvironmentTier {
    PHYSICAL_DEVICE,
    ROBOLECTRIC_HOST,
    EMULATOR,
    CLOUD_BACKBONE;

    val isSimulated: Boolean
        get() = this == ROBOLECTRIC_HOST

    companion object {
        fun current(): ExecutionEnvironmentTier = when {
            WastiSecureStorage.isRobolectricHost -> ROBOLECTRIC_HOST
            android.os.Build.FINGERPRINT.startsWith("generic") || android.os.Build.MODEL.contains("google_sdk") -> EMULATOR
            else -> PHYSICAL_DEVICE
        }
    }
}

/**
 * Terminal Truth States for Wasti AI OS.
 * Guarantees zero-fabrication and avoids ambiguous or contradictory state definitions.
 *
 * Distinct truth matrix:
 * - COMPLETED_VERIFIED: Execution finished successfully AND independent post-state verification evidence was obtained.
 * - COMPLETED_UNVERIFIED: Execution finished without error, but no independent observation/verification was available.
 * - EXECUTION_FAILED: The executor failed during execution (runtime error, crash, exception).
 * - VERIFICATION_FAILED: Execution completed, but post-state verification failed (e.g. expected artifact was missing or invalid).
 * - VERIFICATION_UNAVAILABLE: Verification was requested but verification engine/probes were not available.
 * - BLOCKED: Execution was rejected or blocked by security policies, emergency stop, or safety gates.
 * - CANCELLED: Execution was cancelled by user or supervisory timeout.
 * - ROLLED_BACK: Execution failed and recovery rollback was applied.
 */
enum class TerminalTruthState {
    COMPLETED_VERIFIED,
    COMPLETED_UNVERIFIED,
    EXECUTION_FAILED,
    VERIFICATION_FAILED,
    VERIFICATION_UNAVAILABLE,
    BLOCKED,
    CANCELLED,
    ROLLED_BACK;

    val isTerminal: Boolean
        get() = true

    val isVerified: Boolean
        get() = this == COMPLETED_VERIFIED

    val isExecutionSuccess: Boolean
        get() = this == COMPLETED_VERIFIED || this == COMPLETED_UNVERIFIED

    val isTerminalFailure: Boolean
        get() = this == EXECUTION_FAILED || this == VERIFICATION_FAILED || this == BLOCKED

    val isCancelled: Boolean
        get() = this == CANCELLED

    val isRolledBack: Boolean
        get() = this == ROLLED_BACK
}

/**
 * Structured Evidence Bundle capturing end-to-end provenance.
 * Provides verifiable audit trail from execution through observation and verification.
 */
data class EvidenceBundle(
    val evidenceId: String = UUID.randomUUID().toString(),
    val taskId: String,
    val actionId: String,
    val capabilityId: String,
    val provider: String,
    val nodeId: String = "local_android_node",
    val environmentTier: ExecutionEnvironmentTier = ExecutionEnvironmentTier.current(),
    val timestamp: Long = System.currentTimeMillis(),
    val observationType: String = if (WastiSecureStorage.isRobolectricHost) "HOST_SIMULATED_PROBE" else "POST_EXECUTION_PROBE",
    val source: String = "WastiObservationEngine",
    val preState: String? = null,
    val postState: String? = null,
    val artifactPath: String? = null,
    val artifactHash: String? = null,
    val command: String? = null,
    val exitCode: Int? = null,
    val httpStatus: Int? = null,
    val screenshotRef: String? = null,
    val independentProbe: String? = null,
    val verifier: String = if (WastiSecureStorage.isRobolectricHost) "WastiHostVerificationProbe" else "WastiVerificationEngine",
    val verificationMethod: String = if (WastiSecureStorage.isRobolectricHost) "ROBOLECTRIC_HOST_VERIFICATION" else "INDEPENDENT_PROBE",
    val confidence: Double = if (WastiSecureStorage.isRobolectricHost) 0.85 else 1.0,
    val provenance: String = if (WastiSecureStorage.isRobolectricHost) "CANONICAL_FABRIC_HOST_SIMULATED" else "CANONICAL_EXECUTION_FABRIC"
)

/**
 * Canonical Immutable Execution Fact.
 * Single source of truth consumed across the cognitive and execution fabric:
 * UnifiedExecutionFabric, WastiOSRuntime, UniversalAutonomousExecutionLoop,
 * ExecutionMemoryRecorder, TaskTimeline, and RealityAuditEngine.
 */
data class ExecutionFact(
    val factId: String = UUID.randomUUID().toString(),
    val taskId: String,
    val actionId: String = UUID.randomUUID().toString(),
    val command: String,
    val capabilityId: String,
    val executor: String,
    val provider: String = "LOCAL_EMBEDDED",
    val nodeId: String = "local_android_node",
    val environmentTier: ExecutionEnvironmentTier = ExecutionEnvironmentTier.current(),
    val startedAt: Long = System.currentTimeMillis(),
    val completedAt: Long = System.currentTimeMillis(),
    val durationMs: Long = completedAt - startedAt,
    val rawOutput: String = "",
    val rawError: String? = null,
    val executionStatus: UnifiedExecutionStatus,
    val observationStatus: ObservationStatus = ObservationStatus.UNKNOWN,
    val observationEvidence: String? = null,
    val verificationStatus: UnifiedVerificationStatus = UnifiedVerificationStatus.UNVERIFIED,
    val verificationEvidence: String? = null,
    val terminalTruthState: TerminalTruthState,
    val recoveryStrategyApplied: String? = null,
    val evidenceBundle: EvidenceBundle? = null,
    val timestamp: Long = completedAt
) {
    val isVerifiedSuccess: Boolean
        get() = terminalTruthState.isVerified

    val isTerminalFailure: Boolean
        get() = terminalTruthState.isTerminalFailure

    val isTerminal: Boolean
        get() = terminalTruthState.isTerminal

    val isSuccess: Boolean
        get() = terminalTruthState.isExecutionSuccess
}
