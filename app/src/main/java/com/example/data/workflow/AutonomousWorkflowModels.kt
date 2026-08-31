package com.example.data.workflow

import com.example.data.agent.runtime.UnifiedExecutionResult
import com.example.data.agent.runtime.UnifiedExecutionStatus
import com.example.data.agent.runtime.UnifiedVerificationStatus
import java.util.UUID

/**
 * Stage 9E: Unified Autonomous Workflow States
 */
enum class AutonomousWorkflowState {
    RECEIVED,
    UNDERSTOOD,
    PLANNED,
    CAPABILITIES_DISCOVERED,
    TOOLS_SELECTED,
    TOOLS_CREATED_IF_NEEDED,
    AUTHORIZATION_CHECK,
    EXECUTING,
    OBSERVING,
    VERIFYING,
    RETRY,
    RECOVER,
    BLOCKED,
    FAILED,
    MEMORY_UPDATE,
    SUCCESS,
    COMPLETED
}

enum class WorkflowStepState {
    PENDING,
    READY,
    RUNNING,
    OBSERVING,
    VERIFYING,
    RETRYING,
    COMPLETED,
    VERIFIED,
    FAILED,
    BLOCKED,
    CANCELLED,
    SKIPPED
}

enum class WorkflowJobStatus {
    QUEUED,
    RUNNING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED
}

// TODO: unused — evaluate for removal or wiring in
enum class CapabilityResolutionStrategy {
    USE_EXISTING_TOOL,
    CREATE_DYNAMIC_WRE_TOOL,
    DELEGATE_TO_NATIVE_PROVIDER,
    UNAVAILABLE
}

data class WorkflowStep(
    val stepId: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String = "",
    val capabilityId: String,
    val toolId: String? = null,
    val parameters: Map<String, Any> = emptyMap(),
    val dependsOnStepIds: List<String> = emptyList(),
    val requiresVerification: Boolean = true,
    var state: WorkflowStepState = WorkflowStepState.PENDING,
    var executionResult: UnifiedExecutionResult? = null,
    var observationEvidence: String? = null,
    var verificationStatus: UnifiedVerificationStatus = UnifiedVerificationStatus.NOT_APPLICABLE,
    var verificationEvidence: String? = null,
    var retryCount: Int = 0,
    var error: String? = null
)

data class WorkflowPlan(
    val planId: String = UUID.randomUUID().toString(),
    val goal: String,
    val interpretedIntent: String,
    val steps: List<WorkflowStep>,
    val requiredCapabilities: List<String>,
    val createdAt: Long = System.currentTimeMillis()
)

data class WorkflowObservation(
    val stepId: String,
    val capabilityId: String,
    val stdout: String,
    val stderr: String,
    val exitCode: Int? = null,
    val durationMs: Long,
    val evidence: String,
    val observedAt: Long = System.currentTimeMillis()
)

data class WorkflowVerification(
    val stepId: String,
    val capabilityId: String,
    val status: UnifiedVerificationStatus,
    val evidence: String,
    val verifiedAt: Long = System.currentTimeMillis()
)

data class WorkflowFinalResult(
    val taskId: String,
    val originalRequest: String,
    val isSuccess: Boolean,
    val finalState: AutonomousWorkflowState,
    val summary: String,
    val stepsExecuted: Int,
    val stepsVerified: Int,
    val dynamicToolsCreated: List<String> = emptyList(),
    val stepResults: List<WorkflowStep> = emptyList(),
    val totalDurationMs: Long,
    val verificationEvidence: String? = null,
    val error: String? = null
)

data class WorkflowTask(
    val taskId: String = UUID.randomUUID().toString(),
    val originalRequest: String,
    var interpretedIntent: String = "",
    var currentState: AutonomousWorkflowState = AutonomousWorkflowState.RECEIVED,
    var plan: WorkflowPlan? = null,
    var steps: MutableList<WorkflowStep> = mutableListOf(),
    val selectedCapabilities: MutableList<String> = mutableListOf(),
    val selectedTools: MutableList<String> = mutableListOf(),
    val dynamicToolsCreated: MutableList<String> = mutableListOf(),
    val observations: MutableList<WorkflowObservation> = mutableListOf(),
    val verificationResults: MutableList<WorkflowVerification> = mutableListOf(),
    val errors: MutableList<String> = mutableListOf(),
    val logs: MutableList<String> = mutableListOf(),
    var retryCount: Int = 0,
    val maxRetries: Int = 3,
    var isCancelled: Boolean = false,
    var finalResult: WorkflowFinalResult? = null,
    val createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis()
)

data class WorkflowJob(
    val jobId: String = UUID.randomUUID().toString(),
    val taskId: String,
    val name: String,
    var status: WorkflowJobStatus = WorkflowJobStatus.QUEUED,
    var progress: Float = 0.0f,
    val logs: MutableList<String> = mutableListOf(),
    var result: WorkflowFinalResult? = null,
    val startedAt: Long = System.currentTimeMillis(),
    var completedAt: Long? = null
)
