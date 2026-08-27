package com.example.data.agent.runtime

import java.util.UUID

/**
 * Explainability Layer for Wasti AI OS.
 * Formats transparent, structured, and user-friendly rationales for AI decisions,
 * execution paths, tool dispatches, and verification evidence.
 */

data class ExecutionExplanation(
    val explanationId: String = "exp_${UUID.randomUUID().toString().take(8)}",
    val userQuery: String,
    val intentIdentified: String,
    val selectedCapability: String,
    val selectionRationale: String,
    val executionOutcome: String,
    val verificationEvidenceSummary: String,
    val isTruthfullyVerified: Boolean
)

class WastiExplainabilityEngine {

    /**
     * Synthesizes a structured explanation for a completed execution lifecycle.
     */
    fun explainExecution(
        userQuery: String,
        request: UnifiedExecutionRequest,
        result: UnifiedExecutionResult
    ): ExecutionExplanation {
        val cap = request.capabilityId
        val outcome = if (result.status == UnifiedExecutionStatus.COMPLETED || result.status == UnifiedExecutionStatus.VERIFIED) "Success" else "Failed (${result.status})"
        val isVerified = result.verificationStatus == UnifiedVerificationStatus.VERIFIED

        val rationale = when {
            cap.contains("wasm") -> "Selected sandboxed WASM runtime for deterministic, safe on-device computation without host toolchain dependencies."
            cap.contains("file") -> "Selected WorkspaceManager to perform safe filesystem operations restricted to the app workspace sandbox."
            cap.contains("device") -> "Selected WastiDeviceController to interact with Android system navigation and accessibility services."
            cap.contains("build") || cap.contains("test") -> "Selected WastiBuildAndTestManager to execute verification builds and test suites."
            cap.contains("memory") -> "Selected MemoryManager to perform hybrid semantic search across long-term user memories and preferences."
            else -> "Dispatched to registered capability '$cap' based on direct action intent parsing."
        }

        val evidenceSummary = result.verificationEvidence
            ?: (if (isVerified) "Verified by post-execution observation engine" else "No physical verification evidence observed")

        return ExecutionExplanation(
            userQuery = userQuery,
            intentIdentified = request.parameters["action"]?.toString() ?: cap,
            selectedCapability = cap,
            selectionRationale = rationale,
            executionOutcome = outcome,
            verificationEvidenceSummary = evidenceSummary,
            isTruthfullyVerified = isVerified
        )
    }
}
