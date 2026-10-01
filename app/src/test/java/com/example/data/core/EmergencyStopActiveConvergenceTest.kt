package com.example.data.core

import com.example.data.agent.runtime.WastiEmergencyStopController
import com.example.data.security.TestBootstrapSecurityFixture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
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
    private var testBootstrapToken: String? = null

    @Before
    fun setUp() {
        controller = WastiEmergencyStopController()
        val token = TestBootstrapSecurityFixture.createAuthorizedBootstrapToken()
        testBootstrapToken = token
        TestBootstrapSecurityFixture.resetEmergencyStopForBootstrap(controller, token)
    }

    @After
    fun tearDown() {
        val token = testBootstrapToken
        if (token != null) {
            TestBootstrapSecurityFixture.resetEmergencyStopForBootstrap(controller, token)
            TestBootstrapSecurityFixture.revokeAuthorizedBootstrapToken(token)
            testBootstrapToken = null
        }
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

        // 1. Plain unauthenticated SYSTEM_INITIALIZER must fail closed
        val unauthReset = controller.resetEmergencyStop(requester = "SYSTEM_INITIALIZER")
        assertFalse("Unauthenticated SYSTEM_INITIALIZER must fail closed", unauthReset)
        assertTrue("Controller must remain stopped after unauthenticated attempt", controller.isEmergencyStopped)

        // 2. SYSTEM_INITIALIZER even with token fails closed per strict SYSTEM_* rejection policy
        val systemWithTokenReset = controller.resetEmergencyStop(
            requester = "SYSTEM_INITIALIZER",
            adminToken = testBootstrapToken
        )
        assertFalse("SYSTEM_INITIALIZER with token must fail closed", systemWithTokenReset)
        assertTrue("Controller must remain stopped", controller.isEmergencyStopped)

        // 3. Authenticated test bootstrap fixture reset succeeds
        val authReset = TestBootstrapSecurityFixture.resetEmergencyStopForBootstrap(controller, testBootstrapToken)
        assertTrue("Authenticated bootstrap fixture reset must succeed", authReset)
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
