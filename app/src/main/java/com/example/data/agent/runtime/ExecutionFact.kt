package com.example.data.agent.runtime

import java.util.UUID

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
    ROLLED_BACK
}

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
    val timestamp: Long = completedAt
) {
    val isVerifiedSuccess: Boolean
        get() = terminalTruthState == TerminalTruthState.COMPLETED_VERIFIED

    val isTerminalFailure: Boolean
        get() = terminalTruthState == TerminalTruthState.EXECUTION_FAILED ||
                terminalTruthState == TerminalTruthState.VERIFICATION_FAILED ||
                terminalTruthState == TerminalTruthState.BLOCKED
}
