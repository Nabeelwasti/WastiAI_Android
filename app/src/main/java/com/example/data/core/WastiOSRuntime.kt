package com.example.data.core

import android.content.Context
import android.util.Log
import com.example.data.action.WastiAppAction
import com.example.data.action.WastiAppActionBus
import com.example.data.agent.runtime.*
import com.example.data.db.SystemLogEntity
import com.example.data.db.WastiDatabase
import com.example.data.di.WastiServiceLocator
import com.example.data.memory.ExecutionMemoryRecorder
import com.example.data.memory.ExecutionRecord
import com.example.data.workflow.UnifiedWorkflowEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Stage 10: Canonical Command Origin across all interfaces and rooms.
 */
enum class CommandOrigin(val displayName: String, val isLocal: Boolean) {
    CHAT("Chat Workspace", true),
    TERMINAL("Terminal Workspace", true),
    FLOATING_BUBBLE("Floating Assistant Bubble", true),
    VOICE("Voice Interface", true),
    LOCAL_SERVER("Embedded HTTP/WS Server", true),
    WEB_COMPANION("Web Companion Interface", false),
    DESKTOP_COMPANION("Desktop Companion Interface", false),
    REMOTE_DEVICE("Remote Paired Device", false),
    BACKGROUND_WORKER("Background Autonomous Daemon", true),
    DEV_ASSISTANT("Developer Assistant", true),
    PROJECTS("Projects & Tasks Workspace", true),
    OPERATIONS("Operations Dashboard", true),
    NOTIFICATION("Android System Notification", true),
    EXTERNAL_NODE("Distributed Device Node", false),
    ACCESSIBILITY("Accessibility Automation", true)
}

/**
 * Stage 10: Unified Command Submission Request.
 */
