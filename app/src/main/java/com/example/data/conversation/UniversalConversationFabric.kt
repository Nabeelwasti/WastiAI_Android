package com.example.data.conversation

import android.content.Context
import android.util.Log
import com.example.data.agent.runtime.AgentEvent
import com.example.data.agent.runtime.AgentEventBus
import com.example.data.agent.runtime.ExecutionMode
import com.example.data.agent.runtime.WastiEmergencyStopController
import com.example.data.core.CommandOrigin
import com.example.data.core.CommandSubmissionResult
import com.example.data.core.WastiOSRuntime
import com.example.data.db.ConversationEntity
import com.example.data.db.MessageEntity
import com.example.data.db.WastiDatabase
import com.example.data.di.WastiServiceLocator
import com.example.data.node.WastiNodeManager
import com.example.data.transport.WastiCommandTransport
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Stage 18: Canonical Universal Conversation Fabric for Wasti AI OS.
 *
 * Removes the interaction boundary between the AI Brain and all UI rooms/interfaces.
 * Manages universal cross-room context, shared execution states, room-filtered event flows,
 * universal user confirmation gates, and companion synchronization.
 *
 * ONE BRAIN — INFINITE ROOMS — MANY NODES — ONE UNIFIED EXECUTION FABRIC
 */
class UniversalConversationFabric(
    private val context: Context,
    private val commandTransport: WastiCommandTransport = WastiServiceLocator.commandTransport,
    private val runtime: WastiOSRuntime = WastiServiceLocator.wastiOSRuntime,
    private val eventBus: AgentEventBus = WastiServiceLocator.agentEventBus,
    private val emergencyStopController: WastiEmergencyStopController = WastiServiceLocator.emergencyStopController,
    private val nodeManager: WastiNodeManager = WastiServiceLocator.nodeManager
) {
    companion object {
        private const val TAG = "UniversalConvFabric"

        @Volatile
        private var instance: UniversalConversationFabric? = null

        fun getInstance(context: Context? = null): UniversalConversationFabric {
            return instance ?: synchronized(this) {
                instance ?: run {
                    val appContext = context?.applicationContext
                        ?: com.example.WastiApplication.instance
                        ?: throw IllegalStateException("UniversalConversationFabric requires a valid Context")
                    WastiServiceLocator.init(appContext)
                    UniversalConversationFabric(appContext).also { instance = it }
                }
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Context Storage
    private val conversationContextMap = ConcurrentHashMap<String, UniversalConversationContext>()
    private val _activeContext = MutableStateFlow(UniversalConversationContext())
    val activeContext: StateFlow<UniversalConversationContext> = _activeContext.asStateFlow()

    // Room-Aware Universal Event Stream
    private val _fabricEvents = MutableSharedFlow<UniversalConversationEvent>(
        replay = 100,
        extraBufferCapacity = 500
    )
    val fabricEvents: SharedFlow<UniversalConversationEvent> = _fabricEvents.asSharedFlow()

    // Confirmation Gates
    private val pendingConfirmationEntities = ConcurrentHashMap<String, PendingUserConfirmation>()
    private val confirmationDeferreds = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    init {
        // Initialize default context
        val initialContext = UniversalConversationContext()
        conversationContextMap[initialContext.conversationId] = initialContext
        _activeContext.value = initialContext

        // Wire event bus to synchronize thinking, tools, and execution phases automatically
        scope.launch {
            eventBus.events.collect { agentEvent ->
                handleIncomingAgentEvent(agentEvent)
            }
        }
    }

    /**
     * Switch current active room without interrupting underlying tasks or execution.
     */
    fun switchRoom(roomId: String, conversationId: String? = null): UniversalConversationContext {
        val targetConvId = conversationId ?: _activeContext.value.conversationId
        val existing = conversationContextMap[targetConvId] ?: UniversalConversationContext(conversationId = targetConvId)
        
        val updated = existing.copy(
            currentRoom = roomId,
            lastUpdatedTimestamp = System.currentTimeMillis()
        )
        conversationContextMap[targetConvId] = updated
        _activeContext.value = updated

        emitFabricEvent(
            conversationId = targetConvId,
            taskId = updated.taskId,
            originatingRoom = roomId,
            executionPhase = "ROOM_ATTACHED",
            message = "Room switched to $roomId",
            severity = "INFO"
        )
        return updated
    }

    /**
     * Submit a new task into the canonical execution fabric from any room.
     */
    fun submitTask(
        prompt: String,
        originRoom: String,
        conversationId: String? = null,
        parameters: Map<String, Any> = emptyMap(),
        executionMode: ExecutionMode = ExecutionMode.AUTONOMOUS,
        targetAgentId: String = "ceo_agent"
    ): CommandSubmissionResult {
        if (emergencyStopController.isEmergencyStopped) {
            val rejected = CommandSubmissionResult.Rejected(
                commandId = "",
                origin = mapRoomToOrigin(originRoom),
                reason = "EMERGENCY_STOP_ACTIVE: Execution is authoritatively halted"
            )
            updateExecutionState(ConversationExecutionState.EMERGENCY_STOPPED, "EMERGENCY_STOP", reason = rejected.reason)
            return rejected
        }

        val convId = conversationId ?: _activeContext.value.conversationId
        val taskId = "task_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"
        val origin = mapRoomToOrigin(originRoom)

        // Record User Message in snapshot
        recordMessage(
            role = "user",
            content = prompt,
            originRoom = originRoom,
            conversationId = convId
        )

        // Update context to PLANNING
        val updatedContext = (_activeContext.value.takeIf { it.conversationId == convId } ?: UniversalConversationContext(conversationId = convId)).copy(
            conversationId = convId,
            taskId = taskId,
            origin = origin,
            currentRoom = originRoom,
            activeExecutionState = ConversationExecutionState.PLANNING,
            currentAgent = targetAgentId,
            lastUserInteraction = prompt,
            lastUpdatedTimestamp = System.currentTimeMillis()
        )
        conversationContextMap[convId] = updatedContext
        _activeContext.value = updatedContext

        emitFabricEvent(
            conversationId = convId,
            taskId = taskId,
            originatingRoom = originRoom,
            executionPhase = "SUBMIT_TASK",
            message = "Task received in room $originRoom: $prompt",
            severity = "INFO"
        )

        // Dispatch through Canonical Command Transport
        val subParams = parameters.toMutableMap()
        subParams["conversationId"] = convId
        subParams["taskId"] = taskId
        subParams["roomId"] = originRoom

        val result = commandTransport.dispatchCommand(
            command = prompt,
            origin = origin,
            executionMode = executionMode,
            targetAgentId = targetAgentId,
            parameters = subParams,
            requestId = taskId,
            correlationId = updatedContext.correlationId
        )

        return result
    }

    /**
     * Cross-Room Task Continuation.
     * Continues an active or completed task from a different interface room seamlessly.
     */
    fun continueConversation(
        prompt: String,
        originRoom: String,
        conversationId: String? = null,
        parameters: Map<String, Any> = emptyMap()
    ): CommandSubmissionResult {
        val convId = conversationId ?: _activeContext.value.conversationId
        val existing = conversationContextMap[convId] ?: _activeContext.value

        // Record continuation intent
        val continuation = ContinuationIntent(
            parentTaskId = existing.taskId,
            conversationId = convId,
            originRoom = originRoom,
            prompt = prompt,
            parameters = parameters
        )

        val updatedMeta = existing.continuationMetadata.toMutableMap()
        updatedMeta["lastContinuationPrompt"] = prompt
        updatedMeta["lastContinuationOrigin"] = originRoom
        updatedMeta["lastContinuationTimestamp"] = continuation.timestamp.toString()

        val updatedContext = existing.copy(
            currentRoom = originRoom,
            lastUserInteraction = prompt,
            continuationMetadata = updatedMeta,
            lastUpdatedTimestamp = System.currentTimeMillis()
        )
        conversationContextMap[convId] = updatedContext
        _activeContext.value = updatedContext

        emitFabricEvent(
            conversationId = convId,
            taskId = existing.taskId,
            originatingRoom = originRoom,
            executionPhase = "CONTINUATION_REQUESTED",
            message = "Continuation received from $originRoom: $prompt",
            severity = "INFO"
        )

        // Submit continuation as an augmented prompt preserving context
        val augmentedPrompt = if (existing.taskId != null) {
            "Continuation from room [$originRoom] (Parent Task: ${existing.taskId}): $prompt"
        } else {
            prompt
        }

        return submitTask(
            prompt = augmentedPrompt,
            originRoom = originRoom,
            conversationId = convId,
            parameters = parameters,
            targetAgentId = existing.currentAgent
        )
    }

    /**
     * Request an interactive user confirmation for sensitive or dangerous operations.
     * Accessible and actionable across all rooms.
     */
    fun requestConfirmation(
        actionTitle: String,
        actionDetails: String,
        requiredPrivilege: String,
        requestedByRoom: String,
        conversationId: String? = null,
        taskId: String? = null
    ): PendingUserConfirmation {
        val convId = conversationId ?: _activeContext.value.conversationId
        val tId = taskId ?: _activeContext.value.taskId

        val confirmation = PendingUserConfirmation(
            conversationId = convId,
            taskId = tId,
            actionTitle = actionTitle,
            actionDetails = actionDetails,
            requiredPrivilege = requiredPrivilege,
            requestedByRoom = requestedByRoom
        )

        pendingConfirmationEntities[confirmation.confirmationId] = confirmation
        confirmationDeferreds[confirmation.confirmationId] = CompletableDeferred()

        // Update Context State
        val current = conversationContextMap[convId] ?: _activeContext.value
        val updatedList = current.activeConfirmations.filterNot { it.confirmationId == confirmation.confirmationId } + confirmation
        val updatedContext = current.copy(
            activeExecutionState = ConversationExecutionState.AWAITING_CONFIRMATION,
            activeConfirmations = updatedList,
            lastUpdatedTimestamp = System.currentTimeMillis()
        )
        conversationContextMap[convId] = updatedContext
        if (_activeContext.value.conversationId == convId) {
            _activeContext.value = updatedContext
        }

        emitFabricEvent(
            conversationId = convId,
            taskId = tId,
            originatingRoom = requestedByRoom,
            executionPhase = "CONFIRMATION_REQUIRED",
            message = "Authorization required for: $actionTitle ($requiredPrivilege)",
            severity = "CONFIRMATION_REQUIRED",
            evidence = actionDetails
        )

        return confirmation
    }

    /**
     * Suspend coroutine until confirmation is resolved or timeout occurs.
     */
    suspend fun suspendForConfirmation(confirmationId: String, timeoutMs: Long = 60000L): Boolean {
        val deferred = confirmationDeferreds[confirmationId] ?: return false
        return try {
            withTimeout(timeoutMs) {
                deferred.await()
            }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "Confirmation $confirmationId timed out after ${timeoutMs}ms")
            resolveConfirmation(confirmationId, approved = false, resolvedByRoom = "SYSTEM_TIMEOUT", reason = "Confirmation timed out")
            false
        }
    }

    /**
     * Resolve a pending user confirmation from any authorized room.
     */
    fun resolveConfirmation(
        confirmationId: String,
        approved: Boolean,
        resolvedByRoom: String,
        reason: String? = null
    ): Boolean {
        val entity = pendingConfirmationEntities[confirmationId] ?: return false
        if (entity.isResolved) return true

        val resolvedEntity = entity.copy(
            isResolved = true,
            approved = approved,
            resolvedByRoom = resolvedByRoom,
            resolvedAt = System.currentTimeMillis(),
            reason = reason
        )
        pendingConfirmationEntities[confirmationId] = resolvedEntity

        // Complete deferred
        confirmationDeferreds[confirmationId]?.complete(approved)

        // Update active confirmations in context
        val convId = entity.conversationId
        val current = conversationContextMap[convId] ?: _activeContext.value
        val updatedConfirmations = current.activeConfirmations.map {
            if (it.confirmationId == confirmationId) resolvedEntity else it
        }

        val remainingUnresolved = updatedConfirmations.any { !it.isResolved && !it.isExpired() }
        val nextState = if (remainingUnresolved) {
            ConversationExecutionState.AWAITING_CONFIRMATION
        } else if (approved) {
            ConversationExecutionState.EXECUTING
        } else {
            ConversationExecutionState.CANCELLED
        }

        val updatedContext = current.copy(
            activeExecutionState = nextState,
            activeConfirmations = updatedConfirmations,
            lastUpdatedTimestamp = System.currentTimeMillis()
        )
        conversationContextMap[convId] = updatedContext
        if (_activeContext.value.conversationId == convId) {
            _activeContext.value = updatedContext
        }

        emitFabricEvent(
            conversationId = convId,
            taskId = entity.taskId,
            originatingRoom = resolvedByRoom,
            executionPhase = if (approved) "CONFIRMATION_APPROVED" else "CONFIRMATION_REJECTED",
            message = "Confirmation for '${entity.actionTitle}' was ${if (approved) "APPROVED" else "REJECTED"} from room $resolvedByRoom" + (reason?.let { " ($it)" } ?: ""),
            severity = if (approved) "SUCCESS" else "WARN"
        )

        return true
    }

    /**
     * Get Room-filtered Universal Event Flow.
     */
    fun observeRoomEvents(roomId: String): Flow<UniversalConversationEvent> {
        return _fabricEvents.filter { event ->
            event.targetRooms.isEmpty() || event.targetRooms.contains(roomId) || event.targetRooms.contains(RoomIdentity.CHAT.roomId)
        }
    }

    /**
     * Cancel the active task.
     */
    fun cancelActiveTask(reason: String = "User requested cancellation", roomId: String = "SYSTEM"): Boolean {
        val current = _activeContext.value
        val cancelled = commandTransport.cancelActiveExecution(reason)
        
        updateExecutionState(ConversationExecutionState.CANCELLED, "TASK_CANCELLED", reason = reason)

        emitFabricEvent(
            conversationId = current.conversationId,
            taskId = current.taskId,
            originatingRoom = roomId,
            executionPhase = "TASK_CANCELLED",
            message = "Execution cancelled: $reason",
            severity = "WARN"
        )
        return cancelled
    }

    /**
     * Pause the active task.
     */
    fun pauseActiveTask(reason: String = "User paused task"): Boolean {
        updateExecutionState(ConversationExecutionState.PAUSED, "TASK_PAUSED", reason = reason)
        emitFabricEvent(
            conversationId = _activeContext.value.conversationId,
            taskId = _activeContext.value.taskId,
            originatingRoom = _activeContext.value.currentRoom,
            executionPhase = "TASK_PAUSED",
            message = "Task execution paused: $reason",
            severity = "INFO"
        )
        return true
    }

    /**
     * Resume a paused task.
     */
    fun resumeActiveTask(): Boolean {
        val current = _activeContext.value
        if (current.activeExecutionState == ConversationExecutionState.PAUSED) {
            updateExecutionState(ConversationExecutionState.EXECUTING, "TASK_RESUMED")
            emitFabricEvent(
                conversationId = current.conversationId,
                taskId = current.taskId,
                originatingRoom = current.currentRoom,
                executionPhase = "TASK_RESUMED",
                message = "Task execution resumed",
                severity = "INFO"
            )
            return true
        }
        return false
    }

    /**
     * Trigger Authoritative Emergency Stop.
     */
    fun triggerEmergencyStop(reason: String = "Emergency stop triggered") {
        emergencyStopController.triggerEmergencyStop(reason)
        updateExecutionState(ConversationExecutionState.EMERGENCY_STOPPED, "EMERGENCY_STOP", reason = reason)
        emitFabricEvent(
            conversationId = _activeContext.value.conversationId,
            taskId = _activeContext.value.taskId,
            originatingRoom = "EMERGENCY_STOP",
            executionPhase = "EMERGENCY_STOP",
            message = "EMERGENCY STOP TRIGGERED: $reason",
            severity = "ERROR"
        )
    }

    /**
     * Generates a complete synchronization payload for Web and Desktop companions.
     */
    fun getCompanionSnapshot(conversationId: String? = null): CompanionConversationSnapshot {
        val convId = conversationId ?: _activeContext.value.conversationId
        val ctx = conversationContextMap[convId] ?: _activeContext.value
        val pending = pendingConfirmationEntities.values.filter { it.conversationId == convId && !it.isResolved && !it.isExpired() }
        val activeNodes = nodeManager.getAllNodes().map { "${it.nodeId} (${it.nodeName}) [${it.connectionState.name}]" }
        val availableActions = listOf("SUBMIT_TASK", "CONTINUE", "CONFIRM", "REJECT", "CANCEL", "PAUSE", "RESUME", "EMERGENCY_STOP")

        return CompanionConversationSnapshot(
            conversationContext = ctx,
            recentEvents = _fabricEvents.replayCache.takeLast(20),
            pendingConfirmations = pending,
            activeNodes = activeNodes,
            availableActions = availableActions
        )
    }

    /**
     * Record a message snapshot in the conversation history and persist to Room database.
     */
    fun recordMessage(role: String, content: String, originRoom: String, conversationId: String? = null) {
        val convId = conversationId ?: _activeContext.value.conversationId
        val current = conversationContextMap[convId] ?: UniversalConversationContext(conversationId = convId)

        val messageSnapshot = ConversationMessageSnapshot(
            role = role,
            content = content,
            originRoom = originRoom
        )

        val updatedHistory = current.conversationHistory.takeLast(99) + messageSnapshot
        val updated = current.copy(
            conversationHistory = updatedHistory,
            lastUpdatedTimestamp = System.currentTimeMillis()
        )
        conversationContextMap[convId] = updated
        if (_activeContext.value.conversationId == convId) {
            _activeContext.value = updated
        }

        // Persist to Room database
        scope.launch(Dispatchers.IO) {
            try {
                val db = WastiDatabase.getDatabase(context)
                val msgEntity = MessageEntity(
                    id = UUID.randomUUID().toString(),
                    conversationId = convId,
                    role = role,
                    content = content,
                    timestamp = System.currentTimeMillis()
                )
                db.messageDao().insertMessage(msgEntity)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to persist conversation message: ${e.message}")
            }
        }
    }

    /**
     * Update state of conversation context safely.
     */
    fun updateExecutionState(
        state: ConversationExecutionState,
        phase: String? = null,
        currentTool: String? = null,
        currentCapability: String? = null,
        reason: String? = null,
        conversationId: String? = null
    ) {
        val convId = conversationId ?: _activeContext.value.conversationId
        val current = conversationContextMap[convId] ?: UniversalConversationContext(conversationId = convId)

        val updatedHistory = if (phase != null) {
            current.executionHistoryRef.takeLast(49) + "$phase: ${state.name}${reason?.let { " ($it)" } ?: ""}"
        } else {
            current.executionHistoryRef
        }

        val updated = current.copy(
            activeExecutionState = state,
            currentTool = currentTool ?: current.currentTool,
            currentCapability = currentCapability ?: current.currentCapability,
            executionHistoryRef = updatedHistory,
            lastExecutionEvent = phase ?: current.lastExecutionEvent,
            lastUpdatedTimestamp = System.currentTimeMillis()
        )
        conversationContextMap[convId] = updated
        if (_activeContext.value.conversationId == convId) {
            _activeContext.value = updated
        }
    }

    /**
     * Emits a fabric event across all room listeners.
     */
    fun emitFabricEvent(
        conversationId: String,
        taskId: String?,
        originatingRoom: String,
        executionPhase: String,
        message: String,
        severity: String = "INFO",
        evidence: String? = null,
        targetRooms: Set<String> = emptySet()
    ) {
        val event = UniversalConversationEvent(
            conversationId = conversationId,
            taskId = taskId,
            originatingRoom = originatingRoom,
            executionPhase = executionPhase,
            message = message,
            severity = severity,
            evidence = evidence,
            targetRooms = targetRooms
        )
        _fabricEvents.tryEmit(event)
    }

    private fun handleIncomingAgentEvent(event: AgentEvent) {
        val current = _activeContext.value
        val convId = current.conversationId
        val taskId = current.taskId

        when (event) {
            is AgentEvent.PlanningStarted -> {
                updateExecutionState(ConversationExecutionState.PLANNING, "PLANNING", conversationId = convId)
                emitFabricEvent(convId, taskId, current.currentRoom, "PLANNING", "Agent planning execution steps")
            }
            is AgentEvent.ToolStarted -> {
                updateExecutionState(
                    state = ConversationExecutionState.EXECUTING,
                    phase = "TOOL_START",
                    currentTool = event.toolName,
                    conversationId = convId
                )
                emitFabricEvent(convId, taskId, current.currentRoom, "TOOL_START", "Executing tool: ${event.toolName}")
            }
            is AgentEvent.ToolCompleted -> {
                emitFabricEvent(
                    convId, taskId, current.currentRoom, "TOOL_FINISH",
                    "Completed tool: ${event.toolName} (success=${event.isSuccess})",
                    severity = if (event.isSuccess) "INFO" else "WARN"
                )
            }
            is AgentEvent.ToolFailed -> {
                emitFabricEvent(
                    convId, taskId, current.currentRoom, "TOOL_FAILED",
                    "Tool failed: ${event.toolName} - ${event.error}",
                    severity = "ERROR"
                )
            }
            is AgentEvent.TaskCompleted -> {
                updateExecutionState(ConversationExecutionState.COMPLETED, "TASK_COMPLETED", conversationId = convId)
                emitFabricEvent(
                    convId, taskId, current.currentRoom, "TASK_COMPLETED",
                    "Task completed: ${event.summary}",
                    severity = "SUCCESS"
                )
            }
            is AgentEvent.TaskFailed -> {
                updateExecutionState(ConversationExecutionState.FAILED, "TASK_FAILED", reason = event.error, conversationId = convId)
                emitFabricEvent(
                    convId, taskId, current.currentRoom, "TASK_FAILED",
                    "Task failed: ${event.error}",
                    severity = "ERROR"
                )
            }
            is AgentEvent.EmergencyStopTriggered -> {
                updateExecutionState(ConversationExecutionState.EMERGENCY_STOPPED, "EMERGENCY_STOP", reason = event.reason, conversationId = convId)
            }
            is AgentEvent.VerificationCompleted -> {
                val state = if (event.isSuccessful) ConversationExecutionState.VERIFYING else ConversationExecutionState.FAILED
                updateExecutionState(state, "VERIFICATION", conversationId = convId)
            }
            is AgentEvent.CorrectionProposed -> {
                updateExecutionState(ConversationExecutionState.DEBUGGING, "SELF_CORRECTION", conversationId = convId)
            }
            else -> {
                // Pass-through other events
            }
        }
    }

    fun clearAll() {
        conversationContextMap.clear()
        pendingConfirmationEntities.clear()
        confirmationDeferreds.clear()
        val defaultCtx = UniversalConversationContext()
        conversationContextMap[defaultCtx.conversationId] = defaultCtx
        _activeContext.value = defaultCtx
    }

    private fun mapRoomToOrigin(roomId: String): CommandOrigin {
        return when (roomId.uppercase()) {
            "CHAT" -> CommandOrigin.CHAT
            "TERMINAL" -> CommandOrigin.TERMINAL
            "DEV_ASSISTANT" -> CommandOrigin.DEV_ASSISTANT
            "VOICE" -> CommandOrigin.VOICE
            "FLOATING_BUBBLE" -> CommandOrigin.FLOATING_BUBBLE
            "PROJECTS" -> CommandOrigin.PROJECTS
            "OPERATIONS", "DASHBOARD" -> CommandOrigin.OPERATIONS
            "WEB_COMPANION" -> CommandOrigin.WEB_COMPANION
            "DESKTOP_COMPANION" -> CommandOrigin.DESKTOP_COMPANION
            "BACKGROUND_WORKER", "PROACTIVE_DAEMON" -> CommandOrigin.BACKGROUND_WORKER
            "NOTIFICATION" -> CommandOrigin.NOTIFICATION
            "ACCESSIBILITY" -> CommandOrigin.ACCESSIBILITY
            else -> CommandOrigin.CHAT
        }
    }
}
