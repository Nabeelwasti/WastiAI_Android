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

data class AgentTask(
    val taskId: TaskId,
    val prompt: String,
    val status: AgenticState,
    val executionMode: ExecutionMode = ExecutionMode.SAFE,
    val cancellationState: TaskCancellationState = TaskCancellationState(),
    val createdAt: Long = System.currentTimeMillis()
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
    data class CapabilityChecked(override val taskId: TaskId, val capability: String, val isAvailable: Boolean) : AgentEvent(taskId = taskId)
    data class Searching(override val taskId: TaskId, val query: String) : AgentEvent(taskId = taskId)
    data class Connecting(override val taskId: TaskId, val endpoint: String) : AgentEvent(taskId = taskId)
    data class Authenticating(override val taskId: TaskId, val service: String) : AgentEvent(taskId = taskId)
    data class ProviderSelected(override val taskId: TaskId, val providerId: String, val modelId: String?) : AgentEvent(taskId = taskId)
    data class WaitingForUser(override val taskId: TaskId, val reason: String) : AgentEvent(taskId = taskId)
    data class CapabilityUnavailable(override val taskId: TaskId, val capability: String, val reason: String) : AgentEvent(taskId = taskId)
    data class InspectingProject(override val taskId: TaskId, val path: String) : AgentEvent(taskId = taskId)
    data class ToolRequested(override val taskId: TaskId, val toolName: String, val args: Map<String, Any?>) : AgentEvent(taskId = taskId)
    data class PermissionRequired(override val taskId: TaskId, val action: String, val level: String) : AgentEvent(taskId = taskId)
    data class PermissionRequested(override val taskId: TaskId, val action: String, val level: String) : AgentEvent(taskId = taskId)
    data class ToolStarted(override val taskId: TaskId, val toolName: String) : AgentEvent(taskId = taskId)
    data class ToolCompleted(override val taskId: TaskId, val toolName: String, val isSuccess: Boolean) : AgentEvent(taskId = taskId)
    data class ToolFailed(override val taskId: TaskId, val toolName: String, val error: String) : AgentEvent(taskId = taskId)
    data class SecurityBlocked(override val taskId: TaskId, val reason: String) : AgentEvent(taskId = taskId)
    data class EditingFile(override val taskId: TaskId, val filePath: String) : AgentEvent(taskId = taskId)
    data class ExecutionStarted(override val taskId: TaskId, val executable: String) : AgentEvent(taskId = taskId)
    data class ExecutionCompleted(override val taskId: TaskId, val exitCode: Int) : AgentEvent(taskId = taskId)
    data class ObservationReceived(override val taskId: TaskId, val observationSummary: String) : AgentEvent(taskId = taskId)
    data class CompilationFailed(override val taskId: TaskId, val errors: String) : AgentEvent(taskId = taskId)
    data class DiagnosisCreated(override val taskId: TaskId, val category: String, val summary: String) : AgentEvent(taskId = taskId)
    data class CorrectionProposed(override val taskId: TaskId, val proposal: String) : AgentEvent(taskId = taskId)
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
}
