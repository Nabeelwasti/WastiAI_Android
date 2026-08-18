package com.example.data.workflow

import com.example.data.bus.WastiEvent
import com.example.data.bus.WastiEventBus
import com.example.data.tool.ToolRegistry
import com.example.data.worker.BackgroundTaskManager
import com.example.data.worker.TaskType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class TriggerType {
    ON_EVENT,
    ON_SCHEDULE,
    ON_MEMORY_STORED,
    ON_VOICE_SESSION,
    MANUAL
}

enum class ActionType {
    RUN_TOOL,
    SCHEDULE_TASK,
    EMIT_ALERT,
    STORE_MEMORY,
    NOTIFICATION,
    AUTONOMOUS_WORKFLOW
}

data class WorkflowTrigger(
    val type: TriggerType,
    val targetEventName: String? = null,
    val conditionKey: String? = null,
    val conditionValue: String? = null
)

data class WorkflowAction(
    val type: ActionType,
    val targetId: String, // tool ID, task name, etc.
    val payloadParameters: Map<String, Any> = emptyMap()
)

data class WorkflowRule(
    val id: String,
    val name: String,
    val description: String,
    val trigger: WorkflowTrigger,
    val primaryAction: WorkflowAction,
    val fallbackAction: WorkflowAction? = null,
    val isEnabled: Boolean = true,
    var executionCount: Int = 0,
    var lastExecutedMs: Long = 0L
)

object WorkflowEngine {

    private val scope = CoroutineScope(Dispatchers.IO)
    private val rulesMap = ConcurrentHashMap<String, WorkflowRule>()

    private val _rulesStateFlow = MutableStateFlow<List<WorkflowRule>>(emptyList())
    val rulesStateFlow: StateFlow<List<WorkflowRule>> = _rulesStateFlow.asStateFlow()

    init {
        // Register default enterprise workflow rules
        registerRule(
            WorkflowRule(
                id = "rule_auto_memory_sync",
                name = "Auto-Sync Memory on Memory Event",
                description = "Triggers telemetry refresh and background cleanup when new memories are indexed.",
                trigger = WorkflowTrigger(type = TriggerType.ON_MEMORY_STORED),
                primaryAction = WorkflowAction(
                    type = ActionType.SCHEDULE_TASK,
                    targetId = "Memory Cleanup",
                    payloadParameters = mapOf("type" to TaskType.MEMORY_CLEANUP.name)
                )
            )
        )

        registerRule(
            WorkflowRule(
                id = "rule_voice_device_control",
                name = "Voice Control Tool Invocation",
                description = "Automatically triggers device status tool when voice sessions start.",
                trigger = WorkflowTrigger(type = TriggerType.ON_VOICE_SESSION),
                primaryAction = WorkflowAction(
                    type = ActionType.RUN_TOOL,
                    targetId = "device_control",
                    payloadParameters = mapOf("action" to "status")
                )
            )
        )

        registerRule(
            WorkflowRule(
                id = "rule_lead_radar_auto_scan",
                name = "Lead Radar Auto-Scan & Evaluation",
                description = "Automatically scans live job RSS feeds for user's SkillMatrix services and evaluates client match scores.",
                trigger = WorkflowTrigger(type = TriggerType.ON_SCHEDULE),
                primaryAction = WorkflowAction(
                    type = ActionType.RUN_TOOL,
                    targetId = "lead_radar",
                    payloadParameters = mapOf("query" to "Video Editing & Graphic Design")
                )
            )
        )

        registerRule(
            WorkflowRule(
                id = "rule_auto_proposal_drafting",
                name = "Auto Client Proposal Generator",
                description = "Drafts custom proposals and outreach messages automatically when high-scoring client opportunities are discovered.",
                trigger = WorkflowTrigger(type = TriggerType.ON_EVENT),
                primaryAction = WorkflowAction(
                    type = ActionType.STORE_MEMORY,
                    targetId = "Last_Auto_Proposal_Draft",
                    payloadParameters = mapOf("value" to "Respected Hiring Client, I came across your job request and am equipped with full SkillMatrix expertise to deliver with top precision.")
                )
            )
        )

        // Listen for WastiEventBus events to evaluate triggers automatically
        scope.launch {
            WastiEventBus.events.collect { event ->
                evaluateEventTriggers(event)
            }
        }
    }

    fun registerRule(rule: WorkflowRule) {
        rulesMap[rule.id] = rule
        updateRulesFlow()
    }

    fun unregisterRule(id: String) {
        rulesMap.remove(id)
        updateRulesFlow()
    }

    private suspend fun evaluateEventTriggers(event: WastiEvent) {
        rulesMap.values.filter { it.isEnabled }.forEach { rule ->
            val isMatched = when (rule.trigger.type) {
                TriggerType.ON_EVENT -> true
                TriggerType.ON_VOICE_SESSION -> event is WastiEvent.VoiceSessionChanged && event.isActive
                TriggerType.ON_MEMORY_STORED -> event is WastiEvent.SyncCompleted && event.serviceName.contains("Memory", ignoreCase = true)
                else -> false
            }

            if (isMatched) {
                executeWorkflowRule(rule)
            }
        }
    }

    suspend fun executeWorkflowRule(rule: WorkflowRule): Boolean {
        return try {
            executeAction(rule.primaryAction)
            rule.executionCount++
            rule.lastExecutedMs = System.currentTimeMillis()
            updateRulesFlow()
            true
        } catch (e: Exception) {
            if (rule.fallbackAction != null) {
                try {
                    executeAction(rule.fallbackAction)
                    rule.executionCount++
                    rule.lastExecutedMs = System.currentTimeMillis()
                    updateRulesFlow()
                    true
                } catch (fallbackErr: Exception) {
                    WastiEventBus.emit(WastiEvent.SystemAlert("WORKFLOW_ERROR", "Workflow Rule [${rule.name}] failed: ${fallbackErr.message}"))
                    false
                }
            } else {
                WastiEventBus.emit(WastiEvent.SystemAlert("WORKFLOW_ERROR", "Workflow Rule [${rule.name}] failed: ${e.message}"))
                false
            }
        }
    }

    private suspend fun executeAction(action: WorkflowAction) {
        when (action.type) {
            ActionType.RUN_TOOL -> {
                ToolRegistry.executeTool(action.targetId, action.payloadParameters)
            }
            ActionType.SCHEDULE_TASK -> {
                BackgroundTaskManager.scheduleOneOffTask(
                    name = action.targetId,
                    type = TaskType.ANALYTICS
                ) {
                    com.example.data.ops.OperationsManager.refreshStats()
                }
            }
            ActionType.EMIT_ALERT -> {
                WastiEventBus.emit(WastiEvent.SystemAlert("WORKFLOW_ACTION", action.targetId))
            }
            ActionType.STORE_MEMORY -> {
                com.example.data.memory.MemoryManager.saveMemory(
                    key = action.targetId,
                    value = action.payloadParameters["value"]?.toString() ?: "Workflow auto-saved memory",
                    category = "WORKFLOW"
                )
            }
            ActionType.NOTIFICATION -> {
                WastiEventBus.emit(WastiEvent.SystemAlert("NOTIFICATION", action.targetId))
            }
            ActionType.AUTONOMOUS_WORKFLOW -> {
                val task = WorkflowTask(
                    originalRequest = action.targetId
                )
                UnifiedWorkflowEngine.getInstance().executeWorkflow(task)
            }
        }
    }

    private fun updateRulesFlow() {
        _rulesStateFlow.value = rulesMap.values.toList()
    }
}
