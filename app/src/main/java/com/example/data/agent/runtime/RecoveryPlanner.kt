package com.example.data.agent.runtime

import java.util.UUID

/**
 * Recovery Planner for Wasti AI OS.
 * Determines the optimal remediation strategy when an action fails or returns an unverified state.
 */

enum class RecoveryStrategy {
    RETRY_WITH_BACKOFF,
    REFINE_PARAMETERS,
    FALLBACK_CAPABILITY,
    FALLBACK_PROVIDER,
    ROUTE_TO_MESH_NODE,
    REQUEST_USER_PERMISSION,
    DIAGNOSE_AND_AUTO_PATCH,
    ESCALATE_TO_USER
}

data class RecoveryPlan(
    val planId: String = "rec_${UUID.randomUUID().toString().take(8)}",
    val failureReason: String,
    val recommendedStrategy: RecoveryStrategy,
    val targetCapabilityId: String?,
    val alternativeNodeId: String? = null,
    val modifiedParameters: Map<String, Any> = emptyMap(),
    val userExplanation: String,
    val maxRetries: Int = 3
)

class RecoveryPlanner(
    private val realityRegistry: CapabilityRealityRegistry = CapabilityRealityRegistry()
) {

    /**
     * Synthesizes a RecoveryPlan based on execution error, status, and capability reality.
     */
    fun createRecoveryPlan(
        failedRequest: UnifiedExecutionRequest,
        failedResult: UnifiedExecutionResult
    ): RecoveryPlan {
        val errorMsg = (failedResult.error ?: failedResult.output).lowercase()
        val capId = failedRequest.capabilityId.lowercase()

        return when {
            errorMsg.contains("permission") || errorMsg.contains("inactive") || failedResult.status == UnifiedExecutionStatus.AUTHENTICATION_REQUIRED -> {
                RecoveryPlan(
                    failureReason = "System permission or Accessibility Service inactive",
                    recommendedStrategy = RecoveryStrategy.REQUEST_USER_PERMISSION,
                    targetCapabilityId = failedRequest.capabilityId,
                    userExplanation = "Wasti requires permission activation in Android Settings to perform this action safely."
                )
            }

            errorMsg.contains("toolchain") || errorMsg.contains("not found") || errorMsg.contains("missing") -> {
                // If local toolchain missing, try routing to WASM sandbox or Mesh Node
                val isWasmViable = capId.contains("terminal") || capId.contains("code")
                if (isWasmViable) {
                    RecoveryPlan(
                        failureReason = "Native compiler/runtime missing on Android host",
                        recommendedStrategy = RecoveryStrategy.FALLBACK_CAPABILITY,
                        targetCapabilityId = "wasm_sandbox",
                        modifiedParameters = failedRequest.parameters + mapOf("language" to "wasm"),
                        userExplanation = "Falling back to sandboxed WASM runtime environment since native host toolchain is unavailable."
                    )
                } else {
                    RecoveryPlan(
                        failureReason = "Runtime unavailable on local device",
                        recommendedStrategy = RecoveryStrategy.ROUTE_TO_MESH_NODE,
                        targetCapabilityId = failedRequest.capabilityId,
                        userExplanation = "Offloading task to paired Wasti mesh node with required capabilities."
                    )
                }
            }

            errorMsg.contains("syntax") || errorMsg.contains("compilation") || errorMsg.contains("error:") -> {
                RecoveryPlan(
                    failureReason = "Code or project compilation error detected",
                    recommendedStrategy = RecoveryStrategy.DIAGNOSE_AND_AUTO_PATCH,
                    targetCapabilityId = "debug_project",
                    userExplanation = "Triggering SelfCorrectionEngine to parse diagnostic AST and apply code patch."
                )
            }

            errorMsg.contains("timeout") || errorMsg.contains("busy") || errorMsg.contains("rate limit") -> {
                RecoveryPlan(
                    failureReason = "Transient timeout or network congestion",
                    recommendedStrategy = RecoveryStrategy.RETRY_WITH_BACKOFF,
                    targetCapabilityId = failedRequest.capabilityId,
                    userExplanation = "Retrying execution with exponential backoff delay."
                )
            }

            else -> {
                val reality = realityRegistry.get(failedRequest.capabilityId)
                val fallback = reality?.fallbackCapabilities?.firstOrNull()
                if (fallback != null) {
                    RecoveryPlan(
                        failureReason = "Primary capability failed; fallback available in registry",
                        recommendedStrategy = RecoveryStrategy.FALLBACK_CAPABILITY,
                        targetCapabilityId = fallback,
                        userExplanation = "Switching to registered fallback capability '$fallback'."
                    )
                } else {
                    RecoveryPlan(
                        failureReason = "Unrecoverable execution failure",
                        recommendedStrategy = RecoveryStrategy.ESCALATE_TO_USER,
                        targetCapabilityId = null,
                        userExplanation = "Operation could not complete: ${failedResult.output}"
                    )
                }
            }
        }
    }
}
