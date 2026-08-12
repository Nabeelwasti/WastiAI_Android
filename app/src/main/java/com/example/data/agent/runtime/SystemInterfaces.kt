package com.example.data.agent.runtime

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow

interface CapabilityRegistry {
    fun getSupportedCapabilities(): List<String>
    fun isCapabilityEnabled(capabilityName: String): Boolean
}

interface AgentToolRouter {
    suspend fun resolveTool(
        task: AgentTask,
        requestedAction: String
    ): AgentTool?
}

interface EmergencyStopController {
    val isEmergencyStopped: Boolean
    fun triggerEmergencyStop(reason: String)
    fun resetEmergencyStop()
}

interface WastiAgentRuntime {
    val events: Flow<AgentEvent>
    val eventStream: SharedFlow<AgentEvent>
    fun submitTask(prompt: String, executionMode: ExecutionMode = ExecutionMode.SAFE): TaskId
    fun submitTaskResult(prompt: String, executionMode: ExecutionMode = ExecutionMode.SAFE): Result<AgentTask>
    suspend fun executeTask(taskId: TaskId): AgenticLoopResult
    fun cancelTask(taskId: TaskId, reason: String = "User requested cancellation"): Result<AgentTask>
    fun triggerEmergencyStop(reason: String = "Emergency stop triggered")
    fun getTask(taskId: TaskId): AgentTask?
    fun getAllTasks(): List<AgentTask>
}

