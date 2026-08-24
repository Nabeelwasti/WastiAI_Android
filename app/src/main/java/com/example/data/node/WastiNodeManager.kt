package com.example.data.node

import android.util.Log
import com.example.data.agent.runtime.*
import com.example.data.di.WastiServiceLocator
import java.security.MessageDigest
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

enum class NodeDataLocality {
    LOCAL_ONLY,
    TRUSTED_LAN,
    TRUSTED_REMOTE,
    PUBLIC_REMOTE
}

data class AdvertisedCapabilityInfo(
    val capabilityId: String,
    val version: String = "1.0.0",
    val realityState: CapabilityRealityState = CapabilityRealityState.LIVE_CONNECTED,
    val provider: String = "RemoteNodeProvider",
    val supportedOperations: List<String> = emptyList(),
    val limitations: List<String> = emptyList(),
    val isLocallyExecutable: Boolean = true,
    val resourceRequirements: String = "LOW", // "LOW", "MEDIUM", "HIGH", "GPU"
    val lastVerifiedTimestamp: Long = System.currentTimeMillis()
) {
    companion object {
        fun computeFingerprint(capabilities: Collection<AdvertisedCapabilityInfo>): String {
            val sortedString = capabilities.sortedBy { it.capabilityId.lowercase() }
                .joinToString(";") { "${it.capabilityId}:${it.version}:${it.realityState.name}" }
            val digest = MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest(sortedString.toByteArray(Charsets.UTF_8))
            return hashBytes.joinToString("") { "%02x".format(it) }
        }
    }
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
    val capabilities: Set<String> = emptySet(),
    val connectionState: NodeConnectionState = NodeConnectionState.CONNECTED,
    val trustState: NodeTrustState = NodeTrustState.PAIRED,
    val healthState: NodeHealthState = NodeHealthState.ONLINE,
    val isLocal: Boolean = false,
    val endpointUrl: String? = null,
    val latencyMs: Long = 0L,
    val currentLoad: Float = 0.0f,
    val lastPingTimestamp: Long = System.currentTimeMillis(),
    val softwareVersion: String = "1.0.0",
    val protocolVersion: Int = 1,
    val capabilityFingerprint: String = "",
    val lastSyncTimestamp: Long = 0L,
    val networkAddress: String? = null,
    val advertisedCapabilities: Map<String, AdvertisedCapabilityInfo> = emptyMap(),
    val dataLocality: NodeDataLocality = if (isLocal) NodeDataLocality.LOCAL_ONLY else NodeDataLocality.TRUSTED_LAN
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
 * Stage 10/17: Multi-Device Node Transport, Resource-Aware Execution Fabric & Capability Federation Mesh.
 * Manages distributed Wasti nodes (Android, Web Companion, Desktop, Cloud, Termux)
 * and coordinates capability reality federation without platform lock-in.
 */
class WastiNodeManager(
    val realityRegistry: CapabilityRealityRegistry = UnifiedExecutionFabric.instance.realityRegistry,
    private val eventBus: AgentEventBus = WastiServiceLocator.agentEventBus
) {
    private val nodes = ConcurrentHashMap<String, WastiNode>()

    init {
        registerDefaultNodes()
    }

    fun clearAll() {
        nodes.clear()
        registerDefaultNodes()
    }

    private fun registerDefaultNodes() {
        // 1. Primary Local Android Node
        val localCaps = setOf(
            "terminal", "files", "device_control", "memory_search",
            "system_info", "search_web", "project_dev_manager", "wasti_sandbox"
        )
        val localAdvertised = localCaps.associateWith { capId ->
            AdvertisedCapabilityInfo(
                capabilityId = capId,
                version = "1.0.0",
                realityState = CapabilityRealityState.NATIVE,
                provider = "LocalAndroidHost",
                supportedOperations = listOf("execute", "inspect"),
                isLocallyExecutable = true
            )
        }
        registerNode(
            WastiNode(
                nodeId = "local_android_node",
                nodeName = "Wasti Local Android Host",
                platform = NodePlatform.ANDROID,
                capabilities = localCaps,
                advertisedCapabilities = localAdvertised,
                capabilityFingerprint = computeCapabilityFingerprint(localAdvertised.values),
                connectionState = NodeConnectionState.CONNECTED,
                isLocal = true,
                endpointUrl = "http://127.0.0.1:8080",
                dataLocality = NodeDataLocality.LOCAL_ONLY
            )
        )

        // 2. Local Termux Environment Node (if accessible)
        val termuxCaps = setOf("posix_cli", "pkg", "git", "python", "node")
        val termuxAdvertised = termuxCaps.associateWith { capId ->
            AdvertisedCapabilityInfo(
                capabilityId = capId,
                version = "1.0.0",
                realityState = CapabilityRealityState.NATIVE,
                provider = "TermuxNativeEnvironment",
                supportedOperations = listOf("execute_termux"),
                isLocallyExecutable = true
            )
        }
        registerNode(
            WastiNode(
                nodeId = "local_termux_node",
                nodeName = "Termux Native Linux Environment",
                platform = NodePlatform.TERMUX,
                capabilities = termuxCaps,
                advertisedCapabilities = termuxAdvertised,
                capabilityFingerprint = computeCapabilityFingerprint(termuxAdvertised.values),
                connectionState = NodeConnectionState.CONNECTED,
                isLocal = true,
                endpointUrl = "termux://ipc",
                dataLocality = NodeDataLocality.LOCAL_ONLY
            )
        )

        // 3. Wasti Cloud Worker Node (Optional / AI Tier)
        val cloudCaps = setOf("gemini_ai", "deep_research", "cloud_build", "gpu_compute")
        val cloudAdvertised = cloudCaps.associateWith { capId ->
            AdvertisedCapabilityInfo(
                capabilityId = capId,
                version = "1.0.0",
                realityState = CapabilityRealityState.EXTERNAL_PROVIDER_AVAILABLE,
                provider = "WastiCloudWorker",
                supportedOperations = listOf("cloud_execute"),
                resourceRequirements = "GPU",
                isLocallyExecutable = false
            )
        }
        registerNode(
            WastiNode(
                nodeId = "wasti_cloud_node",
                nodeName = "Wasti Distributed Cloud Worker",
                platform = NodePlatform.CLOUD_WORKER,
                capabilities = cloudCaps,
                advertisedCapabilities = cloudAdvertised,
                capabilityFingerprint = computeCapabilityFingerprint(cloudAdvertised.values),
                connectionState = NodeConnectionState.CONNECTED,
                isLocal = false,
                endpointUrl = "https://api.wasti.ai/cloud",
                dataLocality = NodeDataLocality.TRUSTED_REMOTE
            )
        )
    }

    fun registerNode(node: WastiNode) {
        val calculatedFingerprint = if (node.capabilityFingerprint.isBlank() && node.advertisedCapabilities.isNotEmpty()) {
            computeCapabilityFingerprint(node.advertisedCapabilities.values)
        } else {
            node.capabilityFingerprint
        }
        val nodeToStore = if (calculatedFingerprint != node.capabilityFingerprint) {
            node.copy(capabilityFingerprint = calculatedFingerprint)
        } else {
            node
        }
        nodes[nodeToStore.nodeId] = nodeToStore
        federateNodeCapabilities(nodeToStore)
    }

    fun unregisterNode(nodeId: String) {
        val removed = nodes.remove(nodeId)
        if (removed != null) {
            // Remove advertised capabilities from reality registry if no other node provides them
            for (capId in removed.advertisedCapabilities.keys) {
                removeFederatedCapabilityFromRegistry(capId, nodeId)
            }
            eventBus.tryEmit(
                AgentEvent.NodeMeshDisconnected(
                    nodeId = nodeId,
                    reason = "Node unregistered from mesh"
                )
            )
        }
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
                // Degrade reality for offline node capabilities
                for (capId in marked.advertisedCapabilities.keys) {
                    removeFederatedCapabilityFromRegistry(capId, id)
                }
                eventBus.tryEmit(
                    AgentEvent.NodeMeshDisconnected(
                        nodeId = id,
                        reason = "Heartbeat timeout ($timeSincePing ms)"
                    )
                )
            } else if (timeSincePing > heartbeatTimeoutMs && node.healthState == NodeHealthState.ONLINE) {
                val degraded = node.copy(healthState = NodeHealthState.DEGRADED)
                nodes[id] = degraded
                updated.add(degraded)
                eventBus.tryEmit(
                    AgentEvent.NodeStateChanged(
                        nodeId = id,
                        healthState = NodeHealthState.DEGRADED.name,
                        connectionState = node.connectionState.name
                    )
                )
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
        federateNodeCapabilities(recovered)
        eventBus.tryEmit(AgentEvent.NodeMeshReconnected(nodeId = nodeId))
        return true
    }

    fun updateNodeTrust(nodeId: String, newTrustState: NodeTrustState): Boolean {
        val node = nodes[nodeId] ?: return false
        val updated = node.copy(
            trustState = newTrustState,
            healthState = if (newTrustState == NodeTrustState.REVOKED) NodeHealthState.REVOKED else node.healthState
        )
        nodes[nodeId] = updated
        if (newTrustState == NodeTrustState.REVOKED) {
            for (capId in node.advertisedCapabilities.keys) {
                removeFederatedCapabilityFromRegistry(capId, nodeId)
            }
        } else if (newTrustState == NodeTrustState.ACTIVE || newTrustState == NodeTrustState.PAIRED) {
            federateNodeCapabilities(updated)
        }
        eventBus.tryEmit(
            AgentEvent.NodeTrustChanged(
                nodeId = nodeId,
                newTrustState = newTrustState.name
            )
        )
        return true
    }

    fun updateNodeHealth(nodeId: String, newHealthState: NodeHealthState): Boolean {
        val node = nodes[nodeId] ?: return false
        val updated = node.copy(healthState = newHealthState)
        nodes[nodeId] = updated
        if (newHealthState == NodeHealthState.OFFLINE || newHealthState == NodeHealthState.REVOKED) {
            for (capId in node.advertisedCapabilities.keys) {
                removeFederatedCapabilityFromRegistry(capId, nodeId)
            }
        } else if (newHealthState == NodeHealthState.ONLINE) {
            federateNodeCapabilities(updated)
        }
        eventBus.tryEmit(
            AgentEvent.NodeStateChanged(
                nodeId = nodeId,
                healthState = newHealthState.name,
                connectionState = node.connectionState.name
            )
        )
        return true
    }

    fun cleanStaleFederatedCapabilities(): Int {
        var cleanedCount = 0
        for ((nodeId, node) in nodes) {
            if (node.isLocal) continue
            if (node.healthState == NodeHealthState.OFFLINE || node.healthState == NodeHealthState.REVOKED || node.trustState == NodeTrustState.REVOKED) {
                for (capId in node.advertisedCapabilities.keys) {
                    removeFederatedCapabilityFromRegistry(capId, nodeId)
                    cleanedCount++
                }
            }
        }
        return cleanedCount
    }

    // ==========================================
    // STAGE 17: CAPABILITY FEDERATION & SYNC
    // ==========================================

    /**
     * Computes a deterministic SHA-256 fingerprint from a set of advertised capabilities.
     */
    fun computeCapabilityFingerprint(capabilities: Collection<AdvertisedCapabilityInfo>): String {
        val sortedString = capabilities.sortedBy { it.capabilityId.lowercase() }
            .joinToString(";") { "${it.capabilityId}:${it.version}:${it.realityState.name}" }
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(sortedString.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }.take(16)
    }

    /**
     * Advertises a complete snapshot of capabilities from a remote node into the mesh.
     */
    fun advertiseCapabilitySnapshot(
        nodeId: String,
        snapshot: List<AdvertisedCapabilityInfo>,
        softwareVersion: String = "1.0.0",
        protocolVersion: Int = 1
    ): Boolean {
        val node = nodes[nodeId] ?: run {
            Log.w(TAG, "Cannot advertise capabilities: Node $nodeId not found")
            return false
        }

        if (node.trustState == NodeTrustState.REVOKED) {
            Log.w(TAG, "Cannot advertise capabilities: Node $nodeId is REVOKED")
            return false
        }

        val startTime = System.currentTimeMillis()
        val capMap = snapshot.associateBy { it.capabilityId }
        val fingerprint = computeCapabilityFingerprint(snapshot)

        val updated = node.copy(
            advertisedCapabilities = capMap,
            capabilities = capMap.keys,
            capabilityFingerprint = fingerprint,
            softwareVersion = softwareVersion,
            protocolVersion = protocolVersion,
            lastSyncTimestamp = System.currentTimeMillis(),
            lastPingTimestamp = System.currentTimeMillis(),
            connectionState = NodeConnectionState.CONNECTED,
            healthState = if (node.healthState == NodeHealthState.OFFLINE) NodeHealthState.ONLINE else node.healthState
        )
        nodes[nodeId] = updated
        federateNodeCapabilities(updated)

        eventBus.tryEmit(
            AgentEvent.NodeSyncCompleted(
                nodeId = nodeId,
                capabilitiesCount = snapshot.size,
                durationMs = System.currentTimeMillis() - startTime
            )
        )
        return true
    }

    /**
     * Advertises or updates a single capability delta from a node.
     */
    fun updateAdvertisedCapability(nodeId: String, capability: AdvertisedCapabilityInfo): Boolean {
        val node = nodes[nodeId] ?: return false
        if (node.trustState == NodeTrustState.REVOKED) return false

        val newMap = node.advertisedCapabilities.toMutableMap()
        newMap[capability.capabilityId] = capability
        val fingerprint = computeCapabilityFingerprint(newMap.values)

        val updated = node.copy(
            advertisedCapabilities = newMap,
            capabilities = newMap.keys,
            capabilityFingerprint = fingerprint,
            lastSyncTimestamp = System.currentTimeMillis()
        )
        nodes[nodeId] = updated
        federateSingleCapability(node, capability)

        eventBus.tryEmit(
            AgentEvent.NodeCapabilityUpdated(
                nodeId = nodeId,
                capabilityId = capability.capabilityId,
                status = capability.realityState.name
            )
        )
        return true
    }

    /**
     * Removes an advertised capability from a node.
     */
    fun removeAdvertisedCapability(nodeId: String, capabilityId: String): Boolean {
        val node = nodes[nodeId] ?: return false
        val newMap = node.advertisedCapabilities.toMutableMap()
        val removed = newMap.remove(capabilityId) ?: return false
        val fingerprint = computeCapabilityFingerprint(newMap.values)

        val updated = node.copy(
            advertisedCapabilities = newMap,
            capabilities = newMap.keys,
            capabilityFingerprint = fingerprint,
            lastSyncTimestamp = System.currentTimeMillis()
        )
        nodes[nodeId] = updated
        removeFederatedCapabilityFromRegistry(capabilityId, nodeId)

        eventBus.tryEmit(
            AgentEvent.NodeCapabilityRemoved(
                nodeId = nodeId,
                capabilityId = capabilityId
            )
        )
        return true
    }

    /**
     * Federates all valid capabilities of a node into the central CapabilityRealityRegistry.
     * Enforces Truthfulness: Remote capabilities are recorded as LIVE_CONNECTED or
     * EXTERNAL_PROVIDER_AVAILABLE, never falsely elevated to NATIVE.
     */
    private fun federateNodeCapabilities(node: WastiNode) {
        if (node.isLocal || node.trustState == NodeTrustState.REVOKED || node.healthState == NodeHealthState.OFFLINE) {
            return
        }

        for (cap in node.advertisedCapabilities.values) {
            federateSingleCapability(node, cap)
        }
    }

    private fun federateSingleCapability(node: WastiNode, cap: AdvertisedCapabilityInfo) {
        if (node.isLocal) return

        // Truthful Reality Check: Remote node capabilities cannot claim NATIVE status on local host
        val truthfulReality = when (cap.realityState) {
            CapabilityRealityState.NATIVE -> CapabilityRealityState.LIVE_CONNECTED
            else -> cap.realityState
        }

        val reality = CapabilityReality(
            capabilityId = cap.capabilityId,
            category = "FEDERATED_${node.platform.name}",
            implementationStatus = ImplementationStatus.READY,
            liveConnectionStatus = if (node.healthState == NodeHealthState.ONLINE) LiveConnectionStatus.VERIFIED else LiveConnectionStatus.NOT_VERIFIED,
            executionStatus = if (node.healthState == NodeHealthState.ONLINE) CapabilityExecutionStatus.OPERATIONAL else CapabilityExecutionStatus.DEGRADED,
            authenticationStatus = CapabilityAuthStatus.AUTHENTICATED,
            provider = "Node[${node.nodeId}:${node.nodeName}]",
            supportedOperations = cap.supportedOperations.ifEmpty { listOf("remote_execute") },
            limitations = cap.limitations + listOf("Federated via mesh node ${node.nodeId}"),
            realityState = truthfulReality,
            lastVerifiedAt = cap.lastVerifiedTimestamp
        )

        realityRegistry.updateCapabilityReality(reality)
        eventBus.tryEmit(
            AgentEvent.NodeCapabilityAdvertised(
                nodeId = node.nodeId,
                capabilityId = cap.capabilityId,
                realityState = truthfulReality.name
            )
        )
    }

    private fun removeFederatedCapabilityFromRegistry(capabilityId: String, nodeId: String) {
        // Check if any other online node still advertises this capability
        val otherNodeHasCap = nodes.values.any {
            it.nodeId != nodeId &&
            it.connectionState == NodeConnectionState.CONNECTED &&
            it.healthState == NodeHealthState.ONLINE &&
            it.trustState != NodeTrustState.REVOKED &&
            it.advertisedCapabilities.containsKey(capabilityId)
        }

        if (!otherNodeHasCap && !realityRegistry.getCapabilityReality(capabilityId).provider.contains("Local")) {
            // Downgrade to unavailable in registry
            val current = realityRegistry.getCapabilityReality(capabilityId)
            if (current.provider.contains("Node[$nodeId")) {
                realityRegistry.updateCapabilityReality(
                    current.copy(
                        liveConnectionStatus = LiveConnectionStatus.DISCONNECTED,
                        executionStatus = CapabilityExecutionStatus.UNAVAILABLE,
                        realityState = CapabilityRealityState.UNAVAILABLE
                    )
                )
            }
        }
    }

    /**
     * Capability-Aware Mesh Node Selection for Task Delegation.
     * Evaluates capability requirements, node trust, health, load, latency, and data locality.
     */
    fun selectBestNodeForTask(
        requiredCapabilities: List<String>,
        isHeavyCompute: Boolean = false,
        requiresCloudApi: Boolean = false,
        dataLocality: NodeDataLocality = NodeDataLocality.LOCAL_ONLY,
        excludedNodes: Set<String> = emptySet()
    ): WastiNode? {
        // 1. If data locality is LOCAL_ONLY, return the local host
        if (dataLocality == NodeDataLocality.LOCAL_ONLY) {
            return nodes.values.find { it.isLocal && it.platform == NodePlatform.ANDROID }
        }

        val eligibleNodes = nodes.values.filter { node ->
            !excludedNodes.contains(node.nodeId) &&
            node.connectionState == NodeConnectionState.CONNECTED &&
            (node.healthState == NodeHealthState.ONLINE || node.healthState == NodeHealthState.DEGRADED) &&
            (node.trustState == NodeTrustState.ACTIVE || node.trustState == NodeTrustState.PAIRED) &&
            (dataLocality != NodeDataLocality.TRUSTED_LAN || node.dataLocality == NodeDataLocality.TRUSTED_LAN || node.isLocal) &&
            requiredCapabilities.all { req ->
                node.capabilities.contains(req) ||
                node.advertisedCapabilities.containsKey(req) ||
                node.isLocal // Local host fallback
            }
        }

        if (eligibleNodes.isEmpty()) {
            return nodes.values.find { it.isLocal }
        }

        // Rank by: Local preference if light, remote if heavy compute, lowest load, lowest latency
        return eligibleNodes.minByOrNull { node ->
            var score = node.currentLoad * 100.0f + (node.latencyMs.toFloat() / 10.0f)
            if (isHeavyCompute && (node.platform == NodePlatform.DESKTOP || node.platform == NodePlatform.SERVER || node.platform == NodePlatform.CLOUD_WORKER)) {
                score -= 500.0f // Strongly favor desktop/server/cloud for heavy compute
            }
            if (node.isLocal && !isHeavyCompute) {
                score -= 200.0f // Favor local node for normal latency-sensitive tasks
            }
            score
        }
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
        private const val TAG = "WastiNodeManager"

        @Volatile
        private var instance: WastiNodeManager? = null

        fun getInstance(): WastiNodeManager {
            return instance ?: synchronized(this) {
                instance ?: WastiNodeManager().also { instance = it }
            }
        }
    }
}

