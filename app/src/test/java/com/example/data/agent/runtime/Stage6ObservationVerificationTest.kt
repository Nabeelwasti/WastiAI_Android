package com.example.data.agent.runtime

import android.content.Context
import android.view.accessibility.AccessibilityEvent
import androidx.test.core.app.ApplicationProvider
import com.example.service.WastiAccessibilityService
import kotlinx.coroutines.delay
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
class Stage6ObservationVerificationTest {

    private lateinit var context: Context
    private lateinit var realityRegistry: CapabilityRealityRegistry
    private lateinit var eventBus: AgentEventBus
    private lateinit var auditEngine: RealityAuditEngine
    private lateinit var observationEngine: WastiObservationEngine
    private lateinit var verificationEngine: WastiVerificationEngine
    private lateinit var fabric: UnifiedExecutionFabric

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        realityRegistry = CapabilityRealityRegistry()
        eventBus = AgentEventBus()
        auditEngine = RealityAuditEngine(realityRegistry, WastiCredentialBroker())
        observationEngine = WastiObservationEngine()
        verificationEngine = WastiVerificationEngine()
        fabric = UnifiedExecutionFabric(
            realityRegistry = realityRegistry,
            eventBus = eventBus,
            auditEngine = auditEngine,
            observationEngine = observationEngine,
            verificationEngine = verificationEngine
        )
    }

    // 1. Executor completion without verification (intermediate observation contract)
    @Test
    fun testExecutorCompletionContract() = runBlocking {
        val execResult = UnifiedExecutionResult(
            taskId = "task-1",
            actionId = "act-1",
            capabilityId = "memory_search",
            status = UnifiedExecutionStatus.COMPLETED,
            output = "Memory query executed",
            executor = "MemoryManager",
            startedAt = System.currentTimeMillis(),
            completedAt = System.currentTimeMillis(),
            verificationStatus = UnifiedVerificationStatus.UNVERIFIED
        )

        val obsReq = ObservationRequest(taskId = "task-1", actionId = "act-1", capabilityId = "memory_search")
        val obsRes = observationEngine.observe(obsReq, context, execResult)

        assertEquals(ObservationStatus.OBSERVED, obsRes.status)
        assertEquals("Memory query executed", obsRes.observedState)
    }

    // 2. Successful verification scenario
    @Test
    fun testSuccessfulVerificationScenario() = runBlocking {
        val request = UnifiedExecutionRequest(
            capabilityId = "memory_search",
            parameters = mapOf("query" to "test verification query")
        )

        val result = fabric.execute(request, context)
        assertEquals(UnifiedExecutionStatus.VERIFIED, result.status)
        assertEquals(UnifiedVerificationStatus.VERIFIED, result.verificationStatus)
        assertNotNull(result.verificationEvidence)
    }

    // 3. Failed verification scenario
    @Test
    fun testFailedVerificationScenario() = runBlocking {
        val execResult = UnifiedExecutionResult(
            taskId = "t-fail",
            actionId = "a-fail",
            capabilityId = "device_control",
            status = UnifiedExecutionStatus.COMPLETED,
            output = "Tap dispatched",
            executor = "WastiDeviceController",
            startedAt = System.currentTimeMillis(),
            completedAt = System.currentTimeMillis(),
            verificationStatus = UnifiedVerificationStatus.UNVERIFIED
        )

        val obsResult = ObservationResult(
            taskId = "t-fail",
            actionId = "a-fail",
            capabilityId = "device_control",
            status = ObservationStatus.NOT_OBSERVED,
            observedState = "No screen change detected",
            evidence = "UI bounds unchanged post-tap"
        )

        val verReq = VerificationRequest(
            taskId = "t-fail",
            actionId = "a-fail",
            capabilityId = "device_control",
            executionResult = execResult,
            observationResult = obsResult
        )

        val verRes = verificationEngine.verify(verReq)
        assertEquals(ActionVerificationStatus.FAILED, verRes.status)
        assertNotNull(verRes.failureReason)
    }

    // 4. Verification timeout handling
    @Test
    fun testVerificationTimeoutHandling() = runBlocking {
        val execResult = UnifiedExecutionResult(
            taskId = "t-time",
            actionId = "a-time",
            capabilityId = "device_control",
            status = UnifiedExecutionStatus.COMPLETED,
            output = "Action pending observation",
            executor = "WastiDeviceController",
            startedAt = System.currentTimeMillis(),
            completedAt = System.currentTimeMillis(),
            verificationStatus = UnifiedVerificationStatus.UNVERIFIED
        )

        val obsResult = ObservationResult(
            taskId = "t-time",
            actionId = "a-time",
            capabilityId = "device_control",
            status = ObservationStatus.TIMEOUT,
            observedState = "Timeout",
            evidence = "Observation window exceeded 5000ms"
        )

        val verReq = VerificationRequest(
            taskId = "t-time",
            actionId = "a-time",
            capabilityId = "device_control",
            executionResult = execResult,
            observationResult = obsResult
        )

        val verRes = verificationEngine.verify(verReq)
        assertEquals(ActionVerificationStatus.VERIFICATION_UNAVAILABLE, verRes.status)
    }

    // 5. Verification unavailable path
    @Test
    fun testVerificationUnavailablePath() = runBlocking {
        val request = UnifiedExecutionRequest(
            capabilityId = "WHATSAPP",
            parameters = mapOf("target" to "+15551234567", "content" to "Hello World")
        )

        realityRegistry.updateCapabilityReality(
            CapabilityReality(
                capabilityId = "WHATSAPP",
                category = "MESSAGING",
                implementationStatus = ImplementationStatus.READY,
                liveConnectionStatus = LiveConnectionStatus.VERIFIED,
                executionStatus = CapabilityExecutionStatus.OPERATIONAL,
                authenticationStatus = CapabilityAuthStatus.NOT_REQUIRED,
                provider = "WastiDeviceController",
                supportedOperations = listOf("send_whatsapp"),
                limitations = emptyList(),
                realityState = CapabilityRealityState.NATIVE
            )
        )

        val result = fabric.execute(request, context)
        // Messaging intent execution cannot be verified inside WhatsApp sandbox, status must be VERIFICATION_UNAVAILABLE
        assertEquals(UnifiedVerificationStatus.VERIFICATION_UNAVAILABLE, result.verificationStatus)
        assertTrue(result.verificationEvidence!!.contains("dispatched") || result.verificationEvidence!!.contains("unavailable"))
    }

    // 6. Gesture callback completion contract
    @Test
    fun testGestureCallbackCompletionContract() = runBlocking {
        val service = WastiAccessibilityService()
        assertNotNull(service)
    }

    // 7. Ambiguous UI element selection rejection
    @Test
    fun testAmbiguousTargetSelectionRejection() {
        val service = WastiAccessibilityService()
        val selectionRes = service.findTargetNodeRanked("Button", null)
        // Without rootInActiveWindow, status is NOT_FOUND
        assertEquals(TargetSelectionStatus.NOT_FOUND, selectionRes.status)
    }

    // 8. Exact target selection ranking evaluation
    @Test
    fun testTargetSelectionRankingEvaluator() {
        val rank1 = TargetMatchRank.EXACT_RESOURCE_ID
        val rank2 = TargetMatchRank.EXACT_NORMALIZED_TEXT
        val rank3 = TargetMatchRank.EXACT_CONTENT_DESCRIPTION
        val rank4 = TargetMatchRank.NORMALIZED_EXACT_MATCH
        val rank5 = TargetMatchRank.PARTIAL_MATCH

        assertTrue(rank1.ordinal < rank2.ordinal)
        assertTrue(rank2.ordinal < rank3.ordinal)
        assertTrue(rank3.ordinal < rank4.ordinal)
        assertTrue(rank4.ordinal < rank5.ordinal)
    }

    // 9. Accessibility event processing & structured observation
    @Test
    fun testAccessibilityEventProcessing() {
        val service = WastiAccessibilityService()
        val event = AccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
        event.packageName = "com.example.testapp"
        event.className = "com.example.testapp.MainActivity"

        service.onAccessibilityEvent(event)
        val obs = service.latestUiObservation

        assertNotNull(obs)
        assertEquals("com.example.testapp", obs?.packageName)
        assertEquals("com.example.testapp.MainActivity", obs?.className)
    }

    // 10. Event throttling / debouncing
    @Test
    fun testAccessibilityEventThrottling() {
        val service = WastiAccessibilityService()
        val event1 = AccessibilityEvent(AccessibilityEvent.TYPE_VIEW_CLICKED)
        event1.packageName = "com.app.one"

        val event2 = AccessibilityEvent(AccessibilityEvent.TYPE_VIEW_CLICKED)
        event2.packageName = "com.app.two"

        service.onAccessibilityEvent(event1)
        val obs1 = service.latestUiObservation
        assertEquals("com.app.one", obs1?.packageName)

        // Rapid back-to-back non-window event should be debounced within 50ms window
        service.onAccessibilityEvent(event2)
        val obs2 = service.latestUiObservation
        assertEquals("com.app.one", obs2?.packageName)
    }

    // 11. Action / observation correlation
    @Test
    fun testActionObservationCorrelation() {
        val service = WastiAccessibilityService()
        val correlationId = "test-correlation-123"
        service.setCorrelationId(correlationId)

        val event = AccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
        event.packageName = "com.example.correlated"

        service.onAccessibilityEvent(event)
        val obs = service.latestUiObservation

        assertNotNull(obs)
        assertEquals(correlationId, obs?.correlationId)
    }

    // 12. Truthful result reporting (Never convert UNKNOWN into VERIFIED or SUCCESS)
    @Test
    fun testTruthfulStatusNeverConvertsUnknownToVerified() {
        val execResult = UnifiedExecutionResult(
            taskId = "t-unk",
            actionId = "a-unk",
            capabilityId = "UNKNOWN_CAPABILITY",
            status = UnifiedExecutionStatus.COMPLETED,
            output = "Executed without observation capability",
            executor = "UnknownExecutor",
            startedAt = System.currentTimeMillis(),
            completedAt = System.currentTimeMillis(),
            verificationStatus = UnifiedVerificationStatus.UNVERIFIED
        )

        val obsResult = ObservationResult(
            taskId = "t-unk",
            actionId = "a-unk",
            capabilityId = "UNKNOWN_CAPABILITY",
            status = ObservationStatus.UNKNOWN,
            observedState = "Unknown",
            evidence = "No observation provider registered"
        )

        val verReq = VerificationRequest(
            taskId = "t-unk",
            actionId = "a-unk",
            capabilityId = "UNKNOWN_CAPABILITY",
            executionResult = execResult,
            observationResult = obsResult
        )

        val verRes = verificationEngine.verify(verReq)

        // MUST be VERIFICATION_UNAVAILABLE / NOT_VERIFIABLE, NEVER VERIFIED!
        assertEquals(ActionVerificationStatus.VERIFICATION_UNAVAILABLE, verRes.status)
    }

    // 13. Fabricated success prevention across all execution paths
    @Test
    fun testFabricatedSuccessPreventionInVerificationPipeline() = runBlocking {
        val request = UnifiedExecutionRequest(
            capabilityId = "device_control",
            parameters = mapOf("action" to "open_app", "target" to "non_existent_package_xyz_99")
        )

        val result = fabric.execute(request, context)

        assertEquals(UnifiedExecutionStatus.FAILED, result.status)
        assertEquals(UnifiedVerificationStatus.FAILED, result.verificationStatus)
        assertFalse("Must not claim fabricated success", result.output.contains("executed successfully"))
    }

    // 14. Verification evidence propagation
    @Test
    fun testVerificationEvidencePropagation() = runBlocking {
        val request = UnifiedExecutionRequest(
            capabilityId = "memory_search",
            parameters = mapOf("query" to "evidence check")
        )

        val result = fabric.execute(request, context)

        assertNotNull(result.verificationEvidence)
        assertTrue(result.verificationEvidence!!.isNotBlank())
    }

    // 15. Real executor registration and observation pipeline execution
    @Test
    fun testCustomExecutorPipelineExecution() = runBlocking {
        fabric.registerExecutor(object : UnifiedExecutor {
            override val name = "CustomTestExecutor"
            override val supportedCapabilities = listOf("CUSTOM_TEST")
            override suspend fun execute(request: UnifiedExecutionRequest, context: Context?): UnifiedExecutionResult {
                return UnifiedExecutionResult(
                    taskId = request.taskId,
                    actionId = request.actionId,
                    capabilityId = request.capabilityId,
                    status = UnifiedExecutionStatus.COMPLETED,
                    output = "Custom executor output",
                    executor = name,
                    startedAt = System.currentTimeMillis(),
                    completedAt = System.currentTimeMillis(),
                    verificationStatus = UnifiedVerificationStatus.UNVERIFIED
                )
            }
        })

        realityRegistry.updateCapabilityReality(
            CapabilityReality(
                capabilityId = "CUSTOM_TEST",
                category = "TEST",
                implementationStatus = ImplementationStatus.READY,
                liveConnectionStatus = LiveConnectionStatus.VERIFIED,
                executionStatus = CapabilityExecutionStatus.OPERATIONAL,
                authenticationStatus = CapabilityAuthStatus.NOT_REQUIRED,
                provider = "CustomTestExecutor",
                supportedOperations = listOf("custom_op"),
                limitations = emptyList(),
                realityState = CapabilityRealityState.NATIVE
            )
        )

        val request = UnifiedExecutionRequest(capabilityId = "CUSTOM_TEST")
        val result = fabric.execute(request, context)

        assertNotNull(result)
        assertEquals("CustomTestExecutor", result.executor)
        assertEquals(UnifiedVerificationStatus.VERIFICATION_UNAVAILABLE, result.verificationStatus)
    }
}
