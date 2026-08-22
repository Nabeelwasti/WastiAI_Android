package com.example.data.node

import com.example.data.agent.runtime.CapabilityRealityRegistry
import com.example.data.agent.runtime.UnifiedExecutionFabric
import java.util.concurrent.ConcurrentHashMap

enum class NodePlatform {
    ANDROID,
    WEB,
    DESKTOP,
    TABLET,
    SERVER,
    CLOUD_WORKER,
    TERMUX,
    LOCAL_CONTAINER,
    IOT,
    ROBOT,
    FUTURE_NODE
}

enum class NodeTrustState {
    PAIRING,
    PAIRED,
    ACTIVE,
    SUSPENDED,
    REVOKED,
    DISCONNECTED
}

enum class NodeCapability {
    ANDROID_CONTROL,
    ACCESSIBILITY,
    FILESYSTEM,
    CAMERA,
    MICROPHONE,
    TERMINAL,
    PYTHON,
    KOTLIN,
    WEB_BROWSER,
    GPU_COMPUTE,
    CLOUD_COMPUTE,
    LOCAL_STORAGE,
    NETWORK,
    VIDEO_PROCESSING,
    DEEP_RESEARCH
}

enum class NodeHealthState {
    ONLINE,
    DEGRADED,
    OFFLINE,
    SUSPENDED,
    REVOKED
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
    val trustState: NodeTrustState = NodeTrustState.PAIRED,
    val healthState: NodeHealthState = NodeHealthState.ONLINE,
    val isLocal: Boolean = false,
    val endpointUrl: String? = null,
    val latencyMs: Long = 0L,
    val currentLoad: Float = 0.0f,
    val lastPingTimestamp: Long = System.currentTimeMillis()
)

enum class ExecutionDestination {
    LOCAL_DEVICE,
    REMOTE_DEVICE,
    DESKTOP_NODE,
    WEB_NODE,
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
        nodes.values.filter {
            it.connectionState == NodeConnectionState.CONNECTED &&
            (it.healthState == NodeHealthState.ONLINE || it.healthState == NodeHealthState.DEGRADED) &&
            it.trustState != NodeTrustState.REVOKED
        }

    fun recordHeartbeat(nodeId: String, latencyMs: Long = 0L, load: Float = 0.0f): Boolean {
        val node = nodes[nodeId] ?: return false
        val updated = node.copy(
            lastPingTimestamp = System.currentTimeMillis(),
            latencyMs = latencyMs,
            currentLoad = load,
            healthState = if (node.trustState == NodeTrustState.REVOKED) NodeHealthState.REVOKED else NodeHealthState.ONLINE,
            connectionState = NodeConnectionState.CONNECTED
        )
        nodes[nodeId] = updated
        return true
    }

    fun checkNodeHealth(heartbeatTimeoutMs: Long = 45000): List<WastiNode> {
        val now = System.currentTimeMillis()
        val updated = mutableListOf<WastiNode>()
        for ((id, node) in nodes) {
            if (node.isLocal) continue // Local node never times out
            val timeSincePing = now - node.lastPingTimestamp
            if (timeSincePing > heartbeatTimeoutMs * 2 && node.connectionState == NodeConnectionState.CONNECTED) {
                val marked = node.copy(
                    connectionState = NodeConnectionState.DISCONNECTED,
                    healthState = NodeHealthState.OFFLINE
                )
                nodes[id] = marked
                updated.add(marked)
            } else if (timeSincePing > heartbeatTimeoutMs && node.healthState == NodeHealthState.ONLINE) {
                val degraded = node.copy(healthState = NodeHealthState.DEGRADED)
                nodes[id] = degraded
                updated.add(degraded)
            }
        }
        return updated
    }

    fun recoverNode(nodeId: String): Boolean {
        val node = nodes[nodeId] ?: return false
        if (node.trustState == NodeTrustState.REVOKED) return false
        val recovered = node.copy(
            connectionState = NodeConnectionState.CONNECTED,
            healthState = NodeHealthState.ONLINE,
            lastPingTimestamp = System.currentTimeMillis()
        )
        nodes[nodeId] = recovered
        return true
    }

    fun updateNodeTrust(nodeId: String, newTrustState: NodeTrustState): Boolean {
        val node = nodes[nodeId] ?: return false
        val updated = node.copy(
            trustState = newTrustState,
            healthState = if (newTrustState == NodeTrustState.REVOKED) NodeHealthState.REVOKED else node.healthState
        )
        nodes[nodeId] = updated
        return true
    }

    /**
     * Resolves the optimal execution destination based on capability reality,
     * platform fitness, resource requirements, node health, and failure history.
     */
    fun routeTaskWithFailover(
        capabilityId: String,
        isHeavyCompute: Boolean = false,
        requiresCloudApi: Boolean = false,
        failedNodes: Set<String> = emptySet()
    ): ExecutionDestination {
        val norm = capabilityId.trim().lowercase()

        // 1. Explicit Python / Scripting
        if (norm == "python" || norm == "python_runtime" || norm == "run_python_script") {
            return ExecutionDestination.PYTHON_RUNTIME
        }

        // 2. Heavy Video/GPU or Desktop Compute (Route to Desktop if healthy and not failed)
        if (norm.contains("video_processing") || norm.contains("gpu") || (isHeavyCompute && norm.contains("compile_large_project"))) {
            val desktopNode = nodes.values.find {
                it.platform == NodePlatform.DESKTOP &&
                it.connectionState == NodeConnectionState.CONNECTED &&
                it.healthState != NodeHealthState.OFFLINE &&
                it.trustState != NodeTrustState.REVOKED &&
                !failedNodes.contains(it.nodeId)
            }
            if (desktopNode != null) {
                return ExecutionDestination.DESKTOP_NODE
            }
            // Failover: if Desktop unavailable, fallback to Cloud if allowed, else local
            if (requiresCloudApi || isHeavyCompute) {
                return ExecutionDestination.CLOUD
            }
        }

        // 3. Web Browser Tasks (Route to Web Node if healthy and not failed)
        if (norm == "web_browser" || norm == "browser_automation") {
            val webNode = nodes.values.find {
                it.platform == NodePlatform.WEB &&
                it.connectionState == NodeConnectionState.CONNECTED &&
                it.healthState != NodeHealthState.OFFLINE &&
                it.trustState != NodeTrustState.REVOKED &&
                !failedNodes.contains(it.nodeId)
            }
            if (webNode != null) {
                return ExecutionDestination.WEB_NODE
            }
        }

        // 4. Cloud AI or Heavy Deep Search
        if (requiresCloudApi || norm == "gemini_ai" || norm == "deep_research" || (isHeavyCompute && norm.contains("cloud"))) {
            return ExecutionDestination.CLOUD
        }

        // 5. Termux POSIX Tools
        if (norm.startsWith("termux_") || norm == "pkg" || norm == "apt") {
            return ExecutionDestination.TERMUX
        }

        // 6. Sandboxed Code or Project Workspace Builds
        if (norm == "wasti_sandbox" || norm == "execute_in_sandbox") {
            return ExecutionDestination.SANDBOX
        }

        // 7. Default to Local Device Native Execution
        return ExecutionDestination.LOCAL_DEVICE
    }

    /**
     * Legacy wrapper for backward compatibility.
     */
    fun routeTaskToOptimalNode(
        capabilityId: String,
        isHeavyCompute: Boolean = false,
        requiresCloudApi: Boolean = false
    ): ExecutionDestination = routeTaskWithFailover(capabilityId, isHeavyCompute, requiresCloudApi)

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