data class CommandSubmission(
    val commandId: String = java.util.UUID.randomUUID().toString(),
    val rawCommand: String,
    val origin: CommandOrigin,
    val executionMode: ExecutionMode = ExecutionMode.AUTONOMOUS,
    val targetAgentId: String = "ceo_agent",
    val parameters: Map<String, Any> = emptyMap(),
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Stage 10: Global Execution Context accessible to all rooms and interfaces.
 */
data class GlobalExecutionContext(
    val activeTaskId: String? = null,
    val activeCommand: String? = null,
    val currentOrigin: CommandOrigin? = null,
    val agenticState: AgenticState = AgenticState.Idle(),
    val progressMessage: String = "System Idle",
    val isBusy: Boolean = false,
    val activeTool: String? = null,
    val lastResultSummary: String? = null,
    val lastError: String? = null,
    val currentIteration: Int = 0,
    val lastUpdatedTimestamp: Long = System.currentTimeMillis()
)

/**
 * Stage 10: Canonical Record of Command Execution.
 */
data class CommandExecutionRecord(
    val commandId: String,
    val command: String,
    val origin: CommandOrigin,
    val isSuccess: Boolean,
    val response: String,
    val durationMs: Long,
    val verificationEvidence: String? = null,
    val agenticState: String = "Completed",
    val timestamp: Long = System.currentTimeMillis()
)

sealed class CommandSubmissionResult {
    data class Accepted(
        val commandId: String,
        val taskId: String?,
        val origin: CommandOrigin,
        val message: String
    ) : CommandSubmissionResult()

    data class ImmediateSuccess(
        val commandId: String,
        val origin: CommandOrigin,
        val output: String,
        val verificationEvidence: String? = null
    ) : CommandSubmissionResult()

    data class Rejected(
        val commandId: String,
        val origin: CommandOrigin,
        val reason: String
    ) : CommandSubmissionResult()
}

/**
 * Stage 10: Canonical Wasti OS Runtime.
 *
 * "ONE BRAIN -> INFINITELY EXPANDING ROOMS"
 * The canonical execution brain of Wasti AI OS liberated from any specific UI screen.
 * Operates independently of Compose lifecycles, surviving screen navigation,
 * background transitions, and activity recreations.
 */
class WastiOSRuntime(
    private val appContext: Context? = null,
    private val agentRuntime: WastiAgentRuntime? = null,
    private val executionFabric: UnifiedExecutionFabric? = null,
    private val emergencyStopController: WastiEmergencyStopController? = null,
    private val eventBus: AgentEventBus? = null
) {
    companion object {
        private const val TAG = "WastiOSRuntime"

        @Volatile
        private var instance: WastiOSRuntime? = null

        fun getInstance(context: Context? = null): WastiOSRuntime {
            return instance ?: synchronized(this) {
                instance ?: WastiOSRuntime(
                    appContext = context ?: com.example.WastiApplication.instance
                ).also { instance = it }
            }
        }
    }

    private val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val activeTasksMap = ConcurrentHashMap<String, CommandSubmission>()
    private val executionHistoryQueue = ConcurrentLinkedQueue<CommandExecutionRecord>()

    private val _activeContext = MutableStateFlow(GlobalExecutionContext())
    val activeContext: StateFlow<GlobalExecutionContext> = _activeContext.asStateFlow()

    private val _executionHistory = MutableStateFlow<List<CommandExecutionRecord>>(emptyList())
    val executionHistory: StateFlow<List<CommandExecutionRecord>> = _executionHistory.asStateFlow()

    private val safeEmergencyStop: WastiEmergencyStopController
        get() = emergencyStopController ?: WastiServiceLocator.emergencyStopController

    private val safeAgentRuntime: WastiAgentRuntime
        get() = agentRuntime ?: WastiServiceLocator.agentRuntime

    private val safeEventBus: AgentEventBus
        get() = eventBus ?: WastiServiceLocator.agentEventBus

    private val safeFabric: UnifiedExecutionFabric
        get() = executionFabric ?: WastiServiceLocator.executionFabric

    init {
        observeAgentEvents()
        observeAppActions()
    }

    private fun observeAgentEvents() {
        runtimeScope.launch {
            safeEventBus.events.collect { event ->
                handleAgentEvent(event)
            }
        }
    }

    private fun observeAppActions() {
        runtimeScope.launch {
            WastiAppActionBus.actions.collect { action ->
                handleAppAction(action)
            }
        }
    }

    private fun handleAgentEvent(event: AgentEvent) {
        val current = _activeContext.value
        val taskIdStr = event.taskId.value
        when (event) {
            is AgentEvent.TaskCreated -> {
                _activeContext.value = current.copy(
                    activeTaskId = taskIdStr,
                    isBusy = true,
                    progressMessage = "Task created: ${event.prompt.take(40)}...",
                    lastUpdatedTimestamp = System.currentTimeMillis()
                )
            }
            is AgentEvent.PlanningStarted -> {
                _activeContext.value = current.copy(
                    activeTaskId = taskIdStr,
                    agenticState = AgenticState.Planning(event.prompt),
                    progressMessage = "Planning: ${event.prompt.take(40)}...",
                    isBusy = true,
                    lastUpdatedTimestamp = System.currentTimeMillis()
                )
            }
            is AgentEvent.ToolStarted -> {
                _activeContext.value = current.copy(
                    activeTaskId = taskIdStr,
                    activeTool = event.toolName,
                    agenticState = AgenticState.Executing("Running ${event.toolName}"),
                    progressMessage = "Executing tool: ${event.toolName}",
                    isBusy = true,
                    lastUpdatedTimestamp = System.currentTimeMillis()
                )
            }
            is AgentEvent.ToolCompleted -> {
                _activeContext.value = current.copy(
                    activeTaskId = taskIdStr,
                    activeTool = null,
                    progressMessage = "Completed tool: ${event.toolName}",
                    lastUpdatedTimestamp = System.currentTimeMillis()
                )
            }
            is AgentEvent.DiagnosisCreated -> {
                _activeContext.value = current.copy(
                    activeTaskId = taskIdStr,
                    agenticState = AgenticState.Debugging(event.summary),
                    progressMessage = "Debugging: ${event.summary.take(50)}...",
                    lastUpdatedTimestamp = System.currentTimeMillis()
                )
            }
            is AgentEvent.CorrectionProposed -> {
                _activeContext.value = current.copy(
                    activeTaskId = taskIdStr,
                    agenticState = AgenticState.Debugging(event.proposal),
                    progressMessage = "Self-correcting: ${event.proposal.take(50)}...",
                    lastUpdatedTimestamp = System.currentTimeMillis()
                )
            }
            is AgentEvent.EmergencyStopped -> {
                _activeContext.value = current.copy(
                    activeTaskId = null,
                    isBusy = false,
                    agenticState = AgenticState.SecurityBlocked("Emergency Stop: ${event.reason}"),
                    progressMessage = "EMERGENCY STOP TRIGGERED: ${event.reason}",
                    lastError = event.reason,
                    lastUpdatedTimestamp = System.currentTimeMillis()
                )
            }
            is AgentEvent.TaskCancelled -> {
                _activeContext.value = current.copy(
                    activeTaskId = null,
                    isBusy = false,
                    agenticState = AgenticState.Idle(),
                    progressMessage = "Task cancelled: ${event.reason}",
                    lastUpdatedTimestamp = System.currentTimeMillis()
                )
            }
            is AgentEvent.TaskCompleted -> {
                _activeContext.value = current.copy(
                    activeTaskId = taskIdStr,
                    isBusy = false,
                    agenticState = AgenticState.Completed(event.summary),
                    progressMessage = "Task Completed: ${event.summary.take(40)}",
                    lastUpdatedTimestamp = System.currentTimeMillis()
                )
            }
            is AgentEvent.TaskFailed -> {
                _activeContext.value = current.copy(
                    activeTaskId = taskIdStr,
                    isBusy = false,
                    agenticState = AgenticState.Failed(event.error),
                    progressMessage = "Task Failed: ${event.error.take(40)}",
                    lastError = event.error,
                    lastUpdatedTimestamp = System.currentTimeMillis()
                )
            }
            else -> {}
        }
    }

    private fun mapStateToMessage(state: AgenticState): String {
        return when (state) {
            is AgenticState.Idle -> "System Idle"
            is AgenticState.Analyzing -> "Analyzing Context: ${state.message}"
            is AgenticState.Planning -> "Planning: ${state.message}"
            is AgenticState.Executing -> "Executing: ${state.message}"
            is AgenticState.Debugging -> "Debugging & Self-Correction: ${state.message}"
            is AgenticState.Inspecting -> "Inspecting: ${state.message}"
            is AgenticState.WaitingForPermission -> "Awaiting Permission: ${state.message}"
            is AgenticState.Editing -> "Editing: ${state.message}"
            is AgenticState.Observing -> "Observing: ${state.message}"
            is AgenticState.Testing -> "Testing: ${state.message}"
            is AgenticState.Verification -> "Truth Verification: ${state.message}"
            is AgenticState.SecurityBlocked -> "Security Gate: ${state.message}"
            is AgenticState.Completed -> "Execution Verified & Successful: ${state.message}"
            is AgenticState.Failed -> "Execution Failed: ${state.message}"
            is AgenticState.Cancelled -> "Execution Cancelled: ${state.message}"
            is AgenticState.RolledBack -> "Workspace Rolled Back: ${state.message}"
        }
    }

    private suspend fun handleAppAction(action: WastiAppAction) {
        when (action) {
            is WastiAppAction.ExecuteTerminalCommand -> {
                submitCommand(
                    command = action.command,
                    origin = CommandOrigin.TERMINAL,
                    executionMode = ExecutionMode.AUTONOMOUS
                )
            }
            is WastiAppAction.StartBackgroundWorkflow -> {
                submitCommand(
                    command = action.request,
                    origin = CommandOrigin.BACKGROUND_WORKER,
                    executionMode = ExecutionMode.AUTONOMOUS
                )
            }
            is WastiAppAction.StartLocalServer -> {
                val req = UnifiedExecutionRequest(
                    capabilityId = "LOCAL_SERVER",
                    parameters = mapOf("action" to "start", "port" to action.port)
                )
                safeFabric.execute(req, appContext)
            }
            is WastiAppAction.StopLocalServer -> {
                val req = UnifiedExecutionRequest(
                    capabilityId = "LOCAL_SERVER",
                    parameters = mapOf("action" to "stop", "reason" to action.reason)
                )
                safeFabric.execute(req, appContext)
            }
            else -> {}
        }
    }

    /**
     * Submit a command for autonomous orchestration and multi-agent execution.
     * Accessible to any interface (Chat, Terminal, Bubble, Voice, Local Server, Web, etc.)
     */
    fun submitCommand(
        command: String,
        origin: CommandOrigin,
        executionMode: ExecutionMode = ExecutionMode.AUTONOMOUS,
        targetAgentId: String = "ceo_agent",
        parameters: Map<String, Any> = emptyMap()
    ): CommandSubmissionResult {
        val trimmed = command.trim()
        if (trimmed.isBlank()) {
            return CommandSubmissionResult.Rejected(
                commandId = "",
                origin = origin,
                reason = "Command cannot be blank."
            )
        }

        if (safeEmergencyStop.isEmergencyStopped) {
            return CommandSubmissionResult.Rejected(
                commandId = "",
                origin = origin,
                reason = "EMERGENCY_STOP_ACTIVE: New commands are rejected while emergency stop is active."
            )
        }

        val submission = CommandSubmission(
            rawCommand = trimmed,
            origin = origin,
            executionMode = executionMode,
            targetAgentId = targetAgentId,
            parameters = parameters
        )
        activeTasksMap[submission.commandId] = submission

        // Update global execution context
        _activeContext.value = _activeContext.value.copy(
            activeCommand = trimmed,
            currentOrigin = origin,
            isBusy = true,
            progressMessage = "Dispatching command from ${origin.displayName}...",
            lastUpdatedTimestamp = System.currentTimeMillis()
        )

        runtimeScope.launch(Dispatchers.IO) {
            executeCommandInternal(submission)
        }

        return CommandSubmissionResult.Accepted(
            commandId = submission.commandId,
            taskId = null,
            origin = origin,
            message = "Command accepted by Wasti OS Runtime from ${origin.displayName}"
        )
    }

    private suspend fun executeCommandInternal(submission: CommandSubmission) {
        val startTime = System.currentTimeMillis()
        var isSuccess = false
        var outputSummary = ""
        var evidence: String? = null
        var taskIdStr = ""

        try {
            // 1. Submit to Canonical Agent Runtime
            val taskResult = safeAgentRuntime.submitTaskResult(
                prompt = submission.rawCommand,
                executionMode = submission.executionMode
            )

            if (taskResult.isFailure) {
                val err = taskResult.exceptionOrNull()?.message ?: "Task submission failed"
                recordFailure(submission, startTime, err)
                return
            }

            val task = taskResult.getOrThrow()
            taskIdStr = task.taskId.value

            _activeContext.value = _activeContext.value.copy(
                activeTaskId = task.taskId.value,
                progressMessage = "Running task ${task.taskId.value} via ${submission.targetAgentId}..."
            )

            // 2. Canonical Single Execution via UniversalAutonomousExecutionLoop
            val autoLoopResult = try {
                WastiServiceLocator.universalAutonomousExecutionLoop.executeGoal(
                    userGoal = submission.rawCommand,
                    originatingTaskId = taskIdStr
                )
            } catch (e: Exception) {
                null
            }

            val duration = System.currentTimeMillis() - startTime

            val snapshot = if (autoLoopResult != null) {
                val success = autoLoopResult.phase == com.example.data.conversation.TaskTimelinePhase.COMPLETED || autoLoopResult.isVerified
                val summary = if (autoLoopResult.finalOutput.isNotBlank()) autoLoopResult.finalOutput else "Universal loop completed."
                val state: AgenticState = if (success) {
                    AgenticState.Completed(summary)
                } else {
                    AgenticState.Failed(summary)
                }
                WastiServiceLocator.taskManager.updateTaskState(task.taskId, state)
                TaskExecutionSnapshot(success, summary, autoLoopResult.totalSteps, state)
            } else {
                // Fallback to safeAgentRuntime only if universal autonomous loop is unavailable
                val loopResult = safeAgentRuntime.executeTask(task.taskId)
                TaskExecutionSnapshot(loopResult.isSuccess, loopResult.executionSummary, loopResult.iterationsCompleted, loopResult.finalState)
            }

            val taskSuccess = snapshot.isSuccess
            val finalSummary = snapshot.summary
            val loopIterations = snapshot.iterations
            val finalAgenticState = snapshot.state

            isSuccess = taskSuccess
            outputSummary = finalSummary

            evidence = if (autoLoopResult != null && autoLoopResult.isVerified) {
                autoLoopResult.verificationEvidence ?: "Objective verification passed: ${autoLoopResult.finalOutput.take(120)}"
            } else null

            val terminalTruth = when {
                autoLoopResult != null && autoLoopResult.isVerified -> TerminalTruthState.COMPLETED_VERIFIED
                taskSuccess -> TerminalTruthState.COMPLETED_UNVERIFIED
                else -> TerminalTruthState.EXECUTION_FAILED
            }

            // 3. Update global context
            _activeContext.value = _activeContext.value.copy(
                isBusy = false,
                agenticState = finalAgenticState,
                progressMessage = if (terminalTruth == TerminalTruthState.COMPLETED_VERIFIED) "Execution Verified & Successful" else if (isSuccess) "Execution Completed (Unverified)" else "Execution Failed: $outputSummary",
                lastResultSummary = outputSummary,
                lastError = if (!isSuccess) outputSummary else null,
                lastUpdatedTimestamp = System.currentTimeMillis()
            )

            // 4. Record to Execution Graph & Memory
            val record = CommandExecutionRecord(
                commandId = submission.commandId,
                command = submission.rawCommand,
                origin = submission.origin,
                isSuccess = isSuccess,
                response = outputSummary,
                durationMs = duration,
                verificationEvidence = evidence,
                agenticState = finalAgenticState::class.simpleName ?: "Unknown",
                timestamp = System.currentTimeMillis()
            )
            recordExecutionRecord(record)

            ExecutionMemoryRecorder.recordExecutionOutcome(
                ExecutionRecord(
                    taskId = taskIdStr,
                    goal = submission.rawCommand,
                    interpretedIntent = submission.targetAgentId,
                    selectedCapability = "WastiOSRuntime",
                    selectedNode = "local_android_node",
                    stepsCount = loopIterations,
                    durationMs = duration,
                    isSuccess = if (terminalTruth == TerminalTruthState.COMPLETED_VERIFIED) true else if (isSuccess) null else false,
                    verificationStatus = if (terminalTruth == TerminalTruthState.COMPLETED_VERIFIED) "VERIFIED" else if (isSuccess) "UNVERIFIED" else "FAILED",
                    verificationEvidence = evidence,
                    terminalTruthState = terminalTruth
                )
            )

            logSystemEvent(
                if (isSuccess) "INFO" else "WARN",
                "OSRuntime execution [${submission.origin.name}]: ${submission.rawCommand.take(60)} -> ${if (isSuccess) "SUCCESS" else "FAILED"} (${duration}ms)"
            )
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            Log.e(TAG, "Error during WastiOSRuntime command execution", e)
            recordFailure(submission, startTime, e.localizedMessage ?: "Unknown execution error")
        } finally {
            activeTasksMap.remove(submission.commandId)
        }
    }

    private suspend fun recordFailure(submission: CommandSubmission, startTime: Long, error: String) {
        val duration = System.currentTimeMillis() - startTime
        _activeContext.value = _activeContext.value.copy(
            isBusy = false,
            agenticState = AgenticState.Failed(error),
            progressMessage = "Execution Failed: $error",
            lastError = error,
            lastUpdatedTimestamp = System.currentTimeMillis()
        )

        val record = CommandExecutionRecord(
            commandId = submission.commandId,
            command = submission.rawCommand,
            origin = submission.origin,
            isSuccess = false,
            response = error,
            durationMs = duration,
            agenticState = "Failed",
            timestamp = System.currentTimeMillis()
        )
        recordExecutionRecord(record)

        logSystemEvent("ERROR", "OSRuntime Command Failed [${submission.origin.name}]: $error")
    }

    private fun recordExecutionRecord(record: CommandExecutionRecord) {
        executionHistoryQueue.add(record)
        while (executionHistoryQueue.size > 100) {
            executionHistoryQueue.poll()
        }
        _executionHistory.value = executionHistoryQueue.toList().reversed()
    }

    private fun logSystemEvent(level: String, message: String) {
        try {
            val ctx = appContext ?: com.example.WastiApplication.instance ?: return
            val db = WastiDatabase.getDatabase(ctx)
            runtimeScope.launch(Dispatchers.IO) {
                db.systemLogDao().insertLog(
                    SystemLogEntity(
                        level = level,
                        source = "WastiOSRuntime",
                        message = message,
                        timestamp = System.currentTimeMillis()
                    )
                )
            }
        } catch (_: Exception) {}
    }

    fun cancelActiveExecution(reason: String = "User requested"): Boolean {
        val activeId = _activeContext.value.activeTaskId
        return if (activeId != null) {
            safeAgentRuntime.cancelTask(TaskId(activeId), reason).isSuccess
        } else {
            _activeContext.value = _activeContext.value.copy(
                isBusy = false,
                agenticState = AgenticState.Idle(),
                progressMessage = "Execution cancelled ($reason)"
            )
            true
        }
    }

    fun triggerEmergencyStop(reason: String = "Manual Emergency Stop triggered") {
        safeEmergencyStop.triggerEmergencyStop(reason)
        safeAgentRuntime.triggerEmergencyStop(reason)
        _activeContext.value = _activeContext.value.copy(
            activeTaskId = null,
            isBusy = false,
            agenticState = AgenticState.SecurityBlocked("EMERGENCY STOP: $reason"),
            progressMessage = "EMERGENCY STOP TRIGGERED: $reason",
            lastError = reason,
            lastUpdatedTimestamp = System.currentTimeMillis()
        )
    }

    fun clearEmergencyStop() {
        safeEmergencyStop.resetEmergencyStop()
        _activeContext.value = _activeContext.value.copy(
            agenticState = AgenticState.Idle(),
            progressMessage = "Emergency stop reset. System ready.",
            lastError = null
        )
    }
}

private data class TaskExecutionSnapshot(
    val isSuccess: Boolean,
    val summary: String,
    val iterations: Int,
    val state: AgenticState
)
