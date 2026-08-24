package com.example.data.mesh

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.agent.runtime.*
import com.example.data.core.CommandOrigin
import com.example.data.core.CommandSubmissionResult
import com.example.data.core.WastiOSRuntime
import com.example.data.di.WastiServiceLocator
import com.example.data.node.*
import com.example.data.server.WastiWebSocketServer
import com.example.data.transport.WastiCommandTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Stage18CrossPlatformBinaryMeshTest {

    private lateinit var context: Context
    private lateinit var nodeManager: WastiNodeManager
    private lateinit var diagnosticEngine: WastiNodeDiagnosticEngine
    private lateinit var emergencyStop: WastiEmergencyStopController
    private lateinit var realityRegistry: CapabilityRealityRegistry
    private lateinit var replayGuard: MeshReplayAndIdempotencyGuard
    private lateinit var meshTransport: WebSocketMeshTransport

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        WastiServiceLocator.init(context)

        emergencyStop = WastiServiceLocator.emergencyStopController
        emergencyStop.resetEmergencyStop()

        realityRegistry = WastiServiceLocator.realityRegistry
        nodeManager = WastiNodeManager.getInstance()
        nodeManager.clearAll()

        diagnosticEngine = WastiNodeDiagnosticEngine(
            nodeManager = nodeManager,
            realityRegistry = realityRegistry,
            emergencyStop = emergencyStop
        )

        replayGuard = MeshReplayAndIdempotencyGuard()
        meshTransport = WebSocketMeshTransport(
            webSocketServer = WastiWebSocketServer.getInstance(),
            commandTransport = WastiCommandTransport.getInstance(context),
            nodeManager = nodeManager,
            replayGuard = replayGuard,
            emergencyStop = emergencyStop
        )
    }

    @Test
    fun testBinaryProtocolSerializationRoundtrip() {
        val payload = "HELLO_WASTI_MESH_2026".toByteArray(Charsets.UTF_8)
        val envelope = WastiMeshEnvelope(
            protocolVersion = 2,
            messageType = WastiMeshMessageType.HELLO,
            flags = 0,
            timestamp = System.currentTimeMillis(),
            sequenceNumber = 1L,
            messageId = UUID.randomUUID().toString(),
            requestId = "req_12345",
            correlationId = "corr_98765",
            senderNodeId = "desktop_node_alpha",
            sessionToken = "valid_token_xyz",
            payloadBytes = payload,
            integrityHash = WastiMeshEnvelope.computeCrc32(payload)
        )

        val serializedBytes = WastiBinaryProtocolSerializer.serialize(envelope)
        assertNotNull(serializedBytes)
        assertTrue(serializedBytes.isNotEmpty())

        val deserializedResult = WastiBinaryProtocolSerializer.deserialize(serializedBytes)
        assertTrue(deserializedResult.isSuccess)

        val decoded = deserializedResult.getOrThrow()
        assertEquals(envelope.protocolVersion, decoded.protocolVersion)
        assertEquals(envelope.messageType, decoded.messageType)
        assertEquals(envelope.messageId, decoded.messageId)
        assertEquals(envelope.requestId, decoded.requestId)
        assertEquals(envelope.correlationId, decoded.correlationId)
        assertEquals(envelope.senderNodeId, decoded.senderNodeId)
        assertEquals(envelope.sessionToken, decoded.sessionToken)
        assertArrayEquals(envelope.payloadBytes, decoded.payloadBytes)
        assertEquals(envelope.integrityHash, decoded.integrityHash)
    }

    @Test
    fun testBinaryProtocolTamperDetectionRejection() {
        val payload = "ORIGINAL_PAYLOAD".toByteArray(Charsets.UTF_8)
        val envelope = WastiMeshEnvelope(
            protocolVersion = 2,
            messageType = WastiMeshMessageType.COMMAND_SUBMIT,
            senderNodeId = "remote_node",
            payloadBytes = payload,
            integrityHash = WastiMeshEnvelope.computeCrc32(payload)
        )

        val serializedBytes = WastiBinaryProtocolSerializer.serialize(envelope)

        // Corrupt the last byte of payload
        serializedBytes[serializedBytes.size - 1] = (serializedBytes[serializedBytes.size - 1] + 1).toByte()

        val result = WastiBinaryProtocolSerializer.deserialize(serializedBytes)
        assertTrue("Corrupted payload must be rejected", result.isFailure)
        assertTrue(result.exceptionOrNull() is SecurityException)
    }

    @Test
    fun testReplayAndIdempotencyGuard() {
        val messageId = UUID.randomUUID().toString()
        val envelope = WastiMeshEnvelope(
            messageType = WastiMeshMessageType.HEARTBEAT,
            timestamp = System.currentTimeMillis(),
            messageId = messageId,
            senderNodeId = "test_node"
        )

        // First message should be accepted
        val firstCheck = replayGuard.validateAndRecordMessage(envelope)
        assertTrue(firstCheck.isSuccess)

        // Duplicate message with same messageId must be rejected
        val secondCheck = replayGuard.validateAndRecordMessage(envelope)
        assertTrue(secondCheck.isFailure)
        assertTrue(secondCheck.exceptionOrNull() is SecurityException)

        // Expired timestamp (> 5 minutes ago) must be rejected
        val staleEnvelope = envelope.copy(
            messageId = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis() - (10 * 60 * 1000L)
        )
        val staleCheck = replayGuard.validateAndRecordMessage(staleEnvelope)
        assertTrue(staleCheck.isFailure)
    }

    @Test
    fun testProtocolVersionNegotiation() {
        // Version 2 should be preferred compatible
        val res2 = MeshProtocolNegotiator.negotiate(2)
        assertTrue(res2 is MeshProtocolNegotiator.NegotiationResult.Compatible)
        assertEquals(2, (res2 as MeshProtocolNegotiator.NegotiationResult.Compatible).negotiatedVersion)
        assertTrue(res2.isPreferred)

        // Version 1 should be backward compatible
        val res1 = MeshProtocolNegotiator.negotiate(1)
        assertTrue(res1 is MeshProtocolNegotiator.NegotiationResult.Compatible)
        assertEquals(1, (res1 as MeshProtocolNegotiator.NegotiationResult.Compatible).negotiatedVersion)
        assertFalse(res1.isPreferred)

        // Future Version 3 should fallback to Version 2
        val res3 = MeshProtocolNegotiator.negotiate(3)
        assertTrue(res3 is MeshProtocolNegotiator.NegotiationResult.Compatible)
        assertEquals(2, (res3 as MeshProtocolNegotiator.NegotiationResult.Compatible).negotiatedVersion)

        // Invalid version 0 should be incompatible
        val res0 = MeshProtocolNegotiator.negotiate(0)
        assertTrue(res0 is MeshProtocolNegotiator.NegotiationResult.Incompatible)
    }

    @Test
    fun testCapabilityDeltaComputationAndSerialization() {
        val currentCaps = mapOf(
            "cap_a" to AdvertisedCapabilityInfo("cap_a", "1.0.0", CapabilityRealityState.LIVE_CONNECTED),
            "cap_b" to AdvertisedCapabilityInfo("cap_b", "1.0.0", CapabilityRealityState.LIVE_CONNECTED)
        )

        val targetCaps = mapOf(
            "cap_b" to AdvertisedCapabilityInfo("cap_b", "1.1.0", CapabilityRealityState.LIVE_CONNECTED), // Modified
            "cap_c" to AdvertisedCapabilityInfo("cap_c", "1.0.0", CapabilityRealityState.LIVE_CONNECTED)  // Added
            // cap_a is Removed
        )

        val delta = CapabilityDelta.computeDelta(currentCaps, targetCaps, "target_fp_123")
        assertEquals(1, delta.added.size)
        assertEquals("cap_c", delta.added[0].capabilityId)

        assertEquals(1, delta.modified.size)
        assertEquals("cap_b", delta.modified[0].capabilityId)
        assertEquals("1.1.0", delta.modified[0].version)

        assertEquals(1, delta.removedCapabilityIds.size)
        assertEquals("cap_a", delta.removedCapabilityIds[0])

        // Roundtrip JSON test
        val json = delta.toJson()
        val deserialized = CapabilityDelta.fromJson(json)
        assertEquals("target_fp_123", deserialized.targetFingerprint)
        assertEquals(1, deserialized.added.size)
        assertEquals(1, deserialized.modified.size)
        assertEquals(1, deserialized.removedCapabilityIds.size)
    }

    @Test
    fun testTruthfulNodeDiagnosticsEngine() {
        // Register a healthy desktop node
        val desktopNode = WastiNode(
            nodeId = "desktop_rtx_4090",
            nodeName = "Ubuntu Workstation",
            platform = NodePlatform.DESKTOP,
            capabilities = setOf("gpu_compute", "python", "docker"),
            advertisedCapabilities = mapOf(
                "gpu_compute" to AdvertisedCapabilityInfo("gpu_compute", "1.0.0", CapabilityRealityState.LIVE_CONNECTED),
                "python" to AdvertisedCapabilityInfo("python", "1.0.0", CapabilityRealityState.LIVE_CONNECTED)
            ),
            connectionState = NodeConnectionState.CONNECTED,
            healthState = NodeHealthState.ONLINE,
            trustState = NodeTrustState.ACTIVE,
            latencyMs = 15L,
            currentLoad = 0.2f,
            dataLocality = NodeDataLocality.TRUSTED_LAN
        )
        nodeManager.registerNode(desktopNode)

        // 1. Diagnose eligible node for GPU compute on TRUSTED_LAN
        val report1 = diagnosticEngine.diagnoseNodeEligibility(
            nodeId = "desktop_rtx_4090",
            requiredCapabilities = listOf("gpu_compute"),
            dataLocality = NodeDataLocality.TRUSTED_LAN
        )
        assertTrue(report1.isEligible)
        assertTrue(report1.explanation.contains("fully healthy"))

        // 2. Diagnose LOCAL_ONLY constraint rejection
        val reportLocalOnly = diagnosticEngine.diagnoseNodeEligibility(
            nodeId = "desktop_rtx_4090",
            requiredCapabilities = listOf("gpu_compute"),
            dataLocality = NodeDataLocality.LOCAL_ONLY
        )
        assertFalse(reportLocalOnly.isEligible)
        assertTrue(reportLocalOnly.reasons.any { it.code == DiagnosticReasonCode.DATA_LOCALITY_RESTRICTION })

        // 3. Diagnose Missing Capability
        val reportMissingCap = diagnosticEngine.diagnoseNodeEligibility(
            nodeId = "desktop_rtx_4090",
            requiredCapabilities = listOf("quantum_teleportation"),
            dataLocality = NodeDataLocality.TRUSTED_LAN
        )
        assertFalse(reportMissingCap.isEligible)
        assertTrue(reportMissingCap.reasons.any { it.code == DiagnosticReasonCode.CAPABILITY_UNAVAILABLE })

        // 4. Diagnose Emergency Stop Block
        emergencyStop.triggerEmergencyStop("Test Stop Trigger")
        val reportEmergency = diagnosticEngine.diagnoseNodeEligibility(
            nodeId = "desktop_rtx_4090",
            requiredCapabilities = listOf("gpu_compute"),
            dataLocality = NodeDataLocality.TRUSTED_LAN
        )
        assertFalse(reportEmergency.isEligible)
        assertTrue(reportEmergency.reasons.any { it.code == DiagnosticReasonCode.EMERGENCY_STOP_ACTIVE })
    }

    @Test
    fun testWebSocketMeshTransportIngressProcessing() = runBlocking {
        meshTransport.start()

        // 1. Process HELLO frame
        val helloPayload = "MacBook Pro Node".toByteArray(Charsets.UTF_8)
        val helloEnvelope = WastiMeshEnvelope(
            protocolVersion = 2,
            messageType = WastiMeshMessageType.HELLO,
            senderNodeId = "macbook_pro_01",
            payloadBytes = helloPayload,
            integrityHash = WastiMeshEnvelope.computeCrc32(helloPayload)
        )

        val helloBytes = WastiBinaryProtocolSerializer.serialize(helloEnvelope)
        val responseResult = meshTransport.processIncomingBinaryFrame(helloBytes, "192.168.1.120")
        assertTrue(responseResult.isSuccess)

        val ackEnvelope = responseResult.getOrThrow()
        assertNotNull(ackEnvelope)
        assertEquals(WastiMeshMessageType.HELLO_ACK, ackEnvelope?.messageType)

        // Verify node is registered in NodeManager
        val registeredNode = nodeManager.getNode("macbook_pro_01")
        assertNotNull(registeredNode)
        assertEquals("MacBook Pro Node", registeredNode?.nodeName)
        assertEquals(NodeConnectionState.CONNECTED, registeredNode?.connectionState)

        // 2. Process Heartbeat
        val pingEnvelope = WastiMeshEnvelope(
            protocolVersion = 2,
            messageType = WastiMeshMessageType.HEARTBEAT,
            senderNodeId = "macbook_pro_01"
        )
        val pingBytes = WastiBinaryProtocolSerializer.serialize(pingEnvelope)
        val pingResult = meshTransport.processIncomingBinaryFrame(pingBytes, "192.168.1.120")
        assertTrue(pingResult.isSuccess)
        assertEquals(WastiMeshMessageType.HEARTBEAT_ACK, pingResult.getOrThrow()?.messageType)

        meshTransport.stop()
    }
}
