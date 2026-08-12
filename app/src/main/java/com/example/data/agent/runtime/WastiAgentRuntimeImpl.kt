package com.example.data.agent.runtime

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Task 1: Wasti Agent Runtime Implementation.
 * Central orchestrator. UI-independent.
 * Rejects new tasks while emergency stop is active.
 * Delegates EVERY tool call strictly through WastiAgentToolRouter.
 */
class WastiAgentRuntimeImpl(
    private val taskManager: AgentTaskManager,
    private val eventBus: AgentEventBus,
    private val loopEngine: AgenticLoopEngine,
    private val emergencyStopController: WastiEmergencyStopController,
    private val toolRouter: WastiAgentToolRouter
) : WastiAgentRuntime {

    override val events: Flow<AgentEvent> = eventBus.events
    override val eventStream: SharedFlow<AgentEvent> = eventBus.events

    override fun submitTask(prompt: String, executionMode: ExecutionMode): TaskId {
        val res = submitTaskResult(prompt, executionMode)
        return res.getOrThrow().taskId
    }

    override fun submitTaskResult(prompt: String, executionMode: ExecutionMode): Result<AgentTask> {
        // Reject new tasks if emergency stop is active
        if (emergencyStopController.isEmergencyStopped) {
            return Result.failure(
                IllegalStateException("EMERGENCY_STOP_ACTIVE: New tasks are rejected while emergency stop is active.")
            )
        }

        val task = taskManager.createTask(prompt, executionMode)
        eventBus.tryEmit(AgentEvent.TaskCreated(task.taskId, prompt))
        return Result.success(task)
    }

    override suspend fun executeTask(taskId: TaskId): AgenticLoopResult {
        val task = taskManager.getTask(taskId)
            ?: throw IllegalArgumentException("Task $taskId not found")

        if (emergencyStopController.isEmergencyStopped) {
            val errorReason = "Emergency stop active prior to task execution"
            taskManager.updateTaskState(taskId, AgenticState.SecurityBlocked(errorReason))
            eventBus.emit(AgentEvent.EmergencyStopped(taskId, errorReason))
            return AgenticLoopResult(
                taskId = taskId,
                isSuccess = false,
                finalState = AgenticState.SecurityBlocked(errorReason),
                executionSummary = errorReason,
                iterationsCompleted = 0,
                correctionsAttempted = 0,
                totalTimeMs = 0L
            )
        }

        return loopEngine.executeLoop(task)
    }

    override fun cancelTask(taskId: TaskId, reason: String): Result<AgentTask> {
        val result = taskManager.cancelTask(taskId, reason)
        if (result.isSuccess) {
            eventBus.tryEmit(AgentEvent.TaskCancelled(taskId, reason))
        }
        return result
    }

    override fun triggerEmergencyStop(reason: String) {
        emergencyStopController.triggerEmergencyStop(reason)
        // Emit emergency stop event for active tasks
        taskManager.getActiveTasks().forEach { activeTask ->
            taskManager.cancelTask(activeTask.taskId, "Terminated by Emergency Stop: $reason")
            eventBus.tryEmit(AgentEvent.EmergencyStopped(activeTask.taskId, reason))
        }
    }

    override fun getTask(taskId: TaskId): AgentTask? {
        return taskManager.getTask(taskId)
    }

    override fun getAllTasks(): List<AgentTask> {
        return taskManager.getAllTasks()
    }
}
