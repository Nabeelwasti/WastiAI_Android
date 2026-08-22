package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.agent.runtime.ExecutionMode
import com.example.data.core.CommandOrigin
import com.example.data.core.CommandSubmissionResult
import com.example.data.core.WastiOSRuntime
import com.example.data.node.ExecutionDestination
import com.example.data.node.NodePlatform
import com.example.data.node.WastiNodeManager
import com.example.data.server.WastiLocalServerManager
import com.example.data.transport.WastiCommandTransport
import kotlinx.coroutines.runBlocking
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
class Stage11MultiDeviceTransportTest {

    private lateinit var context: Context
    private lateinit var transport: WastiCommandTransport
    private lateinit var serverManager: WastiLocalServerManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        transport = WastiCommandTransport.getInstance(context)
        serverManager = WastiLocalServerManager.getInstance(context)
    }

    @Test
    fun testPairingChallengeCreationAndClaim() {
        val deviceId = "web_companion_device_001"
        val deviceName = "Chrome macOS Companion"

        // 1. Create Challenge
        val challenge = transport.createPairingChallenge(
            deviceId = deviceId,
            deviceName = deviceName,
            platform = NodePlatform.WEB
        )

        assertNotNull(challenge)
        assertTrue(challenge.code.startsWith("PAIR-"))
        assertEquals(deviceId, challenge.deviceId)
        assertEquals(deviceName, challenge.deviceName)
        assertFalse(challenge.isClaimed)

        // 2. Claim Challenge
        val paired = transport.verifyPairingChallenge(
            code = challenge.code,
            deviceId = deviceId,
            endpointUrl = "https://companion.wasti.ai"
        )

        assertNotNull(paired)
        assertEquals(deviceId, paired!!.deviceId)
        assertEquals(deviceName, paired.deviceName)
        assertEquals(NodePlatform.WEB, paired.platform)
        assertTrue(paired.sessionToken.startsWith("wasti-dev-sess-"))
        assertFalse(paired.isRevoked)

        // 3. Registered in WastiNodeManager
        val registeredNode = WastiNodeManager.getInstance().getNode(deviceId)
        assertNotNull(registeredNode)
        assertEquals(deviceName, registeredNode!!.nodeName)
        assertEquals(NodePlatform.WEB, registeredNode.platform)

        // 4. Validate security gate with deviceId + sessionToken
        val isAuthorized = transport.validateRequestSecurity(
            origin = CommandOrigin.WEB_COMPANION,
            clientHost = "192.168.1.50",
            authToken = paired.sessionToken,
            deviceId = deviceId
        )
        assertTrue("Paired companion must pass security gate", isAuthorized)

        // 5. Revocation test
        val revoked = transport.revokeDevice(deviceId)
        assertTrue(revoked)

        val isAuthorizedAfterRevoke = transport.validateRequestSecurity(
            origin = CommandOrigin.WEB_COMPANION,
            clientHost = "192.168.1.50",
            authToken = paired.sessionToken,
            deviceId = deviceId
        )
        assertFalse("Revoked device must be rejected", isAuthorizedAfterRevoke)
    }

    @Test
    fun testUnauthenticatedRemoteDeviceRejection() {
        val rejectedResult = transport.dispatchCommand(
            command = "deploy project to staging",
            origin = CommandOrigin.WEB_COMPANION,
            clientHost = "192.168.1.100",
            authToken = "fake-unauthorized-token",
            deviceId = "unknown_device_999"
        )

        assertTrue(rejectedResult is CommandSubmissionResult.Rejected)
        val reason = (rejectedResult as CommandSubmissionResult.Rejected).reason
        assertTrue(reason.contains("TRANSPORT_SECURITY_DENIED"))
    }

    @Test
    fun testIdempotencyAndDuplicateCommandSubmission() {
        val deviceId = "desktop_companion_002"
        val challenge = transport.createPairingChallenge(
            deviceId = deviceId,
            deviceName = "Desktop Linux Companion",
            platform = NodePlatform.DESKTOP
        )
        val paired = transport.verifyPairingChallenge(challenge.code, deviceId)
        assertNotNull(paired)

        val reqId = "unique_req_uuid_12345"

        // 1. First Dispatch
        val firstResult = transport.dispatchCommand(
            command = "status",
            origin = CommandOrigin.DESKTOP_COMPANION,
            authToken = paired!!.sessionToken,
            deviceId = deviceId,
            requestId = reqId
        )

        assertTrue(firstResult is CommandSubmissionResult.Accepted)
        val originalCmdId = (firstResult as CommandSubmissionResult.Accepted).commandId

        // 2. Duplicate Dispatch with same requestId
        val secondResult = transport.dispatchCommand(
            command = "status",
            origin = CommandOrigin.DESKTOP_COMPANION,
            authToken = paired.sessionToken,
            deviceId = deviceId,
            requestId = reqId
        )

        assertTrue(secondResult is CommandSubmissionResult.Accepted)
        assertEquals(
            "Duplicate idempotent submission must return identical commandId",
            originalCmdId,
            (secondResult as CommandSubmissionResult.Accepted).commandId
        )
    }

    @Test
    fun testCrossDeviceNodeRouting() {
        val nodeManager = WastiNodeManager.getInstance()

        // 1. Python task -> PYTHON_RUNTIME
        val pyDest = nodeManager.routeTaskToOptimalNode("run_python_script")
        assertEquals(ExecutionDestination.PYTHON_RUNTIME, pyDest)

        // 2. Cloud AI task -> CLOUD
        val cloudDest = nodeManager.routeTaskToOptimalNode("gemini_ai", requiresCloudApi = true)
        assertEquals(ExecutionDestination.CLOUD, cloudDest)

        // 3. Termux task -> TERMUX
        val termuxDest = nodeManager.routeTaskToOptimalNode("pkg")
        assertEquals(ExecutionDestination.TERMUX, termuxDest)

        // 4. Sandbox task -> SANDBOX
        val sandboxDest = nodeManager.routeTaskToOptimalNode("wasti_sandbox")
        assertEquals(ExecutionDestination.SANDBOX, sandboxDest)

        // 5. Default Native Android -> LOCAL_DEVICE
        val nativeDest = nodeManager.routeTaskToOptimalNode("device_control")
        assertEquals(ExecutionDestination.LOCAL_DEVICE, nativeDest)
    }

    @Test
    fun testServerPairingAndStreamHttpEndpoints() = runBlocking {
        val startResult = serverManager.startServer()
        assertTrue(startResult.isSuccess)
        val port = serverManager.serverInfo.value.port

        try {
            // 1. Request Pairing via POST /api/pairing/request
            val reqUrl = URL("http://127.0.0.1:$port/api/pairing/request")
            val reqConn = reqUrl.openConnection() as HttpURLConnection
            reqConn.requestMethod = "POST"
            reqConn.doOutput = true
            reqConn.setRequestProperty("Content-Type", "application/json")
            val reqPayload = JSONObject().apply {
                put("deviceId", "http_paired_dev_007")
                put("deviceName", "Web Companion Client")
                put("platform", "WEB")
            }.toString()
            reqConn.outputStream.use { it.write(reqPayload.toByteArray()) }

            assertEquals(200, reqConn.responseCode)
            val reqRespBody = reqConn.inputStream.bufferedReader().readText()
            val reqRespJson = JSONObject(reqRespBody)
            assertTrue(reqRespJson.getBoolean("success"))
            val pairCode = reqRespJson.getString("code")
            assertTrue(pairCode.startsWith("PAIR-"))

            // 2. Verify Pairing via POST /api/pairing/verify
            val verifyUrl = URL("http://127.0.0.1:$port/api/pairing/verify")
            val verifyConn = verifyUrl.openConnection() as HttpURLConnection
            verifyConn.requestMethod = "POST"
            verifyConn.doOutput = true
            verifyConn.setRequestProperty("Content-Type", "application/json")
            val verifyPayload = JSONObject().apply {
                put("code", pairCode)
                put("deviceId", "http_paired_dev_007")
            }.toString()
            verifyConn.outputStream.use { it.write(verifyPayload.toByteArray()) }

            assertEquals(200, verifyConn.responseCode)
            val verifyRespBody = verifyConn.inputStream.bufferedReader().readText()
            val verifyRespJson = JSONObject(verifyRespBody)
            assertTrue(verifyRespJson.getBoolean("success"))
            val sessionToken = verifyRespJson.getString("sessionToken")
            assertTrue(sessionToken.startsWith("wasti-dev-sess-"))

            // 3. Dispatch Command via POST /api/command with paired auth token
            val cmdUrl = URL("http://127.0.0.1:$port/api/command")
            val cmdConn = cmdUrl.openConnection() as HttpURLConnection
            cmdConn.requestMethod = "POST"
            cmdConn.doOutput = true
            cmdConn.setRequestProperty("Content-Type", "application/json")
            cmdConn.setRequestProperty("X-Wasti-Auth-Token", sessionToken)
            cmdConn.setRequestProperty("X-Wasti-Device-Id", "http_paired_dev_007")
            val cmdPayload = JSONObject().apply {
                put("command", "system_info")
                put("origin", "WEB_COMPANION")
            }.toString()
            cmdConn.outputStream.use { it.write(cmdPayload.toByteArray()) }

            assertEquals(200, cmdConn.responseCode)
            val cmdRespBody = cmdConn.inputStream.bufferedReader().readText()
            val cmdRespJson = JSONObject(cmdRespBody)
            assertTrue(cmdRespJson.getBoolean("success"))

            // 4. Test Stream snapshot endpoint GET /stream
            val streamUrl = URL("http://127.0.0.1:$port/stream")
            val streamConn = streamUrl.openConnection() as HttpURLConnection
            streamConn.requestMethod = "GET"
            assertEquals(200, streamConn.responseCode)
            val streamBody = streamConn.inputStream.bufferedReader().readText()
            val streamJson = JSONObject(streamBody)
            assertEquals("sync_state", streamJson.getString("type"))
            assertTrue(streamJson.has("isBusy"))
            assertTrue(streamJson.has("recentEvents"))

        } finally {
            serverManager.stopServer("Test finished")
        }
    }
}
