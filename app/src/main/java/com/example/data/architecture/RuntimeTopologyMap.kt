package com.example.data.architecture

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Runtime Topology Map.
 *
 * Implements the World-Brain Hierarchy:
 * - Mobile Device: Acts as the primary Brain (Decisions, Routing, UI, Sovereignty)
 * - Nearby Devices (PC, Termux, LAN): Act as Muscles (Heavy compilation, GPU inference, Swarm offload)
 * - Cloud: Acts as the Extended Cortex (Massive deep research, multi-provider consensus)
 *
 * Dynamically tracks cluster node availability, offload state, and computational distribution.
 */
object RuntimeTopologyMap {

    enum class ComputeNodeRole {
        PRIMARY_BRAIN,        // Host Android device
        COMPUTE_MUSCLE,       // Nearby LAN/Mesh peer with GPU/CPU
        EXTENDED_CORTEX,      // Cloud API provider (Gemini, Groq, DeepSeek)
        LOCAL_SANDBOX         // WRE internal isolated process
    }

    data class TopologyNode(
        val nodeId: String,
        val displayName: String,
        val role: ComputeNodeRole,
        val isOnline: Boolean,
        val latencyMs: Long,
        val loadPercent: Float,
        val supportedCapabilities: List<String>
    )

    data class ClusterTopology(
        val primaryNode: TopologyNode,
        val muscleNodes: List<TopologyNode>,
        val cortexNodes: List<TopologyNode>,
        val activeOffloadNodeId: String? = null,
        val timestamp: Long = System.currentTimeMillis()
    )

    private val _topologyState = MutableStateFlow(
        ClusterTopology(
            primaryNode = TopologyNode(
                nodeId = "local_android_host",
                displayName = "Wasti Local Host (Android)",
                role = ComputeNodeRole.PRIMARY_BRAIN,
                isOnline = true,
                latencyMs = 0L,
                loadPercent = 25.0f,
                supportedCapabilities = listOf("inference_gguf", "accessibility", "vosk_wake", "sqlite_room")
            ),
            muscleNodes = listOf(
                TopologyNode(
                    nodeId = "wre_polyglot_sandbox",
                    displayName = "WRE Local Polyglot Engine",
                    role = ComputeNodeRole.LOCAL_SANDBOX,
                    isOnline = true,
                    latencyMs = 5L,
                    loadPercent = 10.0f,
                    supportedCapabilities = listOf("kotlin_eval", "python_eval", "bash_exec", "file_ops")
                )
            ),
            cortexNodes = listOf(
                TopologyNode(
                    nodeId = "cloud_cortex_groq",
                    displayName = "Groq LPU Cortex (Ultra Fast)",
                    role = ComputeNodeRole.EXTENDED_CORTEX,
                    isOnline = true,
                    latencyMs = 280L,
                    loadPercent = 5.0f,
                    supportedCapabilities = listOf("deep_reasoning", "multi_turn_synthesis")
                ),
                TopologyNode(
                    nodeId = "cloud_cortex_gemini",
                    displayName = "Google Gemini Cortex (Multimodal)",
                    role = ComputeNodeRole.EXTENDED_CORTEX,
                    isOnline = true,
                    latencyMs = 450L,
                    loadPercent = 5.0f,
                    supportedCapabilities = listOf("multimodal_vision", "deep_research")
                )
            )
        )
    )
    val topologyState: StateFlow<ClusterTopology> = _topologyState.asStateFlow()

    @Synchronized
    fun registerMuscleNode(node: TopologyNode) {
        val current = _topologyState.value
        val updated = current.muscleNodes.filter { it.nodeId != node.nodeId } + node
        _topologyState.value = current.copy(muscleNodes = updated)
    }

    @Synchronized
    fun setOffloadTarget(nodeId: String?) {
        _topologyState.value = _topologyState.value.copy(activeOffloadNodeId = nodeId)
    }

    fun getOptimalExecutionTarget(taskType: String): TopologyNode {
        val current = _topologyState.value
        return when {
            taskType.contains("heavy_compile") || taskType.contains("swarm_offload") ->
                current.muscleNodes.firstOrNull { it.isOnline } ?: current.primaryNode
            taskType.contains("deep_research") || taskType.contains("multimodal") ->
                current.cortexNodes.firstOrNull { it.isOnline } ?: current.primaryNode
            else ->
                current.primaryNode
        }
    }
}
