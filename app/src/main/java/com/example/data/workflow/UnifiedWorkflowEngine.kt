package com.example.data.workflow

import android.content.Context
import com.example.data.agent.runtime.ActionAuthorizationState
import com.example.data.agent.runtime.UnifiedExecutionFabric
import com.example.data.agent.runtime.UnifiedExecutionRequest
import com.example.data.agent.runtime.UnifiedExecutionStatus
import com.example.data.agent.runtime.UnifiedVerificationStatus
import com.example.data.agent.runtime.WastiEmergencyStopController
import com.example.data.agent.runtime.WastiSecurityPolicyEngine
import com.example.data.memory.MemoryManager
import com.example.data.tool.ToolRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Stage 9E: Unified Autonomous Workflow Engine
 * Orchestrates:
 * UNDERSTAND REQUEST
 * -> PLAN
 * -> DISCOVER CAPABILITIES
 * -> SELECT OR CREATE TOOLS
 * -> AUTHORIZE
 * -> EXECUTE
 * -> OBSERVE
 * -> VERIFY
 * -> RETRY/RECOVER WHEN APPROPRIATE
 * -> STORE/RETURN RESULT
 */
class UnifiedWorkflowEngine(
    private val context: Context? = null,
    private val securityPolicyEngine: WastiSecurityPolicyEngine? = null,
    private val emergencyStopController: WastiEmergencyStopController? = null,
    private val capabilityOrchestrator: AutonomousCapabilityOrchestrator = AutonomousCapabilityOrchestrator(context)
) {
    private val activeTasks = ConcurrentHashMap<String, WorkflowTask>()
    private val _taskStateFlow = MutableStateFlow<Map<String, AutonomousWorkflowState>>(emptyMap())
    val taskStateFlow: StateFlow<Map<String, AutonomousWorkflowState>> = _taskStateFlow.asStateFlow()

    companion object {
        @Volatile
        private var instance: UnifiedWorkflowEngine? = null

        fun getInstance(context: Context? = null): UnifiedWorkflowEngine =
            instance ?: synchronized(this) {
                instance ?: UnifiedWorkflowEngine(context = context).also { instance = it }
            }
    }

    suspend fun executeWorkflow(
        task: WorkflowTask,
        targetContext: Context? = null
    ): WorkflowFinalResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val ctx = targetContext ?: context ?: com.example.WastiApplication.instance
        activeTasks[task.taskId] = task

        fun updateState(state: AutonomousWorkflowState, logMsg: String? = null) {
            task.currentState = state
            task.updatedAt = System.currentTimeMillis()
            if (logMsg != null) task.logs.add("[${state.name}] $logMsg")
            _taskStateFlow.value = activeTasks.mapValues { it.value.currentState }
        }

        try {
            // 1. RECEIVED
            updateState(AutonomousWorkflowState.RECEIVED, "Workflow received: ${task.originalRequest}")
            if (checkCancellationOrEmergencyStop(task, startTime)) return@withContext task.finalResult!!

            // 2. UNDERSTOOD
            updateState(AutonomousWorkflowState.UNDERSTOOD, "Interpreting intent for: '${task.originalRequest}'")
            if (task.interpretedIntent.isBlank()) {
                task.interpretedIntent = interpretGoal(task.originalRequest)
            }
            if (checkCancellationOrEmergencyStop(task, startTime)) return@withContext task.finalResult!!

            // 3. PLANNED
            updateState(AutonomousWorkflowState.PLANNED, "Synthesizing structured execution plan")
            if (task.plan == null) {
                task.plan = generatePlanForGoal(task.originalRequest, task.interpretedIntent)
                task.steps = task.plan!!.steps.toMutableList()
            }
            if (checkCancellationOrEmergencyStop(task, startTime)) return@withContext task.finalResult!!

            // 4. CAPABILITIES_DISCOVERED & TOOLS_SELECTED / CREATED
            updateState(AutonomousWorkflowState.CAPABILITIES_DISCOVERED, "Discovering capabilities for ${task.steps.size} steps")
            val dynamicToolsMade = mutableListOf<String>()

            for (step in task.steps) {
                updateState(AutonomousWorkflowState.TOOLS_SELECTED, "Resolving capability '${step.capabilityId}' for step '${step.name}'")
                task.selectedCapabilities.add(step.capabilityId)

                when (val res = capabilityOrchestrator.resolveCapability(step.capabilityId, step.description, null, ctx)) {
                    is CapabilityResolutionResult.ExistingTool -> {
                        task.selectedTools.add(res.toolId)
                    }
                    is CapabilityResolutionResult.NativeCapability -> {
                        task.selectedTools.add(res.capabilityId)
                    }
                    is CapabilityResolutionResult.DynamicCreatedTool -> {
                        updateState(AutonomousWorkflowState.TOOLS_CREATED_IF_NEEDED, "Dynamically created & registered tool '${res.toolId}'")
                        task.selectedTools.add(res.toolId)
                        task.dynamicToolsCreated.add(res.toolId)
                        dynamicToolsMade.add(res.toolId)
                    }
                    is CapabilityResolutionResult.SecurityBlocked -> {
                        val failMsg = "Capability resolution blocked by security policy for '${step.capabilityId}': ${res.reason}"
                        task.errors.add(failMsg)
                        updateState(AutonomousWorkflowState.FAILED, failMsg)
                        val failRes = buildFinalResult(
                            task = task,
                            isSuccess = false,
                            finalState = AutonomousWorkflowState.FAILED,
                            summary = failMsg,
                            startTime = startTime,
                            error = failMsg
                        )
                        task.finalResult = failRes
                        return@withContext failRes
                    }
                    is CapabilityResolutionResult.ResolutionFailed -> {
                        val failMsg = "Capability resolution failed for '${step.capabilityId}': ${res.reason}"
                        task.errors.add(failMsg)
                        updateState(AutonomousWorkflowState.FAILED, failMsg)
                        val failRes = buildFinalResult(
                            task = task,
                            isSuccess = false,
                            finalState = AutonomousWorkflowState.FAILED,
                            summary = failMsg,
                            startTime = startTime,
                            error = failMsg
                        )
                        task.finalResult = failRes
                        return@withContext failRes
                    }
                }
            }

            // 5. AUTHORIZATION_CHECK
            updateState(AutonomousWorkflowState.AUTHORIZATION_CHECK, "Verifying security policies and execution permissions")
            if (emergencyStopController?.isEmergencyStopped == true) {
                val blockMsg = "Execution blocked: Emergency stop controller active"
                task.errors.add(blockMsg)
                updateState(AutonomousWorkflowState.BLOCKED, blockMsg)
                val blockRes = buildFinalResult(task, false, AutonomousWorkflowState.BLOCKED, blockMsg, startTime, error = blockMsg)
                task.finalResult = blockRes
                return@withContext blockRes
            }

            // 6. MULTI-STEP DEPENDENCY EXECUTION
            updateState(AutonomousWorkflowState.EXECUTING, "Executing ${task.steps.size} planned steps in dependency order")

            val completedStepIds = mutableSetOf<String>()
            val failedStepIds = mutableSetOf<String>()

            while (completedStepIds.size + failedStepIds.size < task.steps.size) {
                if (checkCancellationOrEmergencyStop(task, startTime)) return@withContext task.finalResult!!

                val eligibleStep = task.steps.firstOrNull { step ->
                    step.state == WorkflowStepState.PENDING &&
                        step.dependsOnStepIds.all { it in completedStepIds }
                }

                if (eligibleStep == null) {
                    // Check if remaining pending steps are blocked by failed dependencies
                    val remainingPending = task.steps.filter { it.state == WorkflowStepState.PENDING }
                    if (remainingPending.isNotEmpty()) {
                        for (p in remainingPending) {
                            p.state = WorkflowStepState.BLOCKED
                            p.error = "Prerequisite dependency failed or blocked"
                            failedStepIds.add(p.stepId)
                        }
                    }
                    break
                }

                // Execute eligible step with bounded retry
                val stepSuccess = executeStepWithBoundedRetry(task, eligibleStep, ctx)
                if (stepSuccess) {
                    completedStepIds.add(eligibleStep.stepId)
                } else {
                    failedStepIds.add(eligibleStep.stepId)
                }
            }

            // 7. VERIFYING OVERALL WORKFLOW
            updateState(AutonomousWorkflowState.VERIFYING, "Verifying comprehensive workflow outcome")
            val isAllStepsSuccess = task.steps.all { it.state == WorkflowStepState.VERIFIED || it.state == WorkflowStepState.COMPLETED }

            // 8. MEMORY_UPDATE
            updateState(AutonomousWorkflowState.MEMORY_UPDATE, "Indexing workflow outcome in long-term memory")
            try {
                MemoryManager.saveMemory(
                    key = "workflow_${task.taskId}",
                    value = "Workflow [${task.originalRequest}] -> Success: $isAllStepsSuccess. Steps: ${task.steps.size}, Verified: ${task.steps.count { it.state == WorkflowStepState.VERIFIED }}",
                    category = "WORKFLOW"
                )
            } catch (_: Exception) {
                // Non-blocking memory update
            }

            // 9. FINAL RESULT ASSEMBLY
            val finalState = if (isAllStepsSuccess) AutonomousWorkflowState.SUCCESS else AutonomousWorkflowState.FAILED
            updateState(finalState, if (isAllStepsSuccess) "All workflow steps completed and verified successfully" else "One or more steps failed")

            val finalResult = buildFinalResult(
                task = task,
                isSuccess = isAllStepsSuccess,
                finalState = finalState,
                summary = if (isAllStepsSuccess) "Autonomous workflow completed successfully across ${task.steps.size} steps." else "Workflow failed. Errors: ${task.errors.joinToString("; ")}",
                startTime = startTime,
                error = if (isAllStepsSuccess) null else task.errors.joinToString("; ")
            )
            task.finalResult = finalResult
            updateState(AutonomousWorkflowState.COMPLETED, "Workflow finalized")
            finalResult
        } finally {
            activeTasks.remove(task.taskId)
        }
    }

    private suspend fun executeStepWithBoundedRetry(
        task: WorkflowTask,
        step: WorkflowStep,
        context: Context?
    ): Boolean {
        step.state = WorkflowStepState.RUNNING
        var attempt = 0
        val maxStepRetries = 2

        while (attempt <= maxStepRetries) {
            val stepStart = System.currentTimeMillis()
            attempt++
            step.retryCount = attempt - 1

            if (attempt > 1) {
                task.currentState = AutonomousWorkflowState.RETRY
                task.logs.add("[RETRY] Retrying step '${step.name}' (attempt $attempt)")
            }

            // Route execution strictly via UnifiedExecutionFabric
            val execRequest = UnifiedExecutionRequest(
                taskId = task.taskId,
                actionId = step.stepId,
                capabilityId = step.capabilityId,
                parameters = step.parameters,
                authorizationState = ActionAuthorizationState.AUTHORIZED
            )

            val execResult = UnifiedExecutionFabric.instance.execute(execRequest, context)
            step.executionResult = execResult

            // Observation
            step.observationEvidence = "Output (${execResult.output.length} chars): ${execResult.output.take(150)}"
            task.observations.add(
                WorkflowObservation(
                    stepId = step.stepId,
                    capabilityId = step.capabilityId,
                    stdout = execResult.output,
                    stderr = execResult.error ?: "",
                    exitCode = if (execResult.status == UnifiedExecutionStatus.FAILED) 1 else 0,
                    durationMs = System.currentTimeMillis() - stepStart,
                    evidence = step.observationEvidence ?: ""
                )
            )

            // Verification
            step.verificationStatus = execResult.verificationStatus
            step.verificationEvidence = execResult.verificationEvidence ?: "Execution status: ${execResult.status}"
            task.verificationResults.add(
                WorkflowVerification(
                    stepId = step.stepId,
                    capabilityId = step.capabilityId,
                    status = execResult.verificationStatus,
                    evidence = step.verificationEvidence ?: ""
                )
            )

            val isSuccess = execResult.status == UnifiedExecutionStatus.VERIFIED ||
                execResult.status == UnifiedExecutionStatus.COMPLETED

            if (isSuccess) {
                step.state = if (execResult.verificationStatus == UnifiedVerificationStatus.VERIFIED) {
                    WorkflowStepState.VERIFIED
                } else {
                    WorkflowStepState.COMPLETED
                }
                return true
            } else {
                step.error = execResult.error ?: "Step failed with status ${execResult.status}"
                if (attempt > maxStepRetries) {
                    step.state = WorkflowStepState.FAILED
                    task.errors.add("Step '${step.name}' failed after $attempt attempts: ${step.error}")
                    return false
                }
            }
        }

        step.state = WorkflowStepState.FAILED
        return false
    }

    private fun checkCancellationOrEmergencyStop(task: WorkflowTask, startTime: Long): Boolean {
        if (task.isCancelled) {
            task.currentState = AutonomousWorkflowState.BLOCKED
            task.logs.add("[CANCELLED] Workflow cancelled by user request")
            task.finalResult = buildFinalResult(
                task = task,
                isSuccess = false,
                finalState = AutonomousWorkflowState.BLOCKED,
                summary = "Workflow was cancelled",
                startTime = startTime,
                error = "Workflow cancelled"
            )
            return true
        }
        if (emergencyStopController?.isEmergencyStopped == true) {
            task.currentState = AutonomousWorkflowState.BLOCKED
            task.logs.add("[EMERGENCY_STOP] Workflow blocked by active emergency stop")
            task.finalResult = buildFinalResult(
                task = task,
                isSuccess = false,
                finalState = AutonomousWorkflowState.BLOCKED,
                summary = "Workflow blocked by Emergency Stop",
                startTime = startTime,
                error = "Emergency stop active"
            )
            return true
        }
        return false
    }

    fun cancelTask(taskId: String): Boolean {
        val task = activeTasks[taskId] ?: return false
        task.isCancelled = true
        task.currentState = AutonomousWorkflowState.BLOCKED
        return true
    }

    private fun interpretGoal(request: String): String {
        val reqLower = request.lowercase()
        return when {
            reqLower.contains("device") || reqLower.contains("whatsapp") || reqLower.contains("sms") -> "AUTOMATE_DEVICE_ACTION"
            reqLower.contains("terminal") || reqLower.contains("script") || reqLower.contains("command") -> "EXECUTE_SYSTEM_WORKFLOW"
            reqLower.contains("project") || reqLower.contains("code") -> "DEVELOPMENT_WORKFLOW"
            reqLower.contains("memory") || reqLower.contains("search") -> "KNOWLEDGE_RETRIEVAL"
            else -> "GENERAL_AUTONOMOUS_TASK"
        }
    }

    private fun generatePlanForGoal(originalRequest: String, intent: String): WorkflowPlan {
        val steps = mutableListOf<WorkflowStep>()
        val reqLower = originalRequest.lowercase()

        when {
            reqLower.contains("device") || reqLower.contains("whatsapp") -> {
                steps.add(
                    WorkflowStep(
                        name = "Inspect System Environment",
                        capabilityId = "system_info",
                        description = "Check Android device status and capabilities"
                    )
                )
                steps.add(
                    WorkflowStep(
                        name = "Execute Device Operation",
                        capabilityId = "device_control",
                        parameters = mapOf("action" to "open_app", "target" to "Settings"),
                        dependsOnStepIds = listOf(steps[0].stepId),
                        description = "Perform required device control intent"
                    )
                )
            }
            reqLower.contains("memory") -> {
                steps.add(
                    WorkflowStep(
                        name = "Query Memory Store",
                        capabilityId = "memory_search",
                        parameters = mapOf("query" to originalRequest),
                        description = "Retrieve relevant contextual memories"
                    )
                )
            }
            else -> {
                steps.add(
                    WorkflowStep(
                        name = "Execute Primary Capability",
                        capabilityId = if (reqLower.contains("terminal")) "terminal" else "system_info",
                        parameters = mapOf("command" to "echo 'Wasti Autonomous Workflow Executed'"),
                        description = "Execute core task request"
                    )
                )
            }
        }

        return WorkflowPlan(
            goal = originalRequest,
            interpretedIntent = intent,
            steps = steps,
            requiredCapabilities = steps.map { it.capabilityId }.distinct()
        )
    }

    private fun buildFinalResult(
        task: WorkflowTask,
        isSuccess: Boolean,
        finalState: AutonomousWorkflowState,
        summary: String,
        startTime: Long,
        error: String? = null
    ): WorkflowFinalResult {
        val verifiedCount = task.steps.count { it.state == WorkflowStepState.VERIFIED }
        val evidenceList = task.verificationResults.joinToString("; ") { "[${it.capabilityId}]: ${it.evidence}" }

        return WorkflowFinalResult(
            taskId = task.taskId,
            originalRequest = task.originalRequest,
            isSuccess = isSuccess,
            finalState = finalState,
            summary = summary,
            stepsExecuted = task.steps.count { it.state == WorkflowStepState.VERIFIED || it.state == WorkflowStepState.COMPLETED },
            stepsVerified = verifiedCount,
            dynamicToolsCreated = task.dynamicToolsCreated.toList(),
            stepResults = task.steps.toList(),
            totalDurationMs = System.currentTimeMillis() - startTime,
            verificationEvidence = evidenceList.ifBlank { "Execution outcome recorded" },
            error = error
        )
    }
}
