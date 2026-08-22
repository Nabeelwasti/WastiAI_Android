package com.example.data.transport

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.agent.runtime.WastiEmergencyStopController
import com.example.data.core.CommandOrigin
import com.example.data.core.CommandSubmissionResult
import com.example.data.core.WastiOSRuntime
import com.example.data.di.WastiServiceLocator
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WastiCommandTransportTest {

    private lateinit var context: Context
    private lateinit var transport: WastiCommandTransport
    private lateinit var runtime: WastiOSRuntime

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        WastiServiceLocator.init(context)
        val emergencyStop = WastiEmergencyStopController()
        emergencyStop.resetEmergencyStop()
        WastiServiceLocator.emergencyStopController.resetEmergencyStop()
        runtime = WastiOSRuntime(
            appContext = context,
            emergencyStopController = emergencyStop
        )
        transport = WastiCommandTransport(context = context, runtime = runtime)
    }

    @Test
    fun testLocalhostSecurityValidation() {
        // Local origin from localhost should be approved
        assertTrue(transport.validateRequestSecurity(CommandOrigin.CHAT, "127.0.0.1"))
        assertTrue(transport.validateRequestSecurity(CommandOrigin.TERMINAL, "localhost"))

        // Remote origin without token should be denied
        assertFalse(transport.validateRequestSecurity(CommandOrigin.WEB_COMPANION, "192.168.1.50", null))

        // Remote origin with valid generated session token should be approved
        val token = transport.generateSessionToken()
        assertTrue(transport.validateRequestSecurity(CommandOrigin.WEB_COMPANION, "192.168.1.50", token))
    }

    @Test
    fun testDispatchCommandToRuntime() {
        val result = transport.dispatchCommand(
            command = "status",
            origin = CommandOrigin.TERMINAL,
            clientHost = "127.0.0.1"
        )
        assertTrue(result is CommandSubmissionResult.Accepted)
    }

    @Test
    fun testDispatchUnauthenticatedCommandFails() {
        val result = transport.dispatchCommand(
            command = "reboot",
            origin = CommandOrigin.WEB_COMPANION,
            clientHost = "192.168.1.100",
            authToken = "invalid-token"
        )
        assertTrue(result is CommandSubmissionResult.Rejected)
        val rej = result as CommandSubmissionResult.Rejected
        assertTrue(rej.reason.contains("TRANSPORT_SECURITY_DENIED"))
    }
}
