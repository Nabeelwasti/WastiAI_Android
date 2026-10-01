package com.example.data.agent.runtime

import androidx.test.core.app.ApplicationProvider
import com.example.data.core.WastiOSRuntime
import com.example.data.di.WastiServiceLocator
import com.example.data.security.AutonomousMutationGovernance
import com.example.data.transport.WastiCommandTransport
import com.example.data.security.TestBootstrapSecurityFixture
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EmergencyStopGovernanceAndBypassTest {

    private lateinit var controller: WastiEmergencyStopController
    private lateinit var runtime: WastiOSRuntime
    private lateinit var transport: WastiCommandTransport
    private var testAdminToken: String? = null

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        WastiServiceLocator.init(context)
        controller = WastiEmergencyStopController.instance
        runtime = WastiServiceLocator.wastiOSRuntime
        transport = WastiCommandTransport.getInstance(context)
        val token = TestBootstrapSecurityFixture.createAuthorizedBootstrapToken()
        testAdminToken = token
        TestBootstrapSecurityFixture.resetEmergencyStopForBootstrap(token)
    }

    @After
    fun tearDown() {
        val token = testAdminToken
        if (token != null) {
            TestBootstrapSecurityFixture.resetEmergencyStopForBootstrap(token)
            TestBootstrapSecurityFixture.revokeAuthorizedBootstrapToken(token)
            testAdminToken = null
        }
    }

    @Test
    fun testAuthorizedHumanCanResetEmergencyStop() {
        controller.triggerEmergencyStop("Test Stop Trigger")
        assertTrue(controller.isEmergencyStopped)

        // 1. Without admin token, string alone fails closed
        val resetNoToken = controller.resetEmergencyStop(requester = "HUMAN_OPERATOR")
        assertFalse("HUMAN_OPERATOR without token must fail closed", resetNoToken)
        assertTrue("Controller must remain stopped", controller.isEmergencyStopped)

        // 2. With valid admin token, reset succeeds
        val resetSuccess = controller.resetEmergencyStop(
            requester = "HUMAN_OPERATOR",
            adminToken = testAdminToken
        )
        assertTrue("Authenticated HUMAN_OPERATOR with admin token must succeed", resetSuccess)
        assertFalse("Controller must no longer be stopped", controller.isEmergencyStopped)
    }

    @Test
    fun testAuthorizedOwnerAdminCanResetEmergencyStop() {
        controller.triggerEmergencyStop("Test Stop Trigger")
        assertTrue(controller.isEmergencyStopped)

        // 1. Without admin token, string alone fails closed
        val resetNoToken = controller.resetEmergencyStop(requester = "OWNER_ADMIN")
        assertFalse("OWNER_ADMIN without token must fail closed", resetNoToken)
        assertTrue("Controller must remain stopped", controller.isEmergencyStopped)

        // 2. With valid admin token, reset succeeds
        val resetSuccess = controller.resetEmergencyStop(
            requester = "OWNER_ADMIN",
            adminToken = testAdminToken
        )
        assertTrue("Authenticated OWNER_ADMIN with admin token must succeed", resetSuccess)
        assertFalse("Controller must no longer be stopped", controller.isEmergencyStopped)
    }

    @Test
    fun testCallerWithValidAdminTokenCanResetEmergencyStop() {
        controller.triggerEmergencyStop("Test Stop Trigger")
        assertTrue(controller.isEmergencyStopped)

        val resetSuccess = controller.resetEmergencyStop(
            requester = "AUTHORIZED_OPERATOR",
            adminToken = testAdminToken
        )
        assertTrue("Caller with valid admin token must succeed", resetSuccess)
        assertFalse("Controller must no longer be stopped", controller.isEmergencyStopped)
    }

    @Test
    fun testAutonomousAiCannotResetEmergencyStopEvenWithValidAdminToken() {
        controller.triggerEmergencyStop("Test Stop Trigger")
        assertTrue(controller.isEmergencyStopped)

        // 1. Without admin token
        val aiWithoutToken = controller.resetEmergencyStop(requester = "AUTONOMOUS_AI")
        assertFalse("Autonomous AI without token must fail closed", aiWithoutToken)
        assertTrue("Controller must remain stopped", controller.isEmergencyStopped)

        // 2. With valid admin token (Strict AI prohibition invariant)
        val aiWithToken = controller.resetEmergencyStop(
            requester = "AUTONOMOUS_AI",
            adminToken = testAdminToken
        )
        assertFalse("Autonomous AI with admin token must still fail closed", aiWithToken)
        assertTrue("Controller must remain stopped", controller.isEmergencyStopped)

        // 3. Subagent variant
        val subagentWithToken = controller.resetEmergencyStop(
            requester = "AI_SUBAGENT_PLANNER",
            adminToken = testAdminToken
        )
        assertFalse("AI subagent with token must fail closed", subagentWithToken)
        assertTrue("Controller must remain stopped", controller.isEmergencyStopped)
    }

    @Test
    fun testBlankAndNullAndUnknownRequesterFailsClosed() {
        controller.triggerEmergencyStop("Test Stop Trigger")
        assertTrue(controller.isEmergencyStopped)

        assertFalse("Blank requester must fail", controller.resetEmergencyStop(requester = "   "))
        assertFalse("Unknown requester must fail", controller.resetEmergencyStop(requester = "UNKNOWN"))
        assertFalse("Random unauthenticated caller must fail", controller.resetEmergencyStop(requester = "arbitrary_user_123"))
        assertTrue("Controller must remain stopped", controller.isEmergencyStopped)
    }

    @Test
    fun testSystemCallerFailsClosedWithoutAdminToken() {
        controller.triggerEmergencyStop("Test Stop Trigger")
        assertTrue(controller.isEmergencyStopped)

        val systemNoToken = controller.resetEmergencyStop(requester = "SYSTEM")
        assertFalse("Unauthenticated SYSTEM caller must fail closed", systemNoToken)
        assertTrue("Controller must remain stopped", controller.isEmergencyStopped)

        val systemInitializerNoToken = controller.resetEmergencyStop(requester = "SYSTEM_INITIALIZER")
        assertFalse("SYSTEM_INITIALIZER without token must fail closed", systemInitializerNoToken)
        assertTrue("Controller must remain stopped", controller.isEmergencyStopped)

        // Even with token, SYSTEM and SYSTEM_* fail closed
        val systemInitializerWithToken = controller.resetEmergencyStop(
            requester = "SYSTEM_INITIALIZER",
            adminToken = testAdminToken
        )
        assertFalse("SYSTEM_INITIALIZER must fail closed per security policy", systemInitializerWithToken)
        assertTrue("Controller must remain stopped", controller.isEmergencyStopped)
    }

    @Test
    fun testRuntimeClearEmergencyStopEnforcesGovernance() {
        runtime.triggerEmergencyStop("Runtime emergency test")
        assertTrue(runtime.activeContext.value.isBusy || controller.isEmergencyStopped)

        // Unauthorized reset without token
        val unauth = runtime.clearEmergencyStop(requester = "AUTONOMOUS_AI")
        assertFalse("Autonomous reset via runtime must be rejected", unauth)
        assertTrue("Controller must remain stopped", controller.isEmergencyStopped)

        val humanNoToken = runtime.clearEmergencyStop(requester = "HUMAN_OPERATOR")
        assertFalse("Human reset without admin token must be rejected", humanNoToken)
        assertTrue("Controller must remain stopped", controller.isEmergencyStopped)

        // Authorized reset with valid admin token
        val auth = runtime.clearEmergencyStop(requester = "HUMAN_OPERATOR", adminToken = testAdminToken)
        assertTrue("Human reset with valid admin token via runtime must succeed", auth)
        assertFalse("Controller must no longer be stopped", controller.isEmergencyStopped)
        assertEquals("Idle", runtime.activeContext.value.agenticState::class.simpleName)
    }

    @Test
    fun testTransportClearEmergencyStopEnforcesGovernance() {
        transport.triggerEmergencyStop("Transport emergency test")
        assertTrue(controller.isEmergencyStopped)

        // Unauthorized reset via transport
        val unauth = transport.clearEmergencyStop(requester = "AUTONOMOUS_BOT")
        assertFalse("Autonomous reset via transport must be rejected", unauth)
        assertTrue("Controller must remain stopped", controller.isEmergencyStopped)

        val adminNoToken = transport.clearEmergencyStop(requester = "OWNER_ADMIN")
        assertFalse("Admin reset without token must fail closed", adminNoToken)
        assertTrue("Controller must remain stopped", controller.isEmergencyStopped)

        // Authorized reset via transport with valid admin token
        val auth = transport.clearEmergencyStop(requester = "OWNER_ADMIN", adminToken = testAdminToken)
        assertTrue("Admin reset via transport with valid admin token must succeed", auth)
        assertFalse("Controller must no longer be stopped", controller.isEmergencyStopped)
    }
}
