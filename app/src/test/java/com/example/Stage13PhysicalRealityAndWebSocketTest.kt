package com.example

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import com.example.data.agent.runtime.AgenticState
import com.example.data.agent.runtime.CapabilityExecutionStatus
import com.example.data.agent.runtime.ImplementationStatus
import com.example.data.agent.runtime.LiveConnectionStatus
import com.example.data.agent.runtime.TaskId
import com.example.data.agent.runtime.UnifiedExecutionFabric
import com.example.data.core.CommandOrigin
import com.example.data.node.*
import com.example.data.server.WastiLocalServerManager
import com.example.data.server.WastiWebSocketServer
import com.example.data.transport.WastiCommandTransport
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.security.MessageDigest
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Stage13PhysicalRealityAndWebSocketTest {

    private lateinit var context: Context
    private lateinit var transport: WastiCommandTransport
    private lateinit var nodeManager: WastiNodeManager
    private lateinit var serverManager: WastiLocalServerManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        transport = WastiCommandTransport.getInstance(context)
        nodeManager = WastiNodeManager.getInstance()
        serverManager = WastiLocalServerManager.getInstance(context)
    }

    @Test
    fun testWebSocketRfc6455HandshakeAndFrames() {
        val wsServer = WastiWebSocketServer.getInstance(context)
        wsServer.stop()
        val startRes = wsServer.start(0)
        assertTrue(startRes.isSuccess)
        val port = startRes.getOrNull() ?: 9180

        try {
            val socket = Socket("127.0.0.1", port)
            val out = socket.getOutputStream()
            val inp = socket.getInputStream()

            // 1. Send RFC 6455 Handshake
            val testKey = "dGhlIHNhbXBsZSBub25jZQ=="
            val handshake = "GET /ws HTTP/1.1\r\n" +
                    "Host: 127.0.0.1:$port\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Key: $testKey\r\n" +
                    "Sec-WebSocket-Version: 13\r\n\r\n"

            out.write(handshake.toByteArray(Charsets.UTF_8))
            out.flush()

            // 2. Verify Handshake Response
            val responseBuffer = ByteArray(2048)
            val read = inp.read(responseBuffer)
            val responseStr = String(responseBuffer, 0, read, Charsets.UTF_8)

            assertTrue(responseStr.contains("101 Switching Protocols"))
            assertTrue(responseStr.contains("Upgrade: websocket"))
            val expectedAccept = computeExpectedAccept(testKey)
            assertTrue(responseStr.contains("Sec-WebSocket-Accept: $expectedAccept"))

            // 3. Read initial "CONNECTED" frame from server
            val connectedMsg = readServerFrame(inp)
            assertNotNull(connectedMsg)
            val connectedJson = JSONObject(connectedMsg!!)
            assertEquals("CONNECTED", connectedJson.optString("type"))
            assertEquals("RFC-6455", connectedJson.optString("protocol"))

            // 4. Send Client Frame: PING -> expect PONG
            sendClientMaskedFrame(out, JSONObject().put("type", "PING").toString())
            val pongMsg = readServerFrame(inp)
            assertNotNull(pongMsg)
            val pongJson = JSONObject(pongMsg!!)
            assertEquals("PONG", pongJson.optString("type"))

            socket.close()
        } finally {
            wsServer.stop()
        }
    }

    @Test
    fun testWebSocketAuthenticationAndCommandDispatch() {
        val wsServer = WastiWebSocketServer.getInstance(context)
        wsServer.stop()
        val startRes = wsServer.start(0)
        assertTrue(startRes.isSuccess)
        val port = startRes.getOrNull() ?: 9182

        // Pair a test device
        val challenge = transport.createPairingChallenge(
            deviceId = "ws_companion_node",
            deviceName = "WebSocket Companion Terminal",
            platform = NodePlatform.DESKTOP
        )
        val paired = transport.verifyPairingChallenge(challenge.code, "ws_companion_node")
        assertNotNull(paired)

        try {
            val socket = Socket("127.0.0.1", port)
            val out = socket.getOutputStream()
            val inp = socket.getInputStream()

            // Handshake
            val testKey = "x3JJHMbDL1EzLkh9GBhXDw=="
            out.write(("GET /ws HTTP/1.1\r\nHost: 127.0.0.1\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: $testKey\r\n\r\n").toByteArray())
            out.flush()

            val resp = ByteArray(2048)
            inp.read(resp) // discard handshake header

            // Read welcome
            readServerFrame(inp)

            // Send AUTHENTICATE frame
            val authPayload = JSONObject().apply {
                put("type", "AUTHENTICATE")
                put("token", paired!!.sessionToken)
                put("deviceId", "ws_companion_node")
                put("platform", "DESKTOP")
            }.toString()
            sendClientMaskedFrame(out, authPayload)

            val authResp = readServerFrame(inp)
            assertNotNull(authResp)
            val authJson = JSONObject(authResp!!)
            assertEquals("AUTHENTICATED", authJson.optString("type"))
            assertEquals("ACTIVE", authJson.optString("trustState"))

            // Send COMMAND frame
            val cmdPayload = JSONObject().apply {
                put("type", "COMMAND")
                put("command", "system_status_check")
                put("origin", "DESKTOP_COMPANION")
                put("requestId", "req_ws_test_1")
            }.toString()
            sendClientMaskedFrame(out, cmdPayload)

            val cmdResp = readServerFrame(inp)
            assertNotNull(cmdResp)
            val cmdJson = JSONObject(cmdResp!!)
            assertEquals("TASK_ACCEPTED", cmdJson.optString("type"))
            assertEquals("system_status_check", cmdJson.optString("command"))

            socket.close()
        } finally {
            wsServer.stop()
        }
    }

    @Test
    fun testNodeHeartbeatHealthAndFailoverRouting() {
        val desktopNodeId = "heavy_gpu_node_failover_test"
        nodeManager.registerNode(
            WastiNode(
                nodeId = desktopNodeId,
                nodeName = "Titan RTX Workstation",
                platform = NodePlatform.DESKTOP,
                capabilities = setOf("video_processing", "gpu_compute"),
                trustState = NodeTrustState.ACTIVE,
                healthState = NodeHealthState.ONLINE
            )
        )

        // 1. Record Heartbeat
        val recorded = nodeManager.recordHeartbeat(desktopNodeId, latencyMs = 12L, load = 0.25f)
        assertTrue(recorded)
        val node = nodeManager.getNode(desktopNodeId)
        assertEquals(12L, node?.latencyMs)
        assertEquals(NodeHealthState.ONLINE, node?.healthState)

        // 2. Route when healthy -> DESKTOP_NODE
        val dest1 = nodeManager.routeTaskWithFailover("render_video_processing", failedNodes = emptySet())
        assertEquals(ExecutionDestination.DESKTOP_NODE, dest1)

        // 3. Autonomous Failover: when all desktop nodes are recorded as failed or offline -> fails over to CLOUD
        val allDesktopIds = nodeManager.getAllNodes().filter { it.platform == NodePlatform.DESKTOP }.map { it.nodeId }.toSet()
        val dest2 = nodeManager.routeTaskWithFailover("render_video_processing", requiresCloudApi = true, failedNodes = allDesktopIds)
        assertEquals(ExecutionDestination.CLOUD, dest2)

        // 4. Node Recovery
        nodeManager.updateNodeTrust(desktopNodeId, NodeTrustState.SUSPENDED)
        val suspended = nodeManager.getNode(desktopNodeId)
        assertEquals(NodeTrustState.SUSPENDED, suspended?.trustState)

        val recovered = nodeManager.recoverNode(desktopNodeId)
        assertTrue(recovered)
        val nodeRecovered = nodeManager.getNode(desktopNodeId)
        assertEquals(NodeHealthState.ONLINE, nodeRecovered?.healthState)
    }

    @Test
    fun testLocalServerManagerWebSocketLifecycle() {
        val serverResult = serverManager.startServer(9098)
        assertTrue(serverResult.isSuccess)
        val info = serverResult.getOrNull()!!
        assertEquals(9098, info.port)
        assertEquals(9099, info.wsPort)

        serverManager.stopServer()
    }

    private fun computeExpectedAccept(key: String): String {
        val magic = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        val md = MessageDigest.getInstance("SHA-1")
        val digest = md.digest((key + magic).toByteArray(Charsets.ISO_8859_1))
        return Base64.encodeToString(digest, Base64.NO_WRAP)
    }

    private fun sendClientMaskedFrame(out: OutputStream, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val mask = byteArrayOf(0x12, 0x34, 0x56, 0x78)

        out.write(0x81) // FIN=1, Text Opcode=0x1
        if (bytes.size <= 125) {
            out.write(0x80 or bytes.size) // Mask bit = 1
        } else {
            out.write(0x80 or 126)
            out.write((bytes.size shr 8) and 0xFF)
            out.write(bytes.size and 0xFF)
        }
        out.write(mask)

        val maskedBytes = ByteArray(bytes.size)
        for (i in bytes.indices) {
            maskedBytes[i] = (bytes[i].toInt() xor mask[i % 4].toInt()).toByte()
        }
        out.write(maskedBytes)
        out.flush()
    }

    private fun readServerFrame(inp: InputStream): String? {
        val b1 = inp.read()
        if (b1 == -1) return null
        val b2 = inp.read()
        if (b2 == -1) return null

        var payloadLen = (b2 and 0x7F).toLong()
        if (payloadLen == 126L) {
            val l1 = inp.read()
            val l2 = inp.read()
            if (l1 == -1 || l2 == -1) return null
            payloadLen = ((l1 shl 8) or l2).toLong()
        } else if (payloadLen == 127L) {
            var l = 0L
            for (i in 0 until 8) {
                val b = inp.read()
                if (b == -1) return null
                l = (l shl 8) or (b.toLong() and 0xFF)
            }
            payloadLen = l
        }

        val payload = ByteArray(payloadLen.toInt())
        var read = 0
        while (read < payloadLen.toInt()) {
            val r = inp.read(payload, read, payloadLen.toInt() - read)
            if (r == -1) break
            read += r
        }
        return String(payload, Charsets.UTF_8)
    }
}
