package com.example.data.agent.runtime

import java.util.UUID

/**
 * Stage 1: Core Domain Models for the Wasti Unified Agent Runtime.
 * Represents WHAT Wasti can express without coupling to UI or execution engines.
 */

sealed class AgenticState(open val message: String) {
    data class Idle(override val message: String = "Agent is idle") : AgenticState(message)
    data class Analyzing(override val message: String = "Analyzing prompt and context") : AgenticState(message)
    data class Planning(override val message: String = "Generating execution plan") : AgenticState(message)
    data class Inspecting(override val message: String = "Inspecting workspace and project files") : AgenticState(message)
    data class WaitingForPermission(override val message: String = "Awaiting user or biometric permission") : AgenticState(message)
    data class Editing(override val message: String = "Modifying source files") : AgenticState(message)
    data class Executing(override val message: String = "Executing task step") : AgenticState(message)
    data class Observing(override val message: String = "Observing execution output") : AgenticState(message)
    data class Debugging(override val message: String = "Analyzing errors") : AgenticState(message)
    data class Testing(override val message: String = "Running verification tests") : AgenticState(message)
    data class Verification(override val message: String = "Verifying build integrity") : AgenticState(message)
    data class Completed(override val message: String = "Task completed successfully") : AgenticState(message)
    data class Failed(override val message: String = "Task execution failed") : AgenticState(message)
    data class SecurityBlocked(override val message: String = "Action blocked by security policy") : AgenticState(message)
    data class Cancelled(override val message: String = "Task cancelled by user") : AgenticState(message)
    data class RolledBack(override val message: String = "Workspace rolled back to previous snapshot") : AgenticState(message)
}

enum class ExecutionMode {
    SAFE,
    ASSISTED,
    AUTONOMOUS,
    PRIVILEGED,
    EMERGENCY_STOP
}

enum class ExecutionErrorType {
    COMPILATION,
    SYNTAX,
    RUNTIME,
    TIMEOUT,
    MEMORY,
    SECURITY,
    PERMISSION,
    NETWORK,
    PROVIDER,
    PROVIDER_UNAVAILABLE,
    AUTHENTICATION_FAILED,
    QUOTA_EXHAUSTED,
    INVALID_REQUEST,
    CANCELLED,
    UNKNOWN,
    NONE
}

data class ExecutionStatus(
    val isSuccess: Boolean,
    val message: String
)

data class ExecutionResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    val executionTimeMs: Long,
    val status: ExecutionStatus,
    val errorType: ExecutionErrorType
)

/**
 * Structured execution request.
 * ABSOLUTELY NO single unrestricted shell command string field.
 */
data class ExecutionRequest(
    val executable: String,
    val arguments: List<String>,
    val workingDirectory: String,
    val language: String? = null,
    val timeoutMs: Long = 30000L,
    val environment: Map<String, String> = emptyMap()
)

data class TaskId(val value: String = UUID.randomUUID().toString())

data class TaskCancellationState(
    val isCancelled: Boolean = false,
    val cancellationReason: String? = null,
    val cancelledAt: Long? = null
)

enum class AgentRole {
    RESEARCH,
    CODING,
    TESTING,
    DEBUGGING,
    SECURITY,
    DESIGN,
    DATA,
    BROWSER_INTERNET,
    EXECUTIVE,
    PLANNING,
    QUALITY_REVIEW,
    WORKFLOW
}

enum class AgentTaskState {
    PENDING,
    SCHEDULED,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
    BLOCKED
}

enum class AgentTaskPriority {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}

