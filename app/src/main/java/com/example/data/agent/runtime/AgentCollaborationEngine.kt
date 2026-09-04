package com.example.data.agent.runtime

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AgentCollaborationMessage(
    val senderAgentId: String,
    val receiverAgentId: String,
    val taskSummary: String,
    val sharedArtifactPayload: String,
    val timestampMs: Long = System.currentTimeMillis()
)

object AgentCollaborationEngine {

    private val _messageHistory = MutableStateFlow<List<AgentCollaborationMessage>>(emptyList())
    val messageHistory: StateFlow<List<AgentCollaborationMessage>> = _messageHistory.asStateFlow()

    fun delegateTask(
        fromAgent: String,
        toAgent: String,
        task: String,
        payload: String
    ): AgentCollaborationMessage {
        val msg = AgentCollaborationMessage(
            senderAgentId = fromAgent,
            receiverAgentId = toAgent,
            taskSummary = task,
            sharedArtifactPayload = payload
        )
        _messageHistory.value = _messageHistory.value + msg
        return msg
    }
}

object HumanApprovalGate {

    private val _pendingApprovals = MutableStateFlow<Map<String, String>>(emptyMap())
    val pendingApprovals: StateFlow<Map<String, String>> = _pendingApprovals.asStateFlow()

    fun requiresApproval(actionType: String, resourcePath: String): Boolean {
        val sensitiveActions = setOf("DELETE_DATABASE", "FORMAT_WORKSPACE", "MODIFY_SYSTEM_SETTINGS", "SEND_CREDENTIALS")
        return sensitiveActions.contains(actionType)
    }

    fun submitForApproval(actionId: String, description: String) {
        val current = _pendingApprovals.value.toMutableMap()
        current[actionId] = description
        _pendingApprovals.value = current
    }

    fun approveAction(actionId: String): Boolean {
        val current = _pendingApprovals.value.toMutableMap()
        val exists = current.remove(actionId) != null
        _pendingApprovals.value = current
        return exists
    }
}
