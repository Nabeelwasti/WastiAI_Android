package com.example.data.agent.runtime

enum class AuthorizationDecision {
    ALLOWED,
    REQUIRES_USER_APPROVAL,
    REQUIRES_BIOMETRIC_APPROVAL,
    DENIED
}

/**
 * Defense-in-depth authorization request.
 * Evaluates tool identity, permission level, specific inputs, target paths, workspace boundaries,
 * execution mode, and capability availability.
 */
data class AuthorizationRequest(
    val tool: AgentTool,
    val input: Map<String, Any?>,
    val targetPath: String? = null,
    val executionMode: ExecutionMode = ExecutionMode.SAFE,
    val capabilityAvailable: Boolean = true,
    val context: AgentTask
)

interface SecurityPolicy {
    suspend fun evaluateAuthorization(
        request: AuthorizationRequest
    ): AuthorizationDecision

    suspend fun validateExecutionRequest(
        request: ExecutionRequest,
        context: AgentTask
    ): AuthorizationDecision
}

/**
 * Platform-independent permission model interface.
 * UI/Android biometric prompt integration is provided via adapters, never directly invoked by the core runtime.
 */
interface PermissionModel {
    suspend fun requestUserApproval(
        actionSummary: String,
        permissionLevel: PermissionLevel
    ): Boolean

    suspend fun requestBiometricApproval(
        promptReason: String
    ): Boolean
}

/**
 * Audit contract for recording agent operations, security decisions, and violations.
 * Must never record secrets, API keys, tokens, or raw credential values.
 */
interface AuditLogger {
    suspend fun logToolInvocation(
        taskId: TaskId,
        toolName: String,
        sanitizedInput: Map<String, Any?>,
        decision: AuthorizationDecision,
        permissionGranted: Boolean?,
        result: Map<String, Any?>?,
        executionTimeMs: Long,
        timestamp: Long = System.currentTimeMillis()
    )

    suspend fun logSecurityViolation(
        taskId: TaskId,
        violationType: String,
        details: String,
        timestamp: Long = System.currentTimeMillis()
    )

    fun sanitizeMetadata(rawInput: Map<String, Any?>): Map<String, Any?>
}
