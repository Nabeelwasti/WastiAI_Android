package com.example.data.agent.runtime

import com.example.data.node.NodeDataLocality
import com.example.data.node.NodeHealthState
import com.example.data.node.NodeTrustState
import com.example.data.node.WastiNode

/**
 * Concrete Security Policy Engine for the Wasti Unified Agent Runtime.
 * Performs request-specific authorization based on tool identity, requested action, permission level,
 * input arguments, target path, workspace boundary containment, execution mode, capability availability,
 * task state, emergency-stop state, risk level, and multi-node data locality.
 */
class WastiSecurityPolicyEngine(
    private val workspaceManager: WorkspaceManager,
    private val emergencyStopController: EmergencyStopController
) : SecurityPolicy {

    fun isProtectedPath(path: String?): Boolean {
        return WastiRiskModel.isProtectedPath(path)
    }

    /**
     * Stage 17: Validates whether a task can be delegated to a specific mesh node.
     * Enforces:
     * 1. Emergency stop blocks all delegation.
     * 2. Local-only tasks (sensitive files, credentials, secrets, keystore, private keys) CANNOT be delegated to remote nodes.
     * 3. Target node must have ACTIVE or PAIRED trust state (never REVOKED or SUSPENDED).
     * 4. Target node must be ONLINE or DEGRADED (never OFFLINE).
     * 5. Target node locality must satisfy task data locality requirements.
     */
    fun canDelegateToNode(
        node: WastiNode,
        taskLocality: NodeDataLocality,
        requiredCapabilities: List<String> = emptyList()
    ): Boolean {
        if (emergencyStopController.isEmergencyStopped) return false

        // Local node can always execute local tasks
        if (node.isLocal) return true

        // Local-only tasks must NEVER leave the local host
        if (taskLocality == NodeDataLocality.LOCAL_ONLY) return false

        // Check node trust
        if (node.trustState == NodeTrustState.REVOKED || node.trustState == NodeTrustState.SUSPENDED) {
            return false
        }

        // Check node health
        if (node.healthState == NodeHealthState.OFFLINE || node.healthState == NodeHealthState.REVOKED) {
            return false
        }

        // Check sensitive capability restrictions: API keys, keystore, android root, credential operations
        val sensitiveCaps = setOf("keystore", "credentials", "oauth_secrets", "system_credentials", "device_root")
        if (requiredCapabilities.any { it.lowercase() in sensitiveCaps }) {
            return false
        }

        // Check locality hierarchy
        return when (taskLocality) {
            NodeDataLocality.LOCAL_ONLY -> false
            NodeDataLocality.TRUSTED_LAN -> node.dataLocality == NodeDataLocality.TRUSTED_LAN || node.dataLocality == NodeDataLocality.LOCAL_ONLY
            NodeDataLocality.TRUSTED_REMOTE -> node.dataLocality != NodeDataLocality.PUBLIC_REMOTE
            NodeDataLocality.PUBLIC_REMOTE -> true
        }
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

