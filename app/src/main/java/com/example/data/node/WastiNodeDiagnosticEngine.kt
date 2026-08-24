package com.example.data.node

import com.example.data.agent.runtime.CapabilityRealityRegistry
import com.example.data.agent.runtime.CapabilityRealityState
import com.example.data.agent.runtime.UnifiedExecutionFabric
import com.example.data.agent.runtime.WastiEmergencyStopController
import com.example.data.di.WastiServiceLocator

/**
 * Stage 18: Mesh Telemetry Model.
 * Provides real-time visibility into distributed node status, health, latency, load, and capability reality.
 */
data class MeshTelemetry(
    val nodeId: String,
    val nodeName: String,
    val platform: NodePlatform,
    val trustState: NodeTrustState,
    val healthState: NodeHealthState,
    val connectionState: NodeConnectionState,
    val latencyMs: Long,
    val heartbeatAgeMs: Long,
    val load: Float,
    val activeTasks: Int = 0,
    val capabilitiesCount: Int = 0,
    val capabilityFingerprint: String = "",
    val lastSyncTimestamp: Long = 0L,
    val leaseCount: Int = 0,
    val failoverCount: Int = 0,
    val dataLocality: NodeDataLocality = NodeDataLocality.LOCAL_ONLY,
    val protocolVersion: Int = 1,
    val advertisedCapabilities: List<AdvertisedCapabilityInfo> = emptyList()
)

/**
 * Stage 18: Specific, Non-Generic Reasons for Node Ineligibility.
 */
enum class DiagnosticReasonCode {
    NODE_NOT_FOUND,
    OFFLINE,
    REVOKED,
    SUSPENDED,
    CAPABILITY_UNAVAILABLE,
    CAPABILITY_UNVERIFIED,
    INSUFFICIENT_RESOURCES,
    SECURITY_POLICY_BLOCK,
    DATA_LOCALITY_RESTRICTION,
    EXCESSIVE_LATENCY,
    ACTIVE_LEASE_CONFLICT,
    NODE_OVERLOADED,
    PROTOCOL_INCOMPATIBILITY,
    EMERGENCY_STOP_ACTIVE,
    ELIGIBLE
}

data class NodeDiagnosticReason(
    val code: DiagnosticReasonCode,
    val message: String
)

data class NodeDiagnosticReport(
    val nodeId: String,
    val isEligible: Boolean,
    val reasons: List<NodeDiagnosticReason>,
    val explanation: String
)

/**
 * Stage 18: Canonical Node Diagnostics & Telemetry Engine.
 * Answers with absolute truthfulness: "Why isn't this node being selected?"
 */
