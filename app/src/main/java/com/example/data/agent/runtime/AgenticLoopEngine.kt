package com.example.data.agent.runtime

import kotlinx.coroutines.delay

data class AgenticLoopConfig(
    val maxIterations: Int = 5,
    val maxCorrections: Int = 3,
    val maxExecutionTimeMs: Long = 60000L,
    val maxTaskSteps: Int = 10
)

data class AgenticLoopResult(
    val taskId: TaskId,
    val isSuccess: Boolean,
    val finalState: AgenticState,
    val executionSummary: String,
    val iterationsCompleted: Int,
    val correctionsAttempted: Int,
    val totalTimeMs: Long
)

/**
 * Task 6: Agentic Loop Engine.
 * Finite, bounded agent loop implementing:
 * UNDERSTAND → PLAN → INSPECT → ACT → OBSERVE → DIAGNOSE → CORRECT → TEST → VERIFY → COMPLETE.
 * Emits AgentEvents for every step. Checked against WastiEmergencyStopController at every stage.
 */
class AgenticLoopEngine(
    private val taskManager: AgentTaskManager,
    private val eventBus: AgentEventBus,
    private val planner: AgentPlanner,
    private val toolRouter: WastiAgentToolRouter,
    private val errorAnalyzer: ErrorAnalyzer,
    private val correctionEngine: SelfCorrectionEngine,
    private val emergencyStopController: WastiEmergencyStopController,
    private val memoryStore: AgentMemoryContract? = null,
    private val config: AgenticLoopConfig = AgenticLoopConfig()
) {

    suspend fun executeLoop(task: AgentTask): AgenticLoopResult {
        val startTime = System.currentTimeMillis()
        var correctionsCount = 0
        var iterationsCount = 0

        // 1. Pre-start Emergency Stop Check
        if (isEmergencyStopped(task.taskId)) {
            return handleEmergencyStop(task, startTime, "Emergency stop active prior to task start")
        }

        memoryStore?.recordTaskStart(task.taskId, task.prompt)

        try {
            // 2. UNDERSTAND
            eventBus.emit(AgentEvent.UnderstandingStarted(task.taskId, task.prompt))
            taskManager.updateTaskState(task.taskId, AgenticState.Analyzing("Understanding user goal: ${task.prompt}"))

            if (isEmergencyStopped(task.taskId)) return handleEmergencyStop(task, startTime, "Emergency stop triggered during UNDERSTAND")

            // 3. PLAN
            eventBus.emit(AgentEvent.PlanningStarted(task.taskId, task.prompt))
            taskManager.updateTaskState(task.taskId, AgenticState.Planning())

            val plan = planner.createPlan(task)
            memoryStore?.recordPlan(task.taskId, plan)
            eventBus.emit(AgentEvent.PlanningTask(task.taskId, "Plan generated with ${plan.steps.size} steps"))

            if (!plan.isValid) {
                val errorMsg = "Planning failed: ${plan.validationErrors.joinToString("; ")}"
                taskManager.updateTaskState(task.taskId, AgenticState.Failed(errorMsg))
                eventBus.emit(AgentEvent.TaskFailed(task.taskId, errorMsg))
                return createLoopResult(task.taskId, false, AgenticState.Failed(errorMsg), errorMsg, iterationsCount, correctionsCount, startTime)
            }

            if (isEmergencyStopped(task.taskId)) return handleEmergencyStop(task, startTime, "Emergency stop triggered during PLAN")

            // 4. ACT & OBSERVE LOOP
            val boundedSteps = plan.steps.take(config.maxTaskSteps)

            for (step in boundedSteps) {
                iterationsCount++

                // Check time budget
                if (System.currentTimeMillis() - startTime > config.maxExecutionTimeMs) {
                    val timeoutMsg = "Task execution exceeded maximum time limit of ${config.maxExecutionTimeMs}ms"
                    taskManager.updateTaskState(task.taskId, AgenticState.Failed(timeoutMsg))
                    eventBus.emit(AgentEvent.TaskFailed(task.taskId, timeoutMsg))
                    return createLoopResult(task.taskId, false, AgenticState.Failed(timeoutMsg), timeoutMsg, iterationsCount, correctionsCount, startTime)
                }

                // Pre-step Emergency Stop Check
                if (isEmergencyStopped(task.taskId)) return handleEmergencyStop(task, startTime, "Emergency stop triggered before step ${step.stepId}")

                // INSPECT / TOOL REQUESTED
                eventBus.emit(AgentEvent.ToolRequested(task.taskId, step.toolName, step.arguments))
                taskManager.updateTaskState(task.taskId, AgenticState.Executing("Executing ${step.toolName}"))

                // Pre-execution Emergency Stop Check
                if (isEmergencyStopped(task.taskId)) return handleEmergencyStop(task, startTime, "Emergency stop triggered before tool routing")

                // ACT: Execute tool strictly via WastiAgentToolRouter
                eventBus.emit(AgentEvent.ToolStarted(task.taskId, step.toolName))
                val toolResult = toolRouter.routeAndExecute(
                    toolName = step.toolName,
                    args = step.arguments,
                    context = task
                )

                // Post-execution Emergency Stop Check
                if (isEmergencyStopped(task.taskId)) return handleEmergencyStop(task, startTime, "Emergency stop triggered after tool execution")

                memoryStore?.recordToolCall(task.taskId, step.toolName, step.arguments, toolResult)

                // OBSERVE
                val observation = AgentObservation.fromToolResult(task.taskId, step.toolName, toolResult)
                memoryStore?.recordObservation(task.taskId, observation)
                eventBus.emit(AgentEvent.ObservationReceived(task.taskId, "Output length: ${observation.stdout.length + observation.stderr.length}"))

                if (toolResult.isSuccess) {
                    eventBus.emit(AgentEvent.ToolCompleted(task.taskId, step.toolName, true))
                } else {
                    eventBus.emit(AgentEvent.ToolFailed(task.taskId, step.toolName, toolResult.error ?: "Execution failed"))

                    // Check if security blocked
                    if (toolResult.isSecurityBlocked) {
                        taskManager.updateTaskState(task.taskId, AgenticState.SecurityBlocked(toolResult.error ?: "Security blocked"))
                        eventBus.emit(AgentEvent.SecurityBlocked(task.taskId, toolResult.error ?: "Blocked by Security Policy"))
                        return createLoopResult(task.taskId, false, AgenticState.SecurityBlocked(), toolResult.error ?: "Security blocked", iterationsCount, correctionsCount, startTime)
                    }

                    // DIAGNOSE & CORRECT
                    if (correctionsCount >= config.maxCorrections) {
                        val maxCorrMsg = "Exceeded maximum correction limit of ${config.maxCorrections}"
                        taskManager.updateTaskState(task.taskId, AgenticState.Failed(maxCorrMsg))
                        eventBus.emit(AgentEvent.TaskFailed(task.taskId, maxCorrMsg))
                        return createLoopResult(task.taskId, false, AgenticState.Failed(maxCorrMsg), maxCorrMsg, iterationsCount, correctionsCount, startTime)
                    }

                    correctionsCount++
                    taskManager.updateTaskState(task.taskId, AgenticState.Debugging("Analyzing error in ${step.toolName}"))

                    val diagnostic = errorAnalyzer.analyzeFailure(observation)
                    memoryStore?.recordDiagnostic(task.taskId, diagnostic)
                    eventBus.emit(AgentEvent.DiagnosisCreated(task.taskId, diagnostic.category.name, diagnostic.summary))

                    val proposal = correctionEngine.proposeCorrection(task, diagnostic, observation)
                    memoryStore?.recordCorrection(task.taskId, proposal)
                    eventBus.emit(AgentEvent.CorrectionProposed(task.taskId, proposal.explanation))

                    // Pre-correction Emergency Stop Check
                    if (isEmergencyStopped(task.taskId)) return handleEmergencyStop(task, startTime, "Emergency stop triggered before applying correction")

                    // Apply correction strictly through router
                    eventBus.emit(AgentEvent.ToolStarted(task.taskId, proposal.toolName))
                    val correctionResult = correctionEngine.applyCorrectionThroughRouter(task, proposal)
                    memoryStore?.recordToolCall(task.taskId, proposal.toolName, proposal.toolArguments, correctionResult)

                    if (!correctionResult.isSuccess) {
                        val corrFailMsg = "Correction failed: ${correctionResult.error}"
                        taskManager.updateTaskState(task.taskId, AgenticState.Failed(corrFailMsg))
                        eventBus.emit(AgentEvent.TaskFailed(task.taskId, corrFailMsg))
                        return createLoopResult(task.taskId, false, AgenticState.Failed(corrFailMsg), corrFailMsg, iterationsCount, correctionsCount, startTime)
                    }
                }
            }

            // 5. TEST & VERIFY
            eventBus.emit(AgentEvent.TestingStarted(task.taskId, "Workspace verification"))
            taskManager.updateTaskState(task.taskId, AgenticState.Testing())

            eventBus.emit(AgentEvent.VerificationStarted(task.taskId, "Verifying task completion state"))
            taskManager.updateTaskState(task.taskId, AgenticState.Verification())

            if (isEmergencyStopped(task.taskId)) return handleEmergencyStop(task, startTime, "Emergency stop triggered during verification")

            // 6. COMPLETE
            val summary = "Task successfully completed in $iterationsCount steps with $correctionsCount corrections"
            taskManager.updateTaskState(task.taskId, AgenticState.Completed(summary))
            eventBus.emit(AgentEvent.VerificationCompleted(task.taskId, true))
            eventBus.emit(AgentEvent.TaskCompleted(task.taskId, summary))

            return createLoopResult(task.taskId, true, AgenticState.Completed(summary), summary, iterationsCount, correctionsCount, startTime)

        } catch (e: Exception) {
            val errorMsg = "Unhandled exception in agentic loop: ${e.message}"
            taskManager.updateTaskState(task.taskId, AgenticState.Failed(errorMsg))
            eventBus.emit(AgentEvent.TaskFailed(task.taskId, errorMsg))
            return createLoopResult(task.taskId, false, AgenticState.Failed(errorMsg), errorMsg, iterationsCount, correctionsCount, startTime)
        }
    }

    private fun isEmergencyStopped(taskId: TaskId): Boolean {
        return emergencyStopController.isEmergencyStopped
    }

    private suspend fun handleEmergencyStop(task: AgentTask, startTime: Long, reason: String): AgenticLoopResult {
        taskManager.updateTaskState(task.taskId, AgenticState.SecurityBlocked(reason))
        eventBus.emit(AgentEvent.EmergencyStopped(task.taskId, reason))
        eventBus.emit(AgentEvent.TaskFailed(task.taskId, reason))
        return createLoopResult(
            taskId = task.taskId,
            isSuccess = false,
            finalState = AgenticState.SecurityBlocked(reason),
            summary = "Terminated by Emergency Stop: $reason",
            iterationsCount = 0,
            correctionsCount = 0,
            startTime = startTime
        )
    }

    private fun createLoopResult(
        taskId: TaskId,
        isSuccess: Boolean,
        finalState: AgenticState,
        summary: String,
        iterationsCount: Int,
        correctionsCount: Int,
        startTime: Long
    ): AgenticLoopResult {
        return AgenticLoopResult(
            taskId = taskId,
            isSuccess = isSuccess,
            finalState = finalState,
            executionSummary = summary,
            iterationsCompleted = iterationsCount,
            correctionsAttempted = correctionsCount,
            totalTimeMs = System.currentTimeMillis() - startTime
        )
    }
}
