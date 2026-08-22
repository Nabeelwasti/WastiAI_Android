package com.example.data.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.agent.runtime.*
import com.example.data.di.WastiServiceLocator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WastiOSRuntimeTest {

    private lateinit var context: Context
    private lateinit var runtime: WastiOSRuntime
    private lateinit var emergencyStop: WastiEmergencyStopController
    private lateinit var eventBus: AgentEventBus

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        WastiServiceLocator.init(context)
        emergencyStop = WastiEmergencyStopController()
        emergencyStop.resetEmergencyStop()
        WastiServiceLocator.emergencyStopController.resetEmergencyStop()
        eventBus = AgentEventBus()
        runtime = WastiOSRuntime(
            appContext = context,
            emergencyStopController = emergencyStop,
            eventBus = eventBus
        )
    }

    @Test
    fun testInitialStateIsIdle() = runBlocking {
        val context = runtime.activeContext.value
        assertFalse(context.isBusy)
        assertNull(context.activeTaskId)
    }

    @Test
    fun testSubmitBlankCommandIsRejected() {
        val result = runtime.submitCommand(
            command = "   ",
            origin = CommandOrigin.CHAT
        )
        assertTrue(result is CommandSubmissionResult.Rejected)
    }

    @Test
    fun testEmergencyStopRejectsSubmissions() {
        runtime.triggerEmergencyStop("Test safety breach")
        assertTrue(emergencyStop.isEmergencyStopped)

        val result = runtime.submitCommand(
            command = "Run system diagnosis",
            origin = CommandOrigin.TERMINAL
        )
        assertTrue(result is CommandSubmissionResult.Rejected)
        val rejected = result as CommandSubmissionResult.Rejected
        assertTrue(rejected.reason.contains("EMERGENCY_STOP_ACTIVE"))

        runtime.clearEmergencyStop()
        assertFalse(emergencyStop.isEmergencyStopped)
    }

    @Test
    fun testCommandOriginProperties() {
        assertTrue(CommandOrigin.CHAT.isLocal)
        assertTrue(CommandOrigin.TERMINAL.isLocal)
        assertTrue(CommandOrigin.FLOATING_BUBBLE.isLocal)
        assertTrue(CommandOrigin.VOICE.isLocal)
        assertFalse(CommandOrigin.WEB_COMPANION.isLocal)
        assertFalse(CommandOrigin.EXTERNAL_NODE.isLocal)
    }
}