class WastiNodeDiagnosticEngine(
    private val nodeManager: WastiNodeManager = WastiNodeManager.getInstance(),
    private val realityRegistry: CapabilityRealityRegistry = UnifiedExecutionFabric.instance.realityRegistry,
    private val emergencyStop: WastiEmergencyStopController = WastiServiceLocator.emergencyStopController
) {

    fun diagnoseNodeEligibility(
        nodeId: String,
        requiredCapabilities: List<String> = emptyList(),
        isHeavyCompute: Boolean = false,
        dataLocality: NodeDataLocality = NodeDataLocality.LOCAL_ONLY,
        maxAcceptableLatencyMs: Long = 1000L
    ): NodeDiagnosticReport {
        val reasons = mutableListOf<NodeDiagnosticReason>()

        // 1. Emergency Stop Check
        if (emergencyStop.isEmergencyStopped) {
            reasons.add(
                NodeDiagnosticReason(
                    DiagnosticReasonCode.EMERGENCY_STOP_ACTIVE,
                    "Emergency Stop is active across the mesh. All task delegation is blocked."
                )
            )
        }

        // 2. Node Existence
        val node = nodeManager.getNode(nodeId)
        if (node == null) {
            return NodeDiagnosticReport(
                nodeId = nodeId,
                isEligible = false,
                reasons = listOf(
                    NodeDiagnosticReason(
                        DiagnosticReasonCode.NODE_NOT_FOUND,
                        "Node with id '$nodeId' is not registered in WastiNodeManager."
                    )
                ),
                explanation = "Node not found."
            )
        }

        // 3. Trust State Check
        when (node.trustState) {
            NodeTrustState.REVOKED -> reasons.add(
                NodeDiagnosticReason(
                    DiagnosticReasonCode.REVOKED,
                    "Node trust has been permanently revoked by administrator."
                )
            )
            NodeTrustState.SUSPENDED -> reasons.add(
                NodeDiagnosticReason(
                    DiagnosticReasonCode.SUSPENDED,
                    "Node trust is currently suspended."
                )
            )
            NodeTrustState.PAIRING -> reasons.add(
                NodeDiagnosticReason(
                    DiagnosticReasonCode.SECURITY_POLICY_BLOCK,
                    "Node is still in pairing challenge state and not yet authenticated."
                )
            )
            NodeTrustState.DISCONNECTED -> reasons.add(
                NodeDiagnosticReason(
                    DiagnosticReasonCode.OFFLINE,
                    "Node is disconnected."
                )
            )
            NodeTrustState.ACTIVE, NodeTrustState.PAIRED -> { /* OK */ }
        }

        // 4. Health & Connection State Check
        if (node.connectionState != NodeConnectionState.CONNECTED) {
            reasons.add(
                NodeDiagnosticReason(
                    DiagnosticReasonCode.OFFLINE,
                    "Node connection state is ${node.connectionState.name} (not CONNECTED)."
                )
            )
        }

        if (node.healthState == NodeHealthState.OFFLINE) {
            reasons.add(
                NodeDiagnosticReason(
                    DiagnosticReasonCode.OFFLINE,
                    "Node health is marked OFFLINE due to missed heartbeats."
                )
            )
        }

        // 5. Data Locality Enforcement
        if (dataLocality == NodeDataLocality.LOCAL_ONLY && !node.isLocal) {
            reasons.add(
                NodeDiagnosticReason(
                    DiagnosticReasonCode.DATA_LOCALITY_RESTRICTION,
                    "Task enforces LOCAL_ONLY data locality, but node '${node.nodeName}' is a remote node."
                )
            )
        }

        // 6. Capability Matching & Reality State
        for (req in requiredCapabilities) {
            val hasCap = node.capabilities.contains(req) || node.advertisedCapabilities.containsKey(req) || (node.isLocal)
            if (!hasCap) {
                reasons.add(
                    NodeDiagnosticReason(
                        DiagnosticReasonCode.CAPABILITY_UNAVAILABLE,
                        "Node lacks required capability '$req'."
                    )
                )
            } else {
                val advertised = node.advertisedCapabilities[req]
                if (advertised != null) {
                    if (advertised.realityState == CapabilityRealityState.UNAVAILABLE ||
                        advertised.realityState == CapabilityRealityState.FAILED
                    ) {
                        reasons.add(
                            NodeDiagnosticReason(
                                DiagnosticReasonCode.CAPABILITY_UNVERIFIED,
                                "Capability '$req' on node '${node.nodeName}' is in reality state ${advertised.realityState.name}."
                            )
                        )
                    }
                }
            }
        }

        // 7. Resource & Latency Thresholds
        if (node.currentLoad > 0.95f) {
            reasons.add(
                NodeDiagnosticReason(
                    DiagnosticReasonCode.NODE_OVERLOADED,
                    "Node CPU/Memory load (${(node.currentLoad * 100).toInt()}%) is near capacity limit."
                )
            )
        }

        if (node.latencyMs > maxAcceptableLatencyMs) {
            reasons.add(
                NodeDiagnosticReason(
                    DiagnosticReasonCode.EXCESSIVE_LATENCY,
                    "Node round-trip latency (${node.latencyMs}ms) exceeds threshold of ${maxAcceptableLatencyMs}ms."
                )
            )
        }

        // 8. Protocol Compatibility
        if (node.protocolVersion < 1) {
            reasons.add(
                NodeDiagnosticReason(
                    DiagnosticReasonCode.PROTOCOL_INCOMPATIBILITY,
                    "Node protocol version (${node.protocolVersion}) is unsupported."
                )
            )
        }

        val isEligible = reasons.isEmpty()
        val explanation = if (isEligible) {
            "Node '${node.nodeName}' is fully healthy, trusted, and eligible for execution."
        } else {
            "Node '${node.nodeName}' is ineligible: " + reasons.joinToString("; ") { it.message }
        }

        return NodeDiagnosticReport(
            nodeId = nodeId,
            isEligible = isEligible,
            reasons = if (isEligible) listOf(NodeDiagnosticReason(DiagnosticReasonCode.ELIGIBLE, "Node is eligible")) else reasons,
            explanation = explanation
        )
    }

    fun diagnoseAllNodes(
        requiredCapabilities: List<String> = emptyList(),
        isHeavyCompute: Boolean = false,
        dataLocality: NodeDataLocality = NodeDataLocality.LOCAL_ONLY
    ): Map<String, NodeDiagnosticReport> {
        return nodeManager.getAllNodes().associate { node ->
            node.nodeId to diagnoseNodeEligibility(node.nodeId, requiredCapabilities, isHeavyCompute, dataLocality)
        }
    }

    fun getMeshTelemetrySnapshot(): List<MeshTelemetry> {
        val now = System.currentTimeMillis()
        return nodeManager.getAllNodes().map { node ->
            val heartbeatAge = if (node.lastPingTimestamp > 0) now - node.lastPingTimestamp else 0L
            MeshTelemetry(
                nodeId = node.nodeId,
                nodeName = node.nodeName,
                platform = node.platform,
                trustState = node.trustState,
                healthState = node.healthState,
                connectionState = node.connectionState,
                latencyMs = node.latencyMs,
                heartbeatAgeMs = heartbeatAge,
                load = node.currentLoad,
                capabilitiesCount = if (node.advertisedCapabilities.isNotEmpty()) node.advertisedCapabilities.size else node.capabilities.size,
                capabilityFingerprint = node.capabilityFingerprint,
                lastSyncTimestamp = node.lastSyncTimestamp,
                dataLocality = node.dataLocality,
                protocolVersion = node.protocolVersion,
                advertisedCapabilities = node.advertisedCapabilities.values.toList()
            )
        }
    }

    companion object {
        @Volatile
        private var instance: WastiNodeDiagnosticEngine? = null

        fun getInstance(): WastiNodeDiagnosticEngine {
            return instance ?: synchronized(this) {
                instance ?: WastiNodeDiagnosticEngine().also { instance = it }
            }
        }
    }
}
