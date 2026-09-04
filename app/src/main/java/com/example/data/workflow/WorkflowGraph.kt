package com.example.data.workflow

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class WorkflowNode(
    val nodeId: String,
    val name: String,
    val actionType: String,
    val isParallel: Boolean = false,
    val nextNodeIds: List<String> = emptyList(),
    val conditionalEdge: ((Map<String, Any>) -> String)? = null
)

data class WorkflowGraphState(
    val graphId: String,
    val nodes: Map<String, WorkflowNode>,
    val currentNodeId: String?,
    val executionContext: Map<String, Any>,
    val status: String
)

class WorkflowGraph(
    val id: String,
    private val nodes: Map<String, WorkflowNode>,
    private val startNodeId: String
) {
    private val _graphState = MutableStateFlow(
        WorkflowGraphState(
            graphId = id,
            nodes = nodes,
            currentNodeId = startNodeId,
            executionContext = emptyMap(),
            status = "INITIALIZED"
        )
    )
    val graphState: StateFlow<WorkflowGraphState> = _graphState.asStateFlow()

    suspend fun step(stepOutput: Map<String, Any>): WorkflowGraphState {
        val current = _graphState.value
        val node = current.currentNodeId?.let { nodes[it] }

        if (node == null) {
            val finishedState = current.copy(status = "COMPLETED", currentNodeId = null)
            _graphState.value = finishedState
            return finishedState
        }

        val nextId = node.conditionalEdge?.invoke(stepOutput)
            ?: node.nextNodeIds.firstOrNull()

        val updatedContext = current.executionContext + stepOutput
        val nextState = current.copy(
            currentNodeId = nextId,
            executionContext = updatedContext,
            status = if (nextId == null) "COMPLETED" else "RUNNING"
        )
        _graphState.value = nextState
        return nextState
    }
}
