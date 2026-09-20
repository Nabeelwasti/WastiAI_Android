package com.example.data.core

import com.example.data.agent.runtime.WastiEmergencyStopController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Unit test verifying WastiEmergencyStopController active convergence across jobs, scopes, and hooks.
 */
class EmergencyStopActiveConvergenceTest {

    private lateinit var controller: WastiEmergencyStopController

    @Before
    fun setUp() {
        controller = WastiEmergencyStopController()
        controller.resetEmergencyStop()
    }

    @Test
    fun testEmergencyStopTriggersActiveCancellation() = runBlocking {
        assertFalse(controller.isEmergencyStopped)

        val scope = CoroutineScope(Dispatchers.Default)
        val jobRunning = AtomicBoolean(false)
        val jobCancelled = AtomicBoolean(false)

        val job = scope.launch {
            jobRunning.set(true)
            try {
                while (isActive) {
                    delay(50)
                }
            } finally {
                jobCancelled.set(true)
            }
        }

        controller.registerScope("test_scope", scope)
        controller.registerJob("test_job", job)

        var hookTriggered = false
        controller.registerCancellationHook("test_hook") {
            hookTriggered = true
        }

        delay(100)
        assertTrue("Job should be running", jobRunning.get())

        // Trigger emergency stop
        controller.triggerEmergencyStop("Test active convergence")

        assertTrue("Controller must report stopped", controller.isEmergencyStopped)
        assertEquals("Test active convergence", controller.getReason())
        assertTrue("Custom cancellation hook must be invoked", hookTriggered)

        delay(100)
        assertFalse("Job must no longer be active", job.isActive)

        // Reset emergency stop
        controller.resetEmergencyStop()
        assertFalse("Controller must report not stopped after reset", controller.isEmergencyStopped)
    }

    @Test
    fun testAuditHistoryCapturesEventMetrics() {
        val initialAuditCount = controller.getAuditHistory().size
        controller.triggerEmergencyStop("Audit metric test")
        val auditList = controller.getAuditHistory()

        assertTrue("Audit history must grow", auditList.size > initialAuditCount)
        val latest = auditList.last()
        assertTrue(latest.isStopped)
        assertEquals("Audit metric test", latest.reason)
    }
}
