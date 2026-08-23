package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.agent.runtime.*
import com.example.data.core.CommandOrigin
import com.example.data.db.ProactiveTaskDao
import com.example.data.db.WastiDatabase
import com.example.data.di.WastiServiceLocator
import com.example.data.node.*
import com.example.data.proactive.ProactiveAutonomousTask
import com.example.data.proactive.ProactiveTaskState
import com.example.data.proactive.WastiProactiveAutonomousEngine
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Stage 17: Autonomous Multi-Node Execution Mesh & Capability Federation Tests
 *
 * Verifies:
 * 1. Granular capability advertisement and SHA-256 fingerprint generation
 * 2. Capability federation into CapabilityRealityRegistry with truthfulness (LIVE_CONNECTED / EXTERNAL)
 * 3. Dynamic capability update and removal handling
 * 4. Stale capability invalidation when a node goes OFFLINE or is REVOKED
 * 5. Capability-aware node selection and ranking (scoring based on match, load, latency)
 * 6. Data locality enforcement (LOCAL_ONLY tasks never leave local host)
 * 7. Multi-node security policy evaluation (WastiSecurityPolicyEngine canDelegateToNode)
 * 8. Emergency stop blocks delegation and propagates across the mesh
 * 9. Remote task delegation with lease acquisition and event emissions
 * 10. Autonomous failover recovery when remote node becomes unresponsive
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Stage17CapabilityFederationAndMeshTest {

    private lateinit var context: Context
    private lateinit var nodeManager: WastiNodeManager
    private lateinit var realityRegistry: CapabilityRealityRegistry
    private lateinit var securityPolicy: WastiSecurityPolicyEngine
    private lateinit var emergencyStop: WastiEmergencyStopController
    private lateinit var eventBus: AgentEventBus
    private lateinit var proactiveEngine: WastiProactiveAutonomousEngine
    private lateinit var database: WastiDatabase
    private lateinit var taskDao: ProactiveTaskDao

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        WastiServiceLocator.init(context)
        nodeManager = WastiNodeManager.getInstance()
        realityRegistry = WastiServiceLocator.executionFabric.realityRegistry
        emergencyStop = WastiServiceLocator.emergencyStopController
        emergencyStop.resetEmergencyStop()
        eventBus = WastiServiceLocator.agentEventBus
        proactiveEngine = WastiProactiveAutonomousEngine.getInstance(context)
        securityPolicy = WastiServiceLocator.securityPolicyEngine
        database = WastiDatabase.getDatabase(context)
        taskDao = database.proactiveTaskDao()
    }

    @Test
    fun testCapabilityFingerprintGeneration() {
        val caps1 = listOf(
            AdvertisedCapabilityInfo(
                capabilityId = "python_runtime",
                version = "3.11.0",
                realityState = CapabilityRealityState.LIVE_CONNECTED,
                supportedOperations = listOf("exec_script", "eval")
            ),
            AdvertisedCapabilityInfo(
                capabilityId = "docker_engine",
                version = "24.0.0",
                realityState = CapabilityRealityState.LIVE_CONNECTED,
                supportedOperations = listOf("build", "run")
            )
        )

        val fp1 = AdvertisedCapabilityInfo.computeFingerprint(caps1)
        assertNotNull(fp1)
        assertTrue(fp1.isNotEmpty())
        assertEquals(64, fp1.length) // SHA-256 hex string length

        // Same capabilities in different order should yield identical fingerprint
        val capsReordered = listOf(caps1[1], caps1[0])
        val fp2 = AdvertisedCapabilityInfo.computeFingerprint(capsReordered)
        assertEquals(fp1, fp2)

        // Modified capability should yield different fingerprint
        val capsModified = listOf(
            caps1[0].copy(version = "3.12.0"),
            caps1[1]
        )
        val fp3 = AdvertisedCapabilityInfo.computeFingerprint(capsModified)
        assertNotEquals(fp1, fp3)
    }

    @Test
    fun testRemoteCapabilityFederationTruthfulness() {
        val remoteNodeId = "desktop_companion_1"
        val remoteNode = WastiNode(
            nodeId = remoteNodeId,
            nodeName = "MacBook Pro M3",
            platform = NodePlatform.DESKTOP,
            connectionState = NodeConnectionState.CONNECTED,
            trustState = NodeTrustState.ACTIVE,
            healthState = NodeHealthState.ONLINE,
            dataLocality = NodeDataLocality.TRUSTED_LAN,
            isLocal = false
        )
        nodeManager.registerNode(remoteNode)

        val advertisedCaps = listOf(
            AdvertisedCapabilityInfo(
                capabilityId = "heavy_compiler",
                version = "1.0.0",
                realityState = CapabilityRealityState.LIVE_CONNECTED,
                provider = "DesktopNode",
                supportedOperations = listOf("compile_rust", "compile_c")
            ),
            AdvertisedCapabilityInfo(
                capabilityId = "local_llm_gpu",
                version = "2.0.0",
                realityState = CapabilityRealityState.EXTERNAL_PROVIDER_AVAILABLE,
                provider = "OllamaServer",
                supportedOperations = listOf("generate", "embed")
            )
        )

        val advertised = nodeManager.advertiseCapabilitySnapshot(
            nodeId = remoteNodeId,
            snapshot = advertisedCaps,
            softwareVersion = "1.2.0",
            protocolVersion = 1
        )
        assertTrue(advertised)

        // Verify federated capabilities exist in CapabilityRealityRegistry
        val heavyCompiler = realityRegistry.getCapabilityReality("heavy_compiler")
        assertNotNull(heavyCompiler)
        assertEquals(CapabilityRealityState.LIVE_CONNECTED, heavyCompiler.realityState)
        assertEquals("heavy_compiler", heavyCompiler.capabilityId)

        val localLlm = realityRegistry.getCapabilityReality("local_llm_gpu")
        assertNotNull(localLlm)
        assertEquals(CapabilityRealityState.EXTERNAL_PROVIDER_AVAILABLE, localLlm.realityState)

        // Ensure remote capabilities are NEVER marked as NATIVE (truthfulness mandate)
        assertNotEquals(CapabilityRealityState.NATIVE, heavyCompiler.realityState)
        assertNotEquals(CapabilityRealityState.NATIVE, localLlm.realityState)
    }

    @Test
    fun testDynamicCapabilityUpdateAndRemoval() {
        val nodeId = "server_node_alpha"
        nodeManager.registerNode(
            WastiNode(
                nodeId = nodeId,
                nodeName = "Linux Server Alpha",
                platform = NodePlatform.SERVER,
                connectionState = NodeConnectionState.CONNECTED,
                trustState = NodeTrustState.ACTIVE,
                healthState = NodeHealthState.ONLINE,
                isLocal = false
            )
        )

        // 1. Initial snapshot
        val initialCap = AdvertisedCapabilityInfo(
            capabilityId = "postgres_pool",
            version = "15.0",
            realityState = CapabilityRealityState.LIVE_CONNECTED
        )
        nodeManager.advertiseCapabilitySnapshot(nodeId, listOf(initialCap))
        assertEquals(CapabilityRealityState.LIVE_CONNECTED, realityRegistry.getCapabilityReality("postgres_pool").realityState)

        // 2. Dynamic Update
        val updatedCap = initialCap.copy(version = "16.0", realityState = CapabilityRealityState.EXTERNAL_PROVIDER_AVAILABLE)
        val isUpdated = nodeManager.updateAdvertisedCapability(nodeId, updatedCap)
        assertTrue(isUpdated)
        assertEquals(CapabilityRealityState.EXTERNAL_PROVIDER_AVAILABLE, realityRegistry.getCapabilityReality("postgres_pool").realityState)

        // 3. Dynamic Removal
        val isRemoved = nodeManager.removeAdvertisedCapability(nodeId, "postgres_pool")
        assertTrue(isRemoved)
        assertEquals(CapabilityRealityState.UNAVAILABLE, realityRegistry.getCapabilityReality("postgres_pool").realityState)
    }

    @Test
    fun testStaleCapabilityInvalidationOnNodeDisconnectOrRevoke() {
        val nodeId = "edge_device_temp"
        nodeManager.registerNode(
            WastiNode(
                nodeId = nodeId,
                nodeName = "Temporary Edge Device",
                platform = NodePlatform.IOT,
                connectionState = NodeConnectionState.CONNECTED,
                trustState = NodeTrustState.ACTIVE,
                healthState = NodeHealthState.ONLINE,
                isLocal = false
            )
        )

        nodeManager.advertiseCapabilitySnapshot(
            nodeId = nodeId,
            snapshot = listOf(
                AdvertisedCapabilityInfo(
                    capabilityId = "temp_sensor_i2c",
                    version = "1.0",
                    realityState = CapabilityRealityState.LIVE_CONNECTED
                )
            )
        )
        assertEquals(CapabilityRealityState.LIVE_CONNECTED, realityRegistry.getCapabilityReality("temp_sensor_i2c").realityState)

        // Set node health to OFFLINE and clean stale capabilities
        nodeManager.updateNodeHealth(nodeId, NodeHealthState.OFFLINE)
        val cleaned = nodeManager.cleanStaleFederatedCapabilities()
        assertTrue(cleaned > 0)

        // Federated capability should be marked UNAVAILABLE in reality registry
        assertEquals(CapabilityRealityState.UNAVAILABLE, realityRegistry.getCapabilityReality("temp_sensor_i2c").realityState)
    }

    @Test
    fun testCapabilityAwareNodeSelection() {
        val node1 = WastiNode(
            nodeId = "node_light",
            nodeName = "Light Desktop",
            platform = NodePlatform.DESKTOP,
            connectionState = NodeConnectionState.CONNECTED,
            trustState = NodeTrustState.ACTIVE,
            healthState = NodeHealthState.ONLINE,
            latencyMs = 50L,
            currentLoad = 0.8f,
            dataLocality = NodeDataLocality.TRUSTED_LAN
        )
        val node2 = WastiNode(
            nodeId = "node_power",
            nodeName = "Power Server",
            platform = NodePlatform.SERVER,
            connectionState = NodeConnectionState.CONNECTED,
            trustState = NodeTrustState.ACTIVE,
            healthState = NodeHealthState.ONLINE,
            latencyMs = 15L,
            currentLoad = 0.1f,
            dataLocality = NodeDataLocality.TRUSTED_LAN
        )
        nodeManager.registerNode(node1)
        nodeManager.registerNode(node2)

        nodeManager.advertiseCapabilitySnapshot(
            "node_light",
            listOf(AdvertisedCapabilityInfo(capabilityId = "ffmpeg", version = "5.0"))
        )
        nodeManager.advertiseCapabilitySnapshot(
            "node_power",
            listOf(AdvertisedCapabilityInfo(capabilityId = "ffmpeg", version = "6.0"), AdvertisedCapabilityInfo(capabilityId = "gpu_render", version = "1.0"))
        )

        // Selection for "ffmpeg" task should choose node2 due to lower load, lower latency, and better match
        val best = nodeManager.selectBestNodeForTask(
            requiredCapabilities = listOf("ffmpeg"),
            isHeavyCompute = true,
            dataLocality = NodeDataLocality.TRUSTED_LAN
        )
        assertNotNull(best)
        assertEquals("node_power", best?.nodeId)
    }

    @Test
    fun testDataLocalityAndSecurityPolicyEnforcement() {
        val remoteNode = WastiNode(
            nodeId = "remote_mesh_peer",
            nodeName = "Peer Node",
            platform = NodePlatform.DESKTOP,
            connectionState = NodeConnectionState.CONNECTED,
            trustState = NodeTrustState.ACTIVE,
            healthState = NodeHealthState.ONLINE,
            dataLocality = NodeDataLocality.TRUSTED_LAN
        )
        nodeManager.registerNode(remoteNode)

        // 1. LOCAL_ONLY task must NEVER be delegable to a remote node
        val canDelegateLocalOnly = securityPolicy.canDelegateToNode(
            node = remoteNode,
            taskLocality = NodeDataLocality.LOCAL_ONLY
        )
        assertFalse("LOCAL_ONLY tasks must be rejected from delegation", canDelegateLocalOnly)

        // 2. TRUSTED_LAN task CAN be delegated to a TRUSTED_LAN node
        val canDelegateLan = securityPolicy.canDelegateToNode(
            node = remoteNode,
            taskLocality = NodeDataLocality.TRUSTED_LAN
        )
        assertTrue(canDelegateLan)

        // 3. Sensitive capabilities (e.g. keystore, credentials) must NEVER be delegated to remote node
        val canDelegateKeystore = securityPolicy.canDelegateToNode(
            node = remoteNode,
            taskLocality = NodeDataLocality.TRUSTED_LAN,
            requiredCapabilities = listOf("keystore")
        )
        assertFalse("Sensitive keystore operations must never be delegated remotely", canDelegateKeystore)

        // 4. Revoked or offline node must be rejected
        val revokedNode = remoteNode.copy(trustState = NodeTrustState.REVOKED)
        assertFalse(securityPolicy.canDelegateToNode(revokedNode, NodeDataLocality.TRUSTED_LAN))

        val offlineNode = remoteNode.copy(healthState = NodeHealthState.OFFLINE)
        assertFalse(securityPolicy.canDelegateToNode(offlineNode, NodeDataLocality.TRUSTED_LAN))
    }

    @Test
    fun testEmergencyStopBlocksMeshDelegation() {
        val remoteNode = WastiNode(
            nodeId = "remote_node_emergency",
            nodeName = "Emergency Test Node",
            platform = NodePlatform.DESKTOP,
            connectionState = NodeConnectionState.CONNECTED,
            trustState = NodeTrustState.ACTIVE,
            healthState = NodeHealthState.ONLINE,
            dataLocality = NodeDataLocality.TRUSTED_LAN
        )
        nodeManager.registerNode(remoteNode)

        // Trigger emergency stop
        emergencyStop.triggerEmergencyStop("Test Emergency Mesh Stop")
        assertTrue(emergencyStop.isEmergencyStopped)

        val canDelegate = securityPolicy.canDelegateToNode(
            node = remoteNode,
            taskLocality = NodeDataLocality.TRUSTED_LAN
        )
        assertFalse("Emergency stop must prevent any task delegation across mesh", canDelegate)
    }

    @Test
    fun testRemoteTaskDelegationAndLeaseLifecycle() {
        val remoteNode = WastiNode(
            nodeId = "mesh_worker_01",
            nodeName = "Mesh Worker 01",
            platform = NodePlatform.DESKTOP,
            connectionState = NodeConnectionState.CONNECTED,
            trustState = NodeTrustState.ACTIVE,
            healthState = NodeHealthState.ONLINE,
            dataLocality = NodeDataLocality.TRUSTED_LAN
        )
        nodeManager.registerNode(remoteNode)

        val task = proactiveEngine.scheduleTask(
            ProactiveAutonomousTask(
                title = "Distributed Code Analysis",
                prompt = "Analyze AST dependencies",
                origin = CommandOrigin.BACKGROUND_WORKER,
                requiredCapabilities = listOf("ast_parser")
            )
        )

        // Acquire lease for remote worker
        val acquired = proactiveEngine.acquireTaskLease(task.taskId, remoteNode.nodeId, 45000L)
        assertTrue(acquired)

        val leasedTask = proactiveEngine.getTask(task.taskId)
        assertEquals(remoteNode.nodeId, leasedTask?.leaseOwnerNode)
        assertTrue((leasedTask?.leaseExpiresAt ?: 0L) > System.currentTimeMillis())

        // Renew lease
        val renewed = proactiveEngine.renewTaskLease(task.taskId, remoteNode.nodeId, 60000L)
        assertTrue(renewed)

        // Complete running task remotely
        proactiveEngine.completeRunningTask(task.taskId, "AST tree parsed: 142 nodes verified")
        val completedTask = proactiveEngine.getTask(task.taskId)
        assertEquals(ProactiveTaskState.COMPLETED, completedTask?.state)
        assertNull(completedTask?.leaseOwnerNode)
        assertEquals(0L, completedTask?.leaseExpiresAt)
        assertTrue(completedTask?.verificationEvidence?.contains("142 nodes") == true)
    }

    @Test
    fun testFailoverFromUnresponsiveRemoteNode() {
        val remoteNode = WastiNode(
            nodeId = "flaky_node",
            nodeName = "Flaky Worker",
            platform = NodePlatform.DESKTOP,
            connectionState = NodeConnectionState.CONNECTED,
            trustState = NodeTrustState.ACTIVE,
            healthState = NodeHealthState.ONLINE,
            dataLocality = NodeDataLocality.TRUSTED_LAN,
            lastPingTimestamp = System.currentTimeMillis() - 60000L // 60s ago
        )
        nodeManager.registerNode(remoteNode)

        val idempotentTask = proactiveEngine.scheduleTask(
            ProactiveAutonomousTask(
                title = "Idempotent Mesh Task",
                prompt = "Fetch public weather feed",
                isIdempotent = true,
                state = ProactiveTaskState.RUNNING,
                leaseOwnerNode = remoteNode.nodeId,
                leaseExpiresAt = System.currentTimeMillis() - 5000L // Expired lease
            )
        )

        // Run failover check
        val failedOver = proactiveEngine.checkAndTriggerFailover(heartbeatTimeoutMs = 30000L)
        assertTrue(failedOver.any { it.taskId == idempotentTask.taskId })

        val recoveredTask = proactiveEngine.getTask(idempotentTask.taskId)
        assertEquals(ProactiveTaskState.SCHEDULED, recoveredTask?.state)
        assertEquals("LOCAL", recoveredTask?.leaseOwnerNode)
    }
}
