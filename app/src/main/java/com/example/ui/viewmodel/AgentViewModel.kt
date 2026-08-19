package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.agent.runtime.*
import com.example.data.db.AgentEntity
import com.example.data.di.WastiServiceLocator
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * Specialized ViewModel for Agents, Autonomous Tasks, and Multi-Agent Orchestration.
 * Awaken the sleeping giant by connecting to WastiAgentRuntimeImpl and AgentEventBus.
 */
class AgentViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = WastiServiceLocator.repository
    private val agentRuntime = WastiServiceLocator.agentRuntime
    private val eventBus = WastiServiceLocator.agentEventBus

    val agents: StateFlow<List<AgentEntity>> = repository.agents
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activeAgentTasks = MutableStateFlow<List<AgentTask>>(emptyList())
    val lastTaskResult = MutableStateFlow<AgenticLoopResult?>(null)
    val isAgentRunning = MutableStateFlow(false)

    val eventStream: Flow<AgentEvent> = eventBus.events

    init {
        refreshTasks()
        viewModelScope.launch {
            eventBus.events.collect { _ ->
                refreshTasks()
            }
        }
    }

    fun refreshTasks() {
        activeAgentTasks.value = agentRuntime.getAllTasks()
    }

    fun submitAgentTask(prompt: String, executionMode: ExecutionMode = ExecutionMode.AUTONOMOUS) {
        if (prompt.isBlank()) return
        viewModelScope.launch {
            try {
                isAgentRunning.value = true
                val taskRes = agentRuntime.submitTaskResult(prompt, executionMode)
                taskRes.onSuccess { task ->
                    refreshTasks()
                    val loopResult = agentRuntime.executeTask(task.taskId)
                    lastTaskResult.value = loopResult
                }
            } catch (e: Exception) {
                android.util.Log.e("AgentViewModel", "Task execution error", e)
            } finally {
                isAgentRunning.value = false
                refreshTasks()
            }
        }
    }

    fun cancelTask(taskId: TaskId, reason: String = "Cancelled by user") {
        agentRuntime.cancelTask(taskId, reason)
        refreshTasks()
    }

    fun triggerEmergencyStop(reason: String = "User triggered emergency stop") {
        agentRuntime.triggerEmergencyStop(reason)
        refreshTasks()
    }

    fun addAgent(name: String, roleTitle: String, agentType: String, systemInstruction: String, capabilities: String) {
        viewModelScope.launch {
            try {
                repository.addAgent(name, roleTitle, agentType, systemInstruction, capabilities)
            } catch (e: Exception) {
                android.util.Log.e("AgentViewModel", "Add agent failed", e)
            }
        }
    }
}
