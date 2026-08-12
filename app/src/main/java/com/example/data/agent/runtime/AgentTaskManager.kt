package com.example.data.agent.runtime

import java.util.concurrent.ConcurrentHashMap

/**
 * Task 2: Agent Task Manager.
 * Tracks active, completed, failed, and cancelled tasks.
 * Validates state transitions to prevent invalid lifecycle progressions.
 */
class AgentTaskManager {

    private val tasks = ConcurrentHashMap<String, AgentTask>()

    fun createTask(prompt: String, mode: ExecutionMode = ExecutionMode.SAFE): AgentTask {
        val task = AgentTask(
            taskId = TaskId(),
            prompt = prompt,
            status = AgenticState.Idle(),
            executionMode = mode
        )
        tasks[task.taskId.value] = task
        return task
    }

    fun registerTask(task: AgentTask) {
        tasks[task.taskId.value] = task
    }

    fun getTask(taskId: TaskId): AgentTask? {
        return tasks[taskId.value]
    }

    fun getAllTasks(): List<AgentTask> {
        return tasks.values.toList()
    }

    fun getActiveTasks(): List<AgentTask> {
        return tasks.values.filter { isStateActive(it.status) }
    }

    fun getCompletedTasks(): List<AgentTask> {
        return tasks.values.filter { it.status is AgenticState.Completed }
    }

    fun getFailedTasks(): List<AgentTask> {
        return tasks.values.filter { it.status is AgenticState.Failed || it.status is AgenticState.SecurityBlocked }
    }

    fun getCancelledTasks(): List<AgentTask> {
        return tasks.values.filter { it.status is AgenticState.Cancelled || it.cancellationState.isCancelled }
    }

    @Synchronized
    fun updateTaskState(taskId: TaskId, newStatus: AgenticState): Result<AgentTask> {
        val currentTask = tasks[taskId.value]
            ?: return Result.failure(IllegalArgumentException("Task $taskId not found"))

        if (!isValidTransition(currentTask.status, newStatus)) {
            return Result.failure(
                IllegalStateException(
                    "Invalid state transition from '${currentTask.status::class.simpleName}' to '${newStatus::class.simpleName}' for task ${taskId.value}"
                )
            )
        }

        val updatedTask = currentTask.copy(status = newStatus)
        tasks[taskId.value] = updatedTask
        return Result.success(updatedTask)
    }

    @Synchronized
    fun cancelTask(taskId: TaskId, reason: String = "Cancelled by user"): Result<AgentTask> {
        val currentTask = tasks[taskId.value]
            ?: return Result.failure(IllegalArgumentException("Task $taskId not found"))

        if (isStateTerminal(currentTask.status)) {
            return Result.failure(IllegalStateException("Cannot cancel task in terminal state '${currentTask.status::class.simpleName}'"))
        }

        val cancelledTask = currentTask.copy(
            status = AgenticState.Cancelled(reason),
            cancellationState = TaskCancellationState(
                isCancelled = true,
                cancellationReason = reason,
                cancelledAt = System.currentTimeMillis()
            )
        )
        tasks[taskId.value] = cancelledTask
        return Result.success(cancelledTask)
    }

    private fun isStateActive(state: AgenticState): Boolean {
        return !isStateTerminal(state)
    }

    private fun isStateTerminal(state: AgenticState): Boolean {
        return state is AgenticState.Completed ||
                state is AgenticState.Failed ||
                state is AgenticState.SecurityBlocked ||
                state is AgenticState.Cancelled
    }

    private fun isValidTransition(from: AgenticState, to: AgenticState): Boolean {
        // Any state can transition to Cancelled or SecurityBlocked or Emergency/Failed
        if (to is AgenticState.Cancelled ||
            to is AgenticState.SecurityBlocked ||
            to is AgenticState.Failed
        ) {
            return true
        }

        // Terminal states cannot transition to non-terminal states
        if (isStateTerminal(from) && !isStateTerminal(to)) {
            return false
        }

        return when (from) {
            is AgenticState.Idle -> to is AgenticState.Analyzing || to is AgenticState.Planning || to is AgenticState.Executing || to is AgenticState.Completed
            is AgenticState.Analyzing -> to is AgenticState.Planning || to is AgenticState.Inspecting || to is AgenticState.Executing
            is AgenticState.Planning -> to is AgenticState.Inspecting || to is AgenticState.Executing || to is AgenticState.WaitingForPermission
            is AgenticState.Inspecting -> to is AgenticState.Executing || to is AgenticState.Editing || to is AgenticState.Planning
            is AgenticState.WaitingForPermission -> to is AgenticState.Executing || to is AgenticState.Editing || to is AgenticState.Cancelled
            is AgenticState.Editing -> to is AgenticState.Executing || to is AgenticState.Testing || to is AgenticState.Observing
            is AgenticState.Executing -> to is AgenticState.Observing || to is AgenticState.Debugging || to is AgenticState.Testing || to is AgenticState.Editing || to is AgenticState.Verification || to is AgenticState.Completed
            is AgenticState.Observing -> to is AgenticState.Debugging || to is AgenticState.Testing || to is AgenticState.Editing || to is AgenticState.Verification || to is AgenticState.Completed
            is AgenticState.Debugging -> to is AgenticState.Editing || to is AgenticState.Executing || to is AgenticState.Failed
            is AgenticState.Testing -> to is AgenticState.Verification || to is AgenticState.Debugging || to is AgenticState.Editing
            is AgenticState.Verification -> to is AgenticState.Completed || to is AgenticState.Debugging || to is AgenticState.Editing
            is AgenticState.Completed,
            is AgenticState.Failed,
            is AgenticState.SecurityBlocked,
            is AgenticState.Cancelled,
            is AgenticState.RolledBack -> false
        }
    }
}
