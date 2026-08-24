package com.example.data.conversation

import com.example.data.core.CommandOrigin
import java.util.UUID

/**
 * Canonical Room/Interface Identifiers within Wasti AI OS.
 * Represents all native and connected interaction surfaces.
 */
enum class RoomIdentity(
    val roomId: String,
    val displayName: String,
    val supportsInteractivePrompts: Boolean = true,
    val supportsConfirmations: Boolean = true
) {
    CHAT("CHAT", "Chat Room"),
    TERMINAL("TERMINAL", "Terminal Workspace"),
    DEV_ASSISTANT("DEV_ASSISTANT", "Developer Studio"),
    VOICE("VOICE", "Voice Interface"),
    FLOATING_BUBBLE("FLOATING_BUBBLE", "Floating System Bubble"),
    DASHBOARD("DASHBOARD", "Executive Dashboard"),
    MEMORY("MEMORY", "Memory Knowledge Hub"),
    PROJECTS("PROJECTS", "Projects Hub"),
    SETTINGS("SETTINGS", "Settings & Nodes"),
    WEB_COMPANION("WEB_COMPANION", "Web Companion Portal"),
    DESKTOP_COMPANION("DESKTOP_COMPANION", "Desktop Companion"),
    PROACTIVE_DAEMON("PROACTIVE_DAEMON", "Proactive Autonomous Daemon", false, false),
    EXTERNAL_NODE("EXTERNAL_NODE", "External Federated Node", true, false),
    ACCESSIBILITY_BRIDGE("ACCESSIBILITY_BRIDGE", "Android Accessibility Bridge", false, false);

    companion object {
        fun fromRoomId(id: String): RoomIdentity {
            return values().find { it.roomId.equals(id, ignoreCase = true) } ?: CHAT
        }
    }
}

/**
 * Authoritative OS-level Execution State of a Conversation.
 */
enum class ConversationExecutionState {
    IDLE,
    PLANNING,
    EXECUTING,
    AWAITING_CONFIRMATION,
    DEBUGGING,
    VERIFYING,
    COMPLETED,
    FAILED,
    CANCELLED,
    EMERGENCY_STOPPED,
    PAUSED
}

/**
 * Universal User Confirmation Request.
 * Allows sensitive actions originating in any room/agent to be authorized from any room.
 */
data class PendingUserConfirmation(
    val confirmationId: String = UUID.randomUUID().toString(),
    val conversationId: String,
    val taskId: String? = null,
    val actionTitle: String,
    val actionDetails: String,
    val requiredPrivilege: String,
    val requestedByRoom: String,
    val createdAt: Long = System.currentTimeMillis(),
    val expiresAt: Long = System.currentTimeMillis() + (5 * 60 * 1000L),
    val isResolved: Boolean = false,
    val approved: Boolean = false,
    val resolvedByRoom: String? = null,
    val resolvedAt: Long? = null,
    val reason: String? = null
) {
    fun isExpired(): Boolean = !isResolved && System.currentTimeMillis() > expiresAt
}

/**
 * Contextual Continuation Intent capturing user request across room boundaries.
 */
data class ContinuationIntent(
    val continuationId: String = UUID.randomUUID().toString(),
    val parentTaskId: String? = null,
    val conversationId: String,
    val originRoom: String,
    val prompt: String,
    val parameters: Map<String, Any> = emptyMap(),
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Lightweight snapshot of recent conversation message for cross-room rendering.
 */
data class ConversationMessageSnapshot(
    val messageId: String = UUID.randomUUID().toString(),
    val role: String, // "user", "assistant", "system", "tool"
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val originRoom: String? = null
)

/**
 * Stage 18: Authoritative Universal Conversation Context.
 * Unifies AI Brain state across all rooms, nodes, and background execution services.
 */
data class UniversalConversationContext(
    val conversationId: String = "conv_default_universal",
    val taskId: String? = null,
    val correlationId: String = UUID.randomUUID().toString(),
    val requestId: String? = null,
    val origin: CommandOrigin = CommandOrigin.CHAT,
    val currentRoom: String = RoomIdentity.CHAT.roomId,
    val currentNode: String = "local_android_node",
    val activeExecutionState: ConversationExecutionState = ConversationExecutionState.IDLE,
    val currentAgent: String = "ceo_agent",
    val currentTool: String? = null,
    val currentCapability: String? = null,
    val conversationHistory: List<ConversationMessageSnapshot> = emptyList(),
    val executionHistoryRef: List<String> = emptyList(),
    val memoryReferences: List<String> = emptyList(),
    val pendingActions: List<String> = emptyList(),
    val activeConfirmations: List<PendingUserConfirmation> = emptyList(),
    val securityState: String = "AUTHENTICATED",
    val cancellationState: String = "ACTIVE",
    val createdAt: Long = System.currentTimeMillis(),
    val lastUserInteraction: String? = null,
    val lastExecutionEvent: String? = null,
    val lastUpdatedTimestamp: Long = System.currentTimeMillis(),
    val continuationMetadata: Map<String, String> = emptyMap()
)

/**
 * Unified event broadcasted across all room listeners.
 */
data class UniversalConversationEvent(
    val eventId: String = UUID.randomUUID().toString(),
    val conversationId: String,
    val taskId: String? = null,
    val originatingRoom: String,
    val originatingNode: String = "local_android_node",
    val executionPhase: String,
    val message: String,
    val severity: String = "INFO", // "INFO", "WARN", "ERROR", "CONFIRMATION_REQUIRED", "SUCCESS"
    val evidence: String? = null,
    val targetRooms: Set<String> = emptySet(), // Empty means broadcast to all rooms
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Synchronization snapshot returned to Web & Desktop Companions on connection.
 */
data class CompanionConversationSnapshot(
    val conversationContext: UniversalConversationContext,
    val recentEvents: List<UniversalConversationEvent>,
    val pendingConfirmations: List<PendingUserConfirmation>,
    val activeNodes: List<String>,
    val availableActions: List<String>,
    val generatedAt: Long = System.currentTimeMillis()
)
