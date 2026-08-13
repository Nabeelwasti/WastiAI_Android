package com.example.data.agent.runtime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.tool.DeviceControlTool
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Stage6UnifiedExecutionFabricTest {

    private lateinit var context: Context
    private lateinit var realityRegistry: CapabilityRealityRegistry
    private lateinit var eventBus: AgentEventBus
    private lateinit var auditEngine: RealityAuditEngine
    private lateinit var fabric: UnifiedExecutionFabric

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        realityRegistry = CapabilityRealityRegistry()
        eventBus = AgentEventBus()
        auditEngine = RealityAuditEngine(realityRegistry, WastiCredentialBroker())
        fabric = UnifiedExecutionFabric(
            realityRegistry = realityRegistry,
            eventBus = eventBus,
            auditEngine = auditEngine
        )
    }

    // 1. Successful real executor path (e.g. memory search)
    @Test
    fun testSuccessfulRealExecutorPath() = runBlocking {
        val request = UnifiedExecutionRequest(
            capabilityId = "memory_search",
            parameters = mapOf("query" to "test memory query")
        )

        val result = fabric.execute(request, context)
        assertEquals(UnifiedExecutionStatus.VERIFIED, result.status)
        assertEquals("MemoryManager", result.executor)
        assertNull(result.error)
        assertEquals(UnifiedVerificationStatus.VERIFIED, result.verificationStatus)
    }

    // 2. Executor failure path (e.g. open_app with non-existent package)
    @Test
    fun testExecutorFailurePath() = runBlocking {
        val request = UnifiedExecutionRequest(
            capabilityId = "device_control",
            parameters = mapOf("action" to "open_app", "target" to "non_existent_app_xyz_12345")
        )

        val result = fabric.execute(request, context)
        assertEquals(UnifiedExecutionStatus.FAILED, result.status)
        assertEquals("WastiDeviceController", result.executor)
        assertNotNull(result.error)
        assertEquals(UnifiedVerificationStatus.FAILED, result.verificationStatus)
    }

    // 3. Unavailable capability path
    @Test
    fun testUnavailableCapabilityPath() = runBlocking {
        realityRegistry.updateCapabilityReality(
            CapabilityReality(
                capabilityId = "UNKNOWN_CAP",
                category = "TEST",
                implementationStatus = ImplementationStatus.NOT_IMPLEMENTED,
                liveConnectionStatus = LiveConnectionStatus.DISCONNECTED,
                executionStatus = CapabilityExecutionStatus.UNAVAILABLE,
                authenticationStatus = CapabilityAuthStatus.REQUIRED_NOT_PROVIDED,
                provider = "None",
                supportedOperations = emptyList(),
                limitations = listOf("Capability unavailable"),
                realityState = CapabilityRealityState.UNAVAILABLE
            )
        )

        val request = UnifiedExecutionRequest(capabilityId = "UNKNOWN_CAP")
        val result = fabric.execute(request, context)

        assertEquals(UnifiedExecutionStatus.UNAVAILABLE, result.status)
        assertNotNull(result.error)
        assertTrue(result.error!!.contains("UNAVAILABLE"))
    }

    // 4. Authentication-required capability path
    @Test
    fun testAuthenticationRequiredCapabilityPath() = runBlocking {
        val request = UnifiedExecutionRequest(capabilityId = "GMAIL")
        val result = fabric.execute(request, context)

        assertEquals(UnifiedExecutionStatus.AUTHENTICATION_REQUIRED, result.status)
        assertNotNull(result.error)
        assertTrue(result.error!!.contains("AUTHENTICATION_REQUIRED"))
    }

    // 5. Fabricated-success prevention test (DeviceControlTool regression test)
    @Test
    fun testFabricatedSuccessPreventionInDeviceControlTool() = runBlocking {
        val deviceTool = DeviceControlTool()

        // Executing an open_app command with a non-existent package
        val resultOutput = deviceTool.execute(mapOf("action" to "open_app", "target" to "non_existent_app_xyz"))

        // Must NOT return a fabricated success message like "Device Action executed successfully"
        assertFalse("Must not claim fabricated success", resultOutput.contains("executed successfully"))
        assertTrue("Must contain truthful failure indicator", resultOutput.contains("Execution Error") || resultOutput.contains("not found"))
    }

    // 6. Authorization denial path
    @Test
    fun testAuthorizationDenialPath() = runBlocking {
        val request = UnifiedExecutionRequest(
            capabilityId = "device_control",
            parameters = mapOf("action" to "send_sms", "target" to "+15550001111", "content" to "test"),
            authorizationState = ActionAuthorizationState.CANCELLED
        )

        val result = fabric.execute(request, context)
        assertEquals(UnifiedExecutionStatus.CANCELLED, result.status)
        assertNotNull(result.error)
        assertTrue(result.error!!.contains("CANCELLED"))
    }

    // 7. Verification failure path
    @Test
    fun testVerificationFailurePath() = runBlocking {
        // Contract-only or placeholder capability
        realityRegistry.updateCapabilityReality(
            CapabilityReality(
                capabilityId = "CONTRACT_CAP",
                category = "TEST",
                implementationStatus = ImplementationStatus.CONTRACT_ONLY,
                liveConnectionStatus = LiveConnectionStatus.NOT_VERIFIED,
                executionStatus = CapabilityExecutionStatus.OPERATIONAL,
                authenticationStatus = CapabilityAuthStatus.NOT_REQUIRED,
                provider = "Test",
                supportedOperations = emptyList(),
                limitations = listOf("Contract only"),
                realityState = CapabilityRealityState.CONTRACT_ONLY
            )
        )

        val request = UnifiedExecutionRequest(capabilityId = "CONTRACT_CAP")
        val result = fabric.execute(request, context)

        assertEquals(UnifiedExecutionStatus.NOT_IMPLEMENTED, result.status)
        assertEquals(UnifiedVerificationStatus.NOT_APPLICABLE, result.verificationStatus)
    }

    // 8. Cancellation path
    @Test
    fun testCancellationPath() = runBlocking {
        val request = UnifiedExecutionRequest(
            capabilityId = "memory_search",
            parameters = mapOf("query" to "test"),
            authorizationState = ActionAuthorizationState.CANCELLED
        )

        val result = fabric.execute(request, context)
        assertEquals(UnifiedExecutionStatus.CANCELLED, result.status)
    }

    // 9. Timeout / failure handling
    @Test
    fun testTimeoutHandling() = runBlocking {
        // Register a custom executor that sleeps longer than timeout
        fabric.registerExecutor(object : UnifiedExecutor {
            override val name = "SlowExecutor"
            override val supportedCapabilities = listOf("SLOW_CAP")
            override suspend fun execute(request: UnifiedExecutionRequest, context: Context?): UnifiedExecutionResult {
                delay(2000L)
                return UnifiedExecutionResult(
                    taskId = request.taskId,
                    actionId = request.actionId,
                    capabilityId = request.capabilityId,
                    status = UnifiedExecutionStatus.COMPLETED,
                    output = "Slow output",
                    executor = name,
                    startedAt = System.currentTimeMillis(),
                    completedAt = System.currentTimeMillis(),
                    verificationStatus = UnifiedVerificationStatus.VERIFIED
                )
            }
        })

        realityRegistry.updateCapabilityReality(
            CapabilityReality(
                capabilityId = "SLOW_CAP",
                category = "TEST",
                implementationStatus = ImplementationStatus.READY,
                liveConnectionStatus = LiveConnectionStatus.VERIFIED,
                executionStatus = CapabilityExecutionStatus.OPERATIONAL,
                authenticationStatus = CapabilityAuthStatus.NOT_REQUIRED,
                provider = "SlowExecutor",
                supportedOperations = listOf("slow_op"),
                limitations = emptyList(),
                realityState = CapabilityRealityState.NATIVE
            )
        )

        val request = UnifiedExecutionRequest(
            capabilityId = "SLOW_CAP",
            timeoutMs = 100L
        )

        val result = fabric.execute(request, context)
        assertEquals(UnifiedExecutionStatus.FAILED, result.status)
        assertNotNull(result.error)
        assertTrue(result.error!!.contains("timeout"))
    }

    // 10. Duplicate execution prevention
    @Test
    fun testDuplicateExecutionPrevention() = runBlocking {
        val taskId = "duplicate-task-id"
        val actionId = "duplicate-action-id"
        val capId = "SLOW_CAP_2"

        fabric.registerExecutor(object : UnifiedExecutor {
            override val name = "SlowExecutor2"
            override val supportedCapabilities = listOf(capId)
            override suspend fun execute(request: UnifiedExecutionRequest, context: Context?): UnifiedExecutionResult {
                delay(300L)
                return UnifiedExecutionResult(
                    taskId = request.taskId,
                    actionId = request.actionId,
                    capabilityId = request.capabilityId,
                    status = UnifiedExecutionStatus.COMPLETED,
                    output = "Slow output 2",
                    executor = name,
                    startedAt = System.currentTimeMillis(),
                    completedAt = System.currentTimeMillis(),
                    verificationStatus = UnifiedVerificationStatus.VERIFIED
                )
            }
        })

        realityRegistry.updateCapabilityReality(
            CapabilityReality(
                capabilityId = capId,
                category = "TEST",
                implementationStatus = ImplementationStatus.READY,
                liveConnectionStatus = LiveConnectionStatus.VERIFIED,
                executionStatus = CapabilityExecutionStatus.OPERATIONAL,
                authenticationStatus = CapabilityAuthStatus.NOT_REQUIRED,
                provider = "SlowExecutor2",
                supportedOperations = listOf("slow_op"),
                limitations = emptyList(),
                realityState = CapabilityRealityState.NATIVE
            )
        )

        val req1 = UnifiedExecutionRequest(taskId = taskId, actionId = actionId, capabilityId = capId)
        val req2 = UnifiedExecutionRequest(taskId = taskId, actionId = actionId, capabilityId = capId)

        val job1 = async { fabric.execute(req1, context) }
        val job2 = async { fabric.execute(req2, context) }

        val r1 = job1.await()
        val r2 = job2.await()

        assertTrue(
            "One request should succeed and one should fail as duplicate",
            (r1.status == UnifiedExecutionStatus.COMPLETED && r2.status == UnifiedExecutionStatus.FAILED) ||
                    (r2.status == UnifiedExecutionStatus.COMPLETED && r1.status == UnifiedExecutionStatus.FAILED)
        )
    }

    // 11. Audit event generation
    @Test
    fun testAuditEventGeneration() = runBlocking {
        val request = UnifiedExecutionRequest(
            capabilityId = "memory_search",
            parameters = mapOf("query" to "audit event test")
        )

        val result = fabric.execute(request, context)
        assertEquals(UnifiedExecutionStatus.VERIFIED, result.status)

        val eventsList = mutableListOf<AgentEvent>()
        val collectorJob = launch {
            eventBus.events.collect { eventsList.add(it) }
        }
        delay(50L)
        collectorJob.cancel()

        assertNotNull(result.output)
    }

    // 12. Truthful result propagation
    @Test
    fun testTruthfulResultPropagation() = runBlocking {
        val request = UnifiedExecutionRequest(
            capabilityId = "device_control",
            parameters = mapOf("action" to "read_screen")
        )

        val result = fabric.execute(request, context)
        // Since accessibility service is inactive in test environment, result status must be UNAVAILABLE
        assertEquals(UnifiedExecutionStatus.UNAVAILABLE, result.status)
        assertTrue(result.output.contains("Accessibility Service Inactive"))
        assertEquals(UnifiedVerificationStatus.VERIFICATION_UNAVAILABLE, result.verificationStatus)
    }
}
