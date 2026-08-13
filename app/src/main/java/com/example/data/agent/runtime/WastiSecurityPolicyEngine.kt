package com.example.data.agent.runtime

/**
 * Concrete Security Policy Engine for the Wasti Unified Agent Runtime.
 * Performs request-specific authorization based on tool identity, requested action, permission level,
 * input arguments, target path, workspace boundary containment, execution mode, capability availability,
 * task state, emergency-stop state, and risk level.
 */
class WastiSecurityPolicyEngine(
    private val workspaceManager: WorkspaceManager,
    private val emergencyStopController: EmergencyStopController
) : SecurityPolicy {

    fun isProtectedPath(path: String?): Boolean {
        return WastiRiskModel.isProtectedPath(path)
    }

    override suspend fun evaluateAuthorization(
        request: AuthorizationRequest
    ): AuthorizationDecision {
        // 1. Check Emergency Stop State
        if (request.executionMode == ExecutionMode.EMERGENCY_STOP || emergencyStopController.isEmergencyStopped) {
            return AuthorizationDecision.DENIED
        }

        // 2. Check Task Cancellation State
        if (request.context.cancellationState.isCancelled || request.context.status is AgenticState.Cancelled) {
            return AuthorizationDecision.DENIED
        }

        // 3. Check Capability Availability
        if (!request.capabilityAvailable) {
            return AuthorizationDecision.DENIED
        }

        // 4. Validate Target Path Containment if target path is supplied
        if (!request.targetPath.isNullOrBlank()) {
            val resolvedResult = workspaceManager.resolvePathSafely(request.targetPath)
            if (resolvedResult.isFailure) {
                return AuthorizationDecision.DENIED
            }
        }

        // 5. Evaluate Risk Level per Request
        val risk = WastiRiskModel.evaluateRisk(request.tool, request.input, request.targetPath)

        // 6. Execution Mode Enforcements
        when (request.executionMode) {
            ExecutionMode.EMERGENCY_STOP -> return AuthorizationDecision.DENIED
            ExecutionMode.SAFE -> {
                if (risk == RiskLevel.CRITICAL) return AuthorizationDecision.DENIED
            }
            ExecutionMode.ASSISTED, ExecutionMode.AUTONOMOUS, ExecutionMode.PRIVILEGED -> {
                // Decision determined below based on risk and permission level
            }
        }

        // 7. Request-Specific Decision Matrix
        return when {
            toolIsSafe(request.tool, risk) -> AuthorizationDecision.ALLOWED
            toolRequiresBiometricApproval(request.tool, risk) -> AuthorizationDecision.REQUIRES_BIOMETRIC_APPROVAL
            toolRequiresUserApproval(request.tool, risk) -> AuthorizationDecision.REQUIRES_USER_APPROVAL
            else -> AuthorizationDecision.DENIED
        }
    }

    private fun toolIsSafe(tool: AgentTool, risk: RiskLevel): Boolean {
        return tool.permissionLevel == PermissionLevel.SAFE && risk == RiskLevel.LOW
    }

    private fun toolRequiresUserApproval(tool: AgentTool, risk: RiskLevel): Boolean {
        return tool.permissionLevel == PermissionLevel.CONTROLLED ||
                (tool.permissionLevel == PermissionLevel.SAFE && risk == RiskLevel.HIGH) ||
                risk == RiskLevel.MEDIUM || (risk == RiskLevel.HIGH && tool.permissionLevel != PermissionLevel.PRIVILEGED)
    }

    private fun toolRequiresBiometricApproval(tool: AgentTool, risk: RiskLevel): Boolean {
        return tool.permissionLevel == PermissionLevel.PRIVILEGED || risk == RiskLevel.CRITICAL
    }

    override suspend fun validateExecutionRequest(
        request: ExecutionRequest,
        context: AgentTask
    ): AuthorizationDecision {
        if (emergencyStopController.isEmergencyStopped || context.executionMode == ExecutionMode.EMERGENCY_STOP) {
            return AuthorizationDecision.DENIED
        }
        val resolveRes = workspaceManager.resolvePathSafely(request.workingDirectory)
        if (resolveRes.isFailure) {
            return AuthorizationDecision.DENIED
        }
        return AuthorizationDecision.ALLOWED
    }
}
