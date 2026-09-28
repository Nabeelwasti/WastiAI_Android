package com.example.data.server

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.agent.runtime.WastiEmergencyStopController
import com.example.data.core.CommandOrigin
import com.example.data.core.CommandSubmissionResult
import com.example.data.core.WastiOSRuntime
import com.example.data.di.WastiServiceLocator
import com.example.data.node.NodePlatform
import com.example.data.transport.WastiCommandTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [Finding 12] Adversarial Security Test Suite for Localhost HTTP/WebSocket IPC Boundary.
 *
 * Verifies that:
 * 1. Unauthenticated localhost network requests cannot invoke privileged Wasti operations.
 * 2. Adversarial callers attempting to spoof internal UI origins over network IPC fail closed.
 * 3. Session tokens are strictly validated with TTL and revocation.
 * 4. Paired devices lose access immediately upon revocation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalIpcSecurityAndBypassTest {

    private lateinit var context: Context
    private lateinit var transport: WastiCommandTransport
    private lateinit var runtime: WastiOSRuntime

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        WastiServiceLocator.init(context)
        val emergencyStop = WastiEmergencyStopController()
        emergencyStop.resetEmergencyStop(requester = "HUMAN_OPERATOR")
        WastiServiceLocator.emergencyStopController.resetEmergencyStop(requester = "HUMAN_OPERATOR")
        runtime = WastiOSRuntime(
            appContext = context,
            emergencyStopController = emergencyStop
        )
        transport = WastiCommandTransport(context = context, runtime = runtime)
    }

    @Test
    fun testLocalhostNetworkRequestWithoutTokenIsRejected() {
        // A network request from localhost (127.0.0.1) claiming LOCAL_SERVER without token must be rejected
        val isPermitted = transport.validateRequestSecurity(
            origin = CommandOrigin.LOCAL_SERVER,
            clientHost = "127.0.0.1",
            authToken = null,
            isNetworkRequest = true
        )
        assertFalse("Unauthenticated localhost network request must be rejected", isPermitted)
    }

    @Test
    fun testLocalhostNetworkRequestWithInvalidTokenIsRejected() {
        val isPermitted = transport.validateRequestSecurity(
            origin = CommandOrigin.LOCAL_SERVER,
            clientHost = "127.0.0.1",
            authToken = "adversary-crafted-fake-token",
            isNetworkRequest = true
        )
        assertFalse("Network request with invalid token must be rejected", isPermitted)
    }

    @Test
    fun testLocalhostNetworkRequestWithValidTokenIsPermitted() {
        val validToken = transport.generateSessionToken()
        val isPermitted = transport.validateRequestSecurity(
            origin = CommandOrigin.LOCAL_SERVER,
            clientHost = "127.0.0.1",
            authToken = validToken,
            isNetworkRequest = true
        )
        assertTrue("Network request with valid session token must be permitted", isPermitted)
    }

    @Test
    fun testSpoofingInternalUiOriginOverNetworkIsRejected() {
        val validToken = transport.generateSessionToken()
        val sensitiveOrigins = listOf(
            CommandOrigin.CHAT,
            CommandOrigin.TERMINAL,
            CommandOrigin.FLOATING_BUBBLE,
            CommandOrigin.VOICE,
            CommandOrigin.DEV_ASSISTANT,
            CommandOrigin.PROJECTS,
            CommandOrigin.OPERATIONS,
            CommandOrigin.NOTIFICATION,
            CommandOrigin.ACCESSIBILITY,
            CommandOrigin.BACKGROUND_WORKER
        )

        for (origin in sensitiveOrigins) {
            val permitted = transport.validateRequestSecurity(
                origin = origin,
                clientHost = "127.0.0.1",
                authToken = validToken,
                isNetworkRequest = true
            )
            assertFalse("Spoofing internal origin $origin over network request must be blocked", permitted)
        }
    }

    @Test
    fun testRevokedDeviceLosesAccessImmediately() {
        val challenge = transport.createPairingChallenge("test-device-id", "Test Laptop", NodePlatform.DESKTOP)
        val paired = transport.verifyPairingChallenge(challenge.code, "test-device-id")
        assertNotNull("Pairing must succeed", paired)

        // Validate access with paired token
        val isPermittedBefore = transport.validateRequestSecurity(
            origin = CommandOrigin.DESKTOP_COMPANION,
            clientHost = "127.0.0.1",
            authToken = paired!!.sessionToken,
            deviceId = paired.deviceId,
            isNetworkRequest = true
        )
        assertTrue("Paired device should have access", isPermittedBefore)

        // Revoke device
        val revoked = transport.revokeDevice(paired.deviceId)
        assertTrue("Device must be revoked successfully", revoked)

        // Validate access rejected after revocation
        val isPermittedAfter = transport.validateRequestSecurity(
            origin = CommandOrigin.DESKTOP_COMPANION,
            clientHost = "127.0.0.1",
            authToken = paired.sessionToken,
            deviceId = paired.deviceId,
            isNetworkRequest = true
        )
        assertFalse("Revoked device must be denied access", isPermittedAfter)
    }

    @Test
    fun testDispatchCommandFailsClosedForUnauthorizedLocalIpc() = runBlocking {
        val result = transport.dispatchCommand(
            command = "exec privileged_operation",
            origin = CommandOrigin.LOCAL_SERVER,
            clientHost = "127.0.0.1",
            authToken = null,
            isNetworkRequest = true
        )

        assertTrue("Unauthorized dispatch must be rejected", result is CommandSubmissionResult.Rejected)
        val rejected = result as CommandSubmissionResult.Rejected
        assertTrue("Reason must indicate transport security denied", rejected.reason.contains("TRANSPORT_SECURITY_DENIED"))
    }
}
