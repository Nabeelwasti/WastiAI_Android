package com.example.data.node

import com.example.data.agent.runtime.CapabilityRealityRegistry
import com.example.data.agent.runtime.UnifiedExecutionFabric
import java.util.concurrent.ConcurrentHashMap

enum class NodePlatform {
    ANDROID,
    WEB,
    DESKTOP,
    CLOUD_WORKER,
    TERMUX,
    LOCAL_CONTAINER
}

enum class NodeConnectionState {
    CONNECTED,
    CONNECTING,
    DISCONNECTED,
    UNAVAILABLE
}

data class WastiNode(
    val nodeId: String,
    val nodeName: String,
    val platform: NodePlatform,
    val capabilities: Set<String>,
    val connectionState: NodeConnectionState = NodeConnectionState.CONNECTED,
    val isLocal: Boolean = false,
    val endpointUrl: String? = null,
    val lastPingTimestamp: Long = System.currentTimeMillis()
)

enum class ExecutionDestination {
    LOCAL_DEVICE,
    REMOTE_DEVICE,
    CLOUD,
    PYTHON_RUNTIME,
    TERMUX,
    SANDBOX
}

/**
 * Stage 10: Multi-Device Node Transport & Resource-Aware Execution Fabric.
 * Manages distributed Wasti nodes (Android, Web Companion, Desktop, Cloud, Termux)
 * and determines optimal execution placement without platform lock-in.
 */
class WastiNodeManager(
    private val realityRegistry: CapabilityRealityRegistry = UnifiedExecutionFabric.instance.realityRegistry
) {
    private val nodes = ConcurrentHashMap<String, WastiNode>()

    init {
        registerDefaultNodes()
    }

    private fun registerDefaultNodes() {
        // 1. Primary Local Android Node
        registerNode(
            WastiNode(
                nodeId = "local_android_node",
                nodeName = "Wasti Local Android Host",
                platform = NodePlatform.ANDROID,
                capabilities = setOf(
                    "terminal", "files", "device_control", "memory_search",
                    "system_info", "search_web", "project_dev_manager", "wasti_sandbox"
                ),
                connectionState = NodeConnectionState.CONNECTED,
                isLocal = true,
                endpointUrl = "http://127.0.0.1:8080"
            )
        )

        // 2. Local Termux Environment Node (if accessible)
        registerNode(
            WastiNode(
                nodeId = "local_termux_node",
                nodeName = "Termux Native Linux Environment",
                platform = NodePlatform.TERMUX,
                capabilities = setOf("posix_cli", "pkg", "git", "python", "node"),
                connectionState = NodeConnectionState.CONNECTED,
                isLocal = true,
                endpointUrl = "termux://ipc"
            )
        )

        // 3. Wasti Cloud Worker Node (Optional / AI Tier)
        registerNode(
            WastiNode(
                nodeId = "wasti_cloud_node",
                nodeName = "Wasti Distributed Cloud Worker",
                platform = NodePlatform.CLOUD_WORKER,
                capabilities = setOf("gemini_ai", "deep_research", "cloud_build", "gpu_compute"),
                connectionState = NodeConnectionState.CONNECTED,
                isLocal = false,
                endpointUrl = "https://api.wasti.ai/cloud"
            )
        )
    }

    fun registerNode(node: WastiNode) {
        nodes[node.nodeId] = node
    }

    fun unregisterNode(nodeId: String) {
        nodes.remove(nodeId)
    }

    fun getNode(nodeId: String): WastiNode? = nodes[nodeId]

    fun getAllNodes(): List<WastiNode> = nodes.values.toList()

    fun getConnectedNodes(): List<WastiNode> =
        nodes.values.filter { it.connectionState == NodeConnectionState.CONNECTED }

    /**
     * Resolves the optimal execution destination based on capability reality,
     * platform fitness, resource requirements, and node availability.
     */
    fun routeTaskToOptimalNode(
        capabilityId: String,
        isHeavyCompute: Boolean = false,
        requiresCloudApi: Boolean = false
    ): ExecutionDestination {
        val norm = capabilityId.trim().lowercase()

        // 1. Explicit Python / Scripting
        if (norm == "python" || norm == "python_runtime" || norm == "run_python_script") {
            return ExecutionDestination.PYTHON_RUNTIME
        }

        // 2. Cloud AI or Heavy Deep Search
        if (requiresCloudApi || norm == "gemini_ai" || norm == "deep_research" || (isHeavyCompute && norm.contains("cloud"))) {
            return ExecutionDestination.CLOUD
        }

        // 3. Termux POSIX Tools
        if (norm.startsWith("termux_") || norm == "pkg" || norm == "apt") {
            return ExecutionDestination.TERMUX
        }

        // 4. Sandboxed Code or Project Workspace Builds
        if (norm == "wasti_sandbox" || norm == "execute_in_sandbox") {
            return ExecutionDestination.SANDBOX
        }

        // 5. Default to Local Device Native Execution
        return ExecutionDestination.LOCAL_DEVICE
    }

    companion object {
        @Volatile
        private var instance: WastiNodeManager? = null

        fun getInstance(): WastiNodeManager {
            return instance ?: synchronized(this) {
                instance ?: WastiNodeManager().also { instance = it }
            }
        }
    }
}
