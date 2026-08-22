package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.agent.runtime.ExecutionMode
import com.example.data.core.CommandOrigin
import com.example.data.core.CommandSubmissionResult
import com.example.data.node.ExecutionDestination
import com.example.data.node.NodeCapability
import com.example.data.node.NodePlatform
import com.example.data.node.NodeTrustState
import com.example.data.node.WastiNode
import com.example.data.node.WastiNodeDiscoveryManager
import com.example.data.node.WastiNodeManager
import com.example.data.server.WastiLocalServerManager
import com.example.data.transport.WastiCommandTransport
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.HttpURLConnection
import java.net.URL

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Stage12CrossDeviceOSTest {

    private lateinit var context: Context
    private lateinit var transport: WastiCommandTransport
    private lateinit var serverManager: WastiLocalServerManager
    private lateinit var nodeManager: WastiNodeManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        transport = WastiCommandTransport.getInstance(context)
        serverManager = WastiLocalServerManager.getInstance(context)
        nodeManager = WastiNodeManager.getInstance()
    }

    @Test
    fun testNodeTrustStateLifecycle() {
        val deviceId = "desktop_companion_999"
        val challenge = transport.createPairingChallenge(
            deviceId = deviceId,
            deviceName = "Arch Linux Desktop Node",
            platform = NodePlatform.DESKTOP
        )

        val paired = transport.verifyPairingChallenge(challenge.code, deviceId)
        assertNotNull(paired)
        assertEquals(NodeTrustState.ACTIVE, paired!!.trustState)

        // Verify Node in NodeManager
        val node = nodeManager.getNode(deviceId)
        assertNotNull(node)
        assertEquals(NodeTrustState.ACTIVE, node!!.trustState)

        // Revoke
        val revoked = transport.revokeDevice(deviceId)
        assertTrue(revoked)

        // Try submitting command with revoked token -> must reject
        val result = transport.dispatchCommand(
            command = "test command",
            origin = CommandOrigin.DESKTOP_COMPANION,
            deviceId = deviceId,
            authToken = paired.sessionToken,
            clientHost = "192.168.1.50"
        )
        assertTrue(result is CommandSubmissionResult.Rejected)
    }

    @Test
    fun testCapabilityAwareNodeRouting() {
        // Register a high compute desktop node
        val desktopId = "heavy_desktop_node_1"
        nodeManager.registerNode(
            WastiNode(
                nodeId = desktopId,
                nodeName = "Threadripper Workstation",
                platform = NodePlatform.DESKTOP,
                capabilities = setOf("video_processing", "gpu_compute", "compile_large_project"),
                trustState = NodeTrustState.ACTIVE
            )
        )

        // Register a browser web node
        val webId = "browser_web_node_1"
        nodeManager.registerNode(
            WastiNode(
                nodeId = webId,
                nodeName = "Chrome Automation Node",
                platform = NodePlatform.WEB,
                capabilities = setOf("web_browser", "browser_automation"),
                trustState = NodeTrustState.ACTIVE
            )
        )

        // Route video processing -> should target DESKTOP_NODE
        val dest1 = nodeManager.routeTaskToOptimalNode("render_video_processing")
        assertEquals(ExecutionDestination.DESKTOP_NODE, dest1)

        // Route browser automation -> should target WEB_NODE
        val dest2 = nodeManager.routeTaskToOptimalNode("web_browser")
        assertEquals(ExecutionDestination.WEB_NODE, dest2)

        // Route python -> should target PYTHON_RUNTIME
        val dest3 = nodeManager.routeTaskToOptimalNode("python_runtime")
        assertEquals(ExecutionDestination.PYTHON_RUNTIME, dest3)

        // Route local -> default to LOCAL_DEVICE
        val dest4 = nodeManager.routeTaskToOptimalNode("device_control")
        assertEquals(ExecutionDestination.LOCAL_DEVICE, dest4)
    }

    @Test
    fun testWebCompanionDashboardEndpoint() {
        val serverResult = serverManager.startServer(9095)
        assertTrue(serverResult.isSuccess)
        val port = serverResult.getOrNull()!!.port

        try {
            val url = URL("http://127.0.0.1:$port/web")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 3000
                readTimeout = 3000
            }

            assertEquals(200, conn.responseCode)
            assertEquals("text/html; charset=utf-8", conn.contentType)
            val body = conn.inputStream.bufferedReader().readText()
            assertTrue(body.contains("Wasti AI OS — Web Room"))
            assertTrue(body.contains("Companion Authentication & Pairing"))
        } finally {
            serverManager.stopServer()
        }
    }

    @Test
    fun testNsdDiscoveryManagerLifecycle() {
        val discovery = WastiNodeDiscoveryManager.getInstance(context)
        val registered = discovery.registerService(8080)
        // In Robolectric, NsdManager might be a mock, but method should safely return without crashing
        discovery.unregisterService()
    }
}
