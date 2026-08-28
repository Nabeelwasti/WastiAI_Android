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
            errorMsg.contains("invalid parameter") || errorMsg.contains("bad parameter") || errorMsg.contains("missing parameter") || errorMsg.contains("illegal argument") || errorMsg.contains("invalid_argument") -> {
                val refined = refineParameters(failedRequest.capabilityId, failedRequest.parameters, errorMsg)
                RecoveryPlan(
                    failureReason = "Invalid or malformed parameter detected in capability request",
                    recommendedStrategy = RecoveryStrategy.REFINE_PARAMETERS,
                    targetCapabilityId = failedRequest.capabilityId,
                    modifiedParameters = refined,
                    userExplanation = "Refining capability input parameters and automatically retrying."
                )
            }

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

    /**
     * Intelligently cleans, adjusts, or coerces parameters when validation fails.
     */
    fun refineParameters(
        capabilityId: String,
        currentParameters: Map<String, Any>,
        errorDiagnostic: String
    ): Map<String, Any> {
        val refined = currentParameters.toMutableMap()
        val cap = capabilityId.lowercase()

        // 1. Files / Workspace Refinement
        if (cap.contains("file") || cap.contains("workspace")) {
            val path = refined["path"]?.toString() ?: refined["filePath"]?.toString()
            if (path != null) {
                // Strip absolute prefix or illicit path separators if outside sandbox
                val cleaned = path.removePrefix("/").removePrefix("./").replace("../", "")
                refined["path"] = cleaned
                refined["filePath"] = cleaned
            }
            if (!refined.containsKey("content") && refined.containsKey("data")) {
                refined["content"] = refined["data"].toString()
            }
        }

        // 2. Terminal / Code Refinement
        if (cap.contains("terminal") || cap.contains("code") || cap.contains("wasm")) {
            if (!refined.containsKey("action")) {
                refined["action"] = "execute_code"
            }
            if (errorDiagnostic.contains("language") && !refined.containsKey("language")) {
                refined["language"] = "wasm"
            }
        }

        // 3. Search / Query Refinement
        if (cap.contains("search") || cap.contains("memory") || cap.contains("web")) {
            val q = refined["query"]?.toString() ?: refined["q"]?.toString() ?: refined["text"]?.toString()
            if (q != null) {
                refined["query"] = q.trim()
            }
        }

        // 4. Device / UI Refinement
        if (cap.contains("device") || cap.contains("accessibility")) {
            if (!refined.containsKey("action")) {
                refined["action"] = "inspect_screen"
            }
        }

        return refined
    }
}