data class AgentTask(
    val taskId: TaskId = TaskId(),
    val prompt: String = "",
    val title: String = prompt,
    val description: String = prompt,
    val status: AgenticState = AgenticState.Idle(),
    val state: AgentTaskState = AgentTaskState.PENDING,
    val assignedRole: AgentRole? = null,
    val assignedAgentId: String? = null,
    val priority: AgentTaskPriority = AgentTaskPriority.MEDIUM,
    val dependencies: List<String> = emptyList(),
    val inputData: Map<String, Any?> = emptyMap(),
    val requiredCapabilities: List<String> = emptyList(),
    val executionMode: ExecutionMode = ExecutionMode.SAFE,
    val cancellationState: TaskCancellationState = TaskCancellationState(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * Domain events emitted by the Agent Runtime.
 */
sealed class AgentEvent(
    open val eventId: String = UUID.randomUUID().toString(),
    open val taskId: TaskId,
    open val timestamp: Long = System.currentTimeMillis()
) {
    data class TaskCreated(override val taskId: TaskId, val prompt: String) : AgentEvent(taskId = taskId)
    data class UnderstandingStarted(override val taskId: TaskId, val prompt: String) : AgentEvent(taskId = taskId)
    data class PlanningStarted(override val taskId: TaskId, val prompt: String) : AgentEvent(taskId = taskId)
    data class PlanningTask(override val taskId: TaskId, val details: String) : AgentEvent(taskId = taskId)
    // TODO: unused — evaluate for removal or wiring in
    data class CapabilityChecked(override val taskId: TaskId, val capability: String, val isAvailable: Boolean) : AgentEvent(taskId = taskId)
    // TODO: unused — evaluate for removal or wiring in
    data class Searching(override val taskId: TaskId, val query: String) : AgentEvent(taskId = taskId)
    // TODO: unused — evaluate for removal or wiring in
    data class Connecting(override val taskId: TaskId, val endpoint: String) : AgentEvent(taskId = taskId)
    data class Authenticating(override val taskId: TaskId, val service: String) : AgentEvent(taskId = taskId)
    data class ProviderSelected(override val taskId: TaskId, val providerId: String, val modelId: String?) : AgentEvent(taskId = taskId)
    data class WaitingForUser(override val taskId: TaskId, val reason: String) : AgentEvent(taskId = taskId)
    data class CapabilityUnavailable(override val taskId: TaskId, val capability: String, val reason: String) : AgentEvent(taskId = taskId)
    // TODO: unused — evaluate for removal or wiring in
    data class InspectingProject(override val taskId: TaskId, val path: String) : AgentEvent(taskId = taskId)
    data class ToolRequested(override val taskId: TaskId, val toolName: String, val args: Map<String, Any?>) : AgentEvent(taskId = taskId)
    // TODO: unused — evaluate for removal or wiring in
    data class PermissionRequired(override val taskId: TaskId, val action: String, val level: String) : AgentEvent(taskId = taskId)
    // TODO: unused — evaluate for removal or wiring in
    data class PermissionRequested(override val taskId: TaskId, val action: String, val level: String) : AgentEvent(taskId = taskId)
    data class ToolStarted(override val taskId: TaskId, val toolName: String) : AgentEvent(taskId = taskId)
    data class ToolCompleted(override val taskId: TaskId, val toolName: String, val isSuccess: Boolean) : AgentEvent(taskId = taskId)
    data class ToolFailed(override val taskId: TaskId, val toolName: String, val error: String) : AgentEvent(taskId = taskId)
    data class SecurityBlocked(override val taskId: TaskId, val reason: String) : AgentEvent(taskId = taskId)
    // TODO: unused — evaluate for removal or wiring in
    data class EditingFile(override val taskId: TaskId, val filePath: String) : AgentEvent(taskId = taskId)
    // TODO: unused — evaluate for removal or wiring in
    data class ExecutionStarted(override val taskId: TaskId, val executable: String) : AgentEvent(taskId = taskId)
    // TODO: unused — evaluate for removal or wiring in
    data class ExecutionCompleted(override val taskId: TaskId, val exitCode: Int) : AgentEvent(taskId = taskId)
    data class ObservationReceived(override val taskId: TaskId, val observationSummary: String) : AgentEvent(taskId = taskId)
    // TODO: unused — evaluate for removal or wiring in
    data class CompilationFailed(override val taskId: TaskId, val errors: String) : AgentEvent(taskId = taskId)
    data class DiagnosisCreated(override val taskId: TaskId, val category: String, val summary: String) : AgentEvent(taskId = taskId)
    data class CorrectionProposed(override val taskId: TaskId, val proposal: String) : AgentEvent(taskId = taskId)
    // TODO: unused — evaluate for removal or wiring in
    data class FixingError(override val taskId: TaskId, val errorSummary: String) : AgentEvent(taskId = taskId)
    data class TestingStarted(override val taskId: TaskId, val target: String) : AgentEvent(taskId = taskId)
    data class VerificationStarted(override val taskId: TaskId, val details: String) : AgentEvent(taskId = taskId)
    data class VerificationCompleted(override val taskId: TaskId, val isSuccessful: Boolean) : AgentEvent(taskId = taskId)
    data class TaskCompleted(override val taskId: TaskId, val summary: String) : AgentEvent(taskId = taskId)
    data class TaskFailed(override val taskId: TaskId, val error: String) : AgentEvent(taskId = taskId)
    data class TaskCancelled(override val taskId: TaskId, val reason: String) : AgentEvent(taskId = taskId)
    data class EmergencyStopped(override val taskId: TaskId, val reason: String) : AgentEvent(taskId = taskId)
    data class EmergencyStopTriggered(override val taskId: TaskId, val reason: String) : AgentEvent(taskId = taskId)
    data class TaskRolledBack(override val taskId: TaskId, val snapshotId: String) : AgentEvent(taskId = taskId)

    // Stage 13: Distributed Node, WebSocket and Cross-Device Event Types
    data class NodeConnected(override val taskId: TaskId = TaskId("node_lifecycle"), val nodeId: String, val nodeName: String, val platform: String) : AgentEvent(taskId = taskId)
    data class NodeDisconnected(override val taskId: TaskId = TaskId("node_lifecycle"), val nodeId: String, val reason: String) : AgentEvent(taskId = taskId)
    data class NodeTrustChanged(override val taskId: TaskId = TaskId("node_lifecycle"), val nodeId: String, val newTrustState: String) : AgentEvent(taskId = taskId)
    data class CapabilityChanged(override val taskId: TaskId = TaskId("capability_lifecycle"), val capabilityId: String, val status: String) : AgentEvent(taskId = taskId)
    data class ExecutionStateChanged(override val taskId: TaskId = TaskId("execution_lifecycle"), val state: String, val details: String) : AgentEvent(taskId = taskId)

    // Stage 14: Self-Evolving Capability Engine & Lifecycle Events
    data class CapabilityDesignStarted(override val taskId: TaskId = TaskId("capability_evolution"), val capabilityId: String, val purpose: String) : AgentEvent(taskId = taskId)
    data class CapabilityBuildStarted(override val taskId: TaskId = TaskId("capability_evolution"), val capabilityId: String) : AgentEvent(taskId = taskId)
    data class CapabilityBuildCompleted(override val taskId: TaskId = TaskId("capability_evolution"), val capabilityId: String, val isSuccess: Boolean) : AgentEvent(taskId = taskId)
    data class CapabilityTestStarted(override val taskId: TaskId = TaskId("capability_evolution"), val capabilityId: String) : AgentEvent(taskId = taskId)
    data class CapabilityTestCompleted(override val taskId: TaskId = TaskId("capability_evolution"), val capabilityId: String, val isSuccess: Boolean) : AgentEvent(taskId = taskId)
    data class CapabilityVerificationStarted(override val taskId: TaskId = TaskId("capability_evolution"), val capabilityId: String) : AgentEvent(taskId = taskId)
    data class CapabilityVerified(override val taskId: TaskId = TaskId("capability_evolution"), val capabilityId: String, val evidence: String) : AgentEvent(taskId = taskId)
    data class CapabilityRejected(override val taskId: TaskId = TaskId("capability_evolution"), val capabilityId: String, val reason: String) : AgentEvent(taskId = taskId)
    data class CapabilityPromoted(override val taskId: TaskId = TaskId("capability_evolution"), val capabilityId: String) : AgentEvent(taskId = taskId)
    data class SelfCorrectionStarted(override val taskId: TaskId = TaskId("capability_correction"), val errorSummary: String, val attempt: Int) : AgentEvent(taskId = taskId)
    data class SelfCorrectionCompleted(override val taskId: TaskId = TaskId("capability_correction"), val isFixed: Boolean, val resolution: String) : AgentEvent(taskId = taskId)
    data class RollbackStarted(override val taskId: TaskId = TaskId("capability_rollback"), val snapshotId: String, val reason: String) : AgentEvent(taskId = taskId)
    data class RollbackCompleted(override val taskId: TaskId = TaskId("capability_rollback"), val snapshotId: String, val isSuccess: Boolean) : AgentEvent(taskId = taskId)

    // Stage 15: Proactive Autonomous Assistant, Background Intelligence & Multi-Node Synchronization
    data class ProactiveTaskScheduled(override val taskId: TaskId = TaskId("proactive_task"), val proactiveTaskId: String, val title: String, val scheduledAt: Long, val isRecurring: Boolean) : AgentEvent(taskId = taskId)
    data class ProactiveTaskStarted(override val taskId: TaskId = TaskId("proactive_task"), val proactiveTaskId: String, val title: String, val origin: String) : AgentEvent(taskId = taskId)
    data class ProactiveTaskCompleted(override val taskId: TaskId = TaskId("proactive_task"), val proactiveTaskId: String, val summary: String, val durationMs: Long) : AgentEvent(taskId = taskId)
    data class ProactiveTaskFailed(override val taskId: TaskId = TaskId("proactive_task"), val proactiveTaskId: String, val error: String, val willRetry: Boolean) : AgentEvent(taskId = taskId)
    data class ProactiveTaskCancelled(override val taskId: TaskId = TaskId("proactive_task"), val proactiveTaskId: String, val reason: String) : AgentEvent(taskId = taskId)
    data class TaskLeaseAcquired(override val taskId: TaskId = TaskId("node_lease"), val proactiveTaskId: String, val ownerNodeId: String, val leaseExpiresAt: Long) : AgentEvent(taskId = taskId)
    data class TaskLeaseLost(override val taskId: TaskId = TaskId("node_lease"), val proactiveTaskId: String, val previousOwnerNodeId: String, val reason: String) : AgentEvent(taskId = taskId)
    data class TaskFailoverStarted(override val taskId: TaskId = TaskId("node_failover"), val proactiveTaskId: String, val failedNodeId: String, val targetNodeId: String) : AgentEvent(taskId = taskId)
    data class TaskFailoverCompleted(override val taskId: TaskId = TaskId("node_failover"), val proactiveTaskId: String, val targetNodeId: String, val isSuccess: Boolean) : AgentEvent(taskId = taskId)
    data class NodeStateChanged(override val taskId: TaskId = TaskId("node_state"), val nodeId: String, val healthState: String, val connectionState: String) : AgentEvent(taskId = taskId)
    data class CapabilityRequested(override val taskId: TaskId = TaskId("cap_request"), val capabilityId: String, val requesterTaskId: String) : AgentEvent(taskId = taskId)
    data class CapabilityProvisioningStarted(override val taskId: TaskId = TaskId("cap_provision"), val capabilityId: String) : AgentEvent(taskId = taskId)
    data class CapabilityProvisioningCompleted(override val taskId: TaskId = TaskId("cap_provision"), val capabilityId: String, val isSuccess: Boolean) : AgentEvent(taskId = taskId)
    data class AutonomousRecoveryStarted(override val taskId: TaskId = TaskId("auto_recovery"), val taskIdRef: String, val failureReason: String) : AgentEvent(taskId = taskId)
    data class AutonomousRecoveryCompleted(override val taskId: TaskId = TaskId("auto_recovery"), val taskIdRef: String, val isSuccess: Boolean, val details: String) : AgentEvent(taskId = taskId)

    // Stage 16: Persistent Autonomous Memory, Reboot Recovery & Job Durability
    data class ProactiveTaskRecovered(override val taskId: TaskId = TaskId("proactive_recovery"), val proactiveTaskId: String, val title: String, val previousState: String, val newState: String) : AgentEvent(taskId = taskId)
    data class RebootRecoveryCompleted(override val taskId: TaskId = TaskId("proactive_recovery"), val recoveredTaskCount: Int, val durationMs: Long) : AgentEvent(taskId = taskId)

    // Stage 17: Autonomous Multi-Node Execution Mesh & Capability Federation
    data class NodeCapabilityAdvertised(override val taskId: TaskId = TaskId("mesh_federation"), val nodeId: String, val capabilityId: String, val realityState: String) : AgentEvent(taskId = taskId)
    data class NodeCapabilityUpdated(override val taskId: TaskId = TaskId("mesh_federation"), val nodeId: String, val capabilityId: String, val status: String) : AgentEvent(taskId = taskId)
    data class NodeCapabilityRemoved(override val taskId: TaskId = TaskId("mesh_federation"), val nodeId: String, val capabilityId: String) : AgentEvent(taskId = taskId)
    data class NodeSyncStarted(override val taskId: TaskId = TaskId("mesh_sync"), val nodeId: String, val syncType: String) : AgentEvent(taskId = taskId)
    data class NodeSyncCompleted(override val taskId: TaskId = TaskId("mesh_sync"), val nodeId: String, val capabilitiesCount: Int, val durationMs: Long) : AgentEvent(taskId = taskId)
    data class NodeTaskOffered(override val taskId: TaskId = TaskId("mesh_task"), val proactiveTaskId: String, val targetNodeId: String, val requiredCapabilities: List<String>) : AgentEvent(taskId = taskId)
    data class NodeTaskAccepted(override val taskId: TaskId = TaskId("mesh_task"), val proactiveTaskId: String, val nodeId: String, val leaseExpiresAt: Long) : AgentEvent(taskId = taskId)
    data class NodeTaskRejected(override val taskId: TaskId = TaskId("mesh_task"), val proactiveTaskId: String, val nodeId: String, val reason: String) : AgentEvent(taskId = taskId)
    data class NodeTaskDelegated(override val taskId: TaskId = TaskId("mesh_task"), val proactiveTaskId: String, val targetNodeId: String) : AgentEvent(taskId = taskId)
    data class NodeTaskProgress(override val taskId: TaskId = TaskId("mesh_task"), val proactiveTaskId: String, val nodeId: String, val progressSummary: String) : AgentEvent(taskId = taskId)
    data class NodeLeaseRenewed(override val taskId: TaskId = TaskId("mesh_lease"), val proactiveTaskId: String, val nodeId: String, val newExpiresAt: Long) : AgentEvent(taskId = taskId)
    data class NodeMeshDisconnected(override val taskId: TaskId = TaskId("mesh_lifecycle"), val nodeId: String, val reason: String) : AgentEvent(taskId = taskId)
    data class NodeMeshReconnected(override val taskId: TaskId = TaskId("mesh_lifecycle"), val nodeId: String) : AgentEvent(taskId = taskId)
}
