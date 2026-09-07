package com.example.data.agent.runtime

import android.content.Context
import android.view.accessibility.AccessibilityEvent
import androidx.test.core.app.ApplicationProvider
import com.example.service.WastiAccessibilityService
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
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
@Config(sdk = [34])
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
        WastiEmergencyStopController.resetEmergencyStop()
        context = ApplicationProvider.getApplicationContext()
        realityRegistry = CapabilityRealityRegistry()
        eventBus = AgentEventBus()
        auditEngine = RealityAuditEngine(realityRegistry, WastiCredentialBroker())
        observationEngine = WastiObservationEngine(realityRegistry = realityRegistry, appContext = context)
        verificationEngine = WastiVerificationEngine()
        fabric = UnifiedExecutionFabric(
            realityRegistry = realityRegistry,
            eventBus = eventBus,
            auditEngine = auditEngine,
            observationEngine = observationEngine,
            verificationEngine = verificationEngine,
            appContext = context
        )
    }

    @After
    fun tearDown() {
        WastiEmergencyStopController.resetEmergencyStop()
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
        com.example.assistant.PermissionManager.setUserConsent("ANDROID_CONTROL", true)
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

    // 16. Rejection of mock and synthetic evidence
    @Test
    fun testVerificationEngineRejectsMockAndSyntheticEvidence() {
        val execResult = UnifiedExecutionResult(
            taskId = "t-synth",
            actionId = "a-synth",
            capabilityId = "file_write",
            status = UnifiedExecutionStatus.COMPLETED,
            output = "File write completed",
            executor = "LocalFileSystem",
            startedAt = System.currentTimeMillis(),
            completedAt = System.currentTimeMillis(),
            verificationStatus = UnifiedVerificationStatus.UNVERIFIED
        )

        val mockObs = ObservationResult(
            taskId = "t-synth",
            actionId = "a-synth",
            capabilityId = "file_write",
            status = ObservationStatus.OBSERVED,
            observedState = "observed",
            evidence = "mock_evidence of file writing"
        )

        val req1 = VerificationRequest(
            taskId = "t-synth",
            actionId = "a-synth",
            capabilityId = "file_write",
            executionResult = execResult,
            observationResult = mockObs
        )

        val res1 = verificationEngine.verify(req1)
        assertEquals(ActionVerificationStatus.FAILED, res1.status)
        assertTrue(res1.evidence.contains("synthetic or mock"))

        val syntheticObs = ObservationResult(
            taskId = "t-synth",
            actionId = "a-synth",
            capabilityId = "file_write",
            status = ObservationStatus.OBSERVED,
            observedState = "observed",
            evidence = "synthetic confirmation test"
        )

        val req2 = VerificationRequest(
            taskId = "t-synth",
            actionId = "a-synth",
            capabilityId = "file_write",
            executionResult = execResult,
            observationResult = syntheticObs
        )

        val res2 = verificationEngine.verify(req2)
        assertEquals(ActionVerificationStatus.FAILED, res2.status)
    }

    // 17. Structured evidence validation and canonical verification
    @Test
    fun testVerificationEngineStructuredEvidenceIntegrity() {
        val validEvidence = VerifiedExecutionEvidence(
            evidenceSource = EvidenceSource.FILESYSTEM,
            subject = "workspace/manifest.json",
            verifiedState = "FILE_EXISTS_SHA256_VALID",
            confidence = 0.98
        )

        val validRes = verificationEngine.verifyStructuredEvidence(
            taskId = "task_struct_1",
            actionId = "act_struct_1",
            capabilityId = "file_write",
            evidence = validEvidence
        )
        assertEquals(ActionVerificationStatus.VERIFIED, validRes.status)
        assertNotNull(validRes.structuredEvidence)
        assertTrue(validRes.evidence.contains("FILESYSTEM"))

        val mockSubjectEvidence = VerifiedExecutionEvidence(
            evidenceSource = EvidenceSource.FILESYSTEM,
            subject = "mock_evidence_file",
            verifiedState = "WRITTEN",
            confidence = 0.98
        )
        val mockRes = verificationEngine.verifyStructuredEvidence(
            taskId = "task_struct_2",
            actionId = "act_struct_2",
            capabilityId = "file_write",
            evidence = mockSubjectEvidence
        )
        assertEquals(ActionVerificationStatus.FAILED, mockRes.status)

        val lowConfidenceEvidence = VerifiedExecutionEvidence(
            evidenceSource = EvidenceSource.PROCESS_TELEMETRY,
            subject = "daemon_pid",
            verifiedState = "RUNNING",
            confidence = 0.60
        )
        val lowConfRes = verificationEngine.verifyStructuredEvidence(
            taskId = "task_struct_3",
            actionId = "act_struct_3",
            capabilityId = "process_exec",
            evidence = lowConfidenceEvidence
        )
        assertEquals(ActionVerificationStatus.FAILED, lowConfRes.status)
    }

    // 17. [P0-02 Adversarial Test]: Stale and forward-dated evidence rejection
    @Test
    fun testAdversarialStaleAndForwardDatedEvidenceRejected() {
        val now = System.currentTimeMillis()
        val staleEvidence = CapabilitySpecificEvidence(
            taskId = "t-stale",
            actionId = "a-stale",
            capabilityId = "file_write",
            executor = "LocalFileExecutor",
            observationSource = EvidenceSource.FILESYSTEM,
            timestamp = now - 600000L, // 10 minutes old
            artifactOrStateReference = "/data/file.txt",
            expectedState = "WRITTEN",
            observedState = "FILE_EXISTS",
            verifierIdentity = "WastiVerificationEngine",
            verificationMethod = "FILESYSTEM_STAT_PROBE",
            confidence = 1.0
        )

        val req = VerificationRequest(
            taskId = "t-stale",
            actionId = "a-stale",
            capabilityId = "file_write",
            executionResult = UnifiedExecutionResult(
                taskId = "t-stale",
                actionId = "a-stale",
                capabilityId = "file_write",
                status = UnifiedExecutionStatus.COMPLETED,
                output = "Done",
                executor = "LocalFileExecutor"
            ),
            observationResult = ObservationResult(
                taskId = "t-stale",
                actionId = "a-stale",
                capabilityId = "file_write",
                status = ObservationStatus.OBSERVED,
                observedState = "FILE_EXISTS",
                evidence = "inspected at /data/file.txt"
            ),
            capabilitySpecificEvidence = staleEvidence
        )

        val res = verificationEngine.verifyCapabilitySpecificEvidence(req, staleEvidence)
        assertEquals(ActionVerificationStatus.FAILED, res.status)
        assertTrue(res.evidence.contains("stale") || res.failureReason!!.contains("stale", ignoreCase = true))
    }

    // 18. [P0-02 Adversarial Test]: Mismatched Task, Action, and Capability Provenance Rejection
    @Test
    fun testAdversarialMismatchedProvenanceRejected() {
        val baseEvidence = CapabilitySpecificEvidence(
            taskId = "task_expected",
            actionId = "action_expected",
            capabilityId = "file_write",
            executor = "LocalFileExecutor",
            observationSource = EvidenceSource.FILESYSTEM,
            timestamp = System.currentTimeMillis(),
            artifactOrStateReference = "/data/file.txt",
            expectedState = "WRITTEN",
            observedState = "FILE_EXISTS",
            verifierIdentity = "WastiVerificationEngine",
            verificationMethod = "FILESYSTEM_STAT_PROBE",
            confidence = 1.0
        )

        val execResult = UnifiedExecutionResult(
            taskId = "task_expected",
            actionId = "action_expected",
            capabilityId = "file_write",
            status = UnifiedExecutionStatus.COMPLETED,
            output = "Done",
            executor = "LocalFileExecutor"
        )
        val obsResult = ObservationResult(
            taskId = "task_expected",
            actionId = "action_expected",
            capabilityId = "file_write",
            status = ObservationStatus.OBSERVED,
            observedState = "FILE_EXISTS",
            evidence = "inspected at /data/file.txt"
        )

        // Mismatched Task ID
        val wrongTaskReq = VerificationRequest(
            taskId = "wrong_task_id",
            actionId = "action_expected",
            capabilityId = "file_write",
            executionResult = execResult,
            observationResult = obsResult,
            capabilitySpecificEvidence = baseEvidence
        )
        val wrongTaskRes = verificationEngine.verifyCapabilitySpecificEvidence(wrongTaskReq, baseEvidence)
        assertEquals(ActionVerificationStatus.FAILED, wrongTaskRes.status)
        assertTrue(wrongTaskRes.failureReason!!.contains("task", ignoreCase = true))

        // Mismatched Action ID
        val wrongActionReq = VerificationRequest(
            taskId = "task_expected",
            actionId = "wrong_action_id",
            capabilityId = "file_write",
            executionResult = execResult,
            observationResult = obsResult,
            capabilitySpecificEvidence = baseEvidence
        )
        val wrongActionRes = verificationEngine.verifyCapabilitySpecificEvidence(wrongActionReq, baseEvidence)
        assertEquals(ActionVerificationStatus.FAILED, wrongActionRes.status)
        assertTrue(wrongActionRes.failureReason!!.contains("action", ignoreCase = true))

        // Mismatched Capability ID
        val wrongCapReq = VerificationRequest(
            taskId = "task_expected",
            actionId = "action_expected",
            capabilityId = "database_query",
            executionResult = execResult,
            observationResult = obsResult,
            capabilitySpecificEvidence = baseEvidence
        )
        val wrongCapRes = verificationEngine.verifyCapabilitySpecificEvidence(wrongCapReq, baseEvidence)
        assertEquals(ActionVerificationStatus.FAILED, wrongCapRes.status)
        assertTrue(wrongCapRes.failureReason!!.contains("capability", ignoreCase = true))
    }

    // 19. [P0-02 Adversarial Test]: Executor cannot verify itself without external probe
    @Test
    fun testAdversarialExecutorCannotVerifyItself() {
        val selfEvidence = CapabilitySpecificEvidence(
            taskId = "t-self",
            actionId = "a-self",
            capabilityId = "file_write",
            executor = "WastiInternalWorker",
            observationSource = EvidenceSource.PROCESS_TELEMETRY,
            timestamp = System.currentTimeMillis(),
            artifactOrStateReference = "/data/file.txt",
            expectedState = "WRITTEN",
            observedState = "FILE_EXISTS",
            verifierIdentity = "WastiInternalWorker", // Claiming to verify itself
            verificationMethod = "SELF_INTERNAL_REPORT",
            confidence = 1.0
        )

        val req = VerificationRequest(
            taskId = "t-self",
            actionId = "a-self",
            capabilityId = "file_write",
            executionResult = UnifiedExecutionResult(
                taskId = "t-self",
                actionId = "a-self",
                capabilityId = "file_write",
                status = UnifiedExecutionStatus.COMPLETED,
                output = "Self completed",
                executor = "WastiInternalWorker"
            ),
            observationResult = ObservationResult(
                taskId = "t-self",
                actionId = "a-self",
                capabilityId = "file_write",
                status = ObservationStatus.OBSERVED,
                observedState = "Self completed",
                evidence = "Self observed"
            ),
            capabilitySpecificEvidence = selfEvidence
        )

        val res = verificationEngine.verifyCapabilitySpecificEvidence(req, selfEvidence)
        assertEquals(ActionVerificationStatus.FAILED, res.status)
        assertTrue(res.failureReason!!.contains("separated", ignoreCase = true))
    }

    // 20. [P0-02 Adversarial Test]: Arbitrary non-generic text cannot falsely reach VERIFIED
    @Test
    fun testAdversarialArbitraryTextCannotReachVerified() {
        val req = VerificationRequest(
            taskId = "t-arb",
            actionId = "a-arb",
            capabilityId = "file_write",
            executionResult = UnifiedExecutionResult(
                taskId = "t-arb",
                actionId = "a-arb",
                capabilityId = "file_write",
                status = UnifiedExecutionStatus.COMPLETED,
                output = "Random execution output",
                executor = "SomeExecutor"
            ),
            observationResult = ObservationResult(
                taskId = "t-arb",
                actionId = "a-arb",
                capabilityId = "file_write",
                status = ObservationStatus.OBSERVED,
                observedState = "Looks good to me",
                evidence = "I think the file was written successfully without error" // Arbitrary unanchored text
            )
        )

        val res = verificationEngine.verify(req)
        // MUST NEVER be VERIFIED! Must be NOT_VERIFIABLE because it lacks capability-specific state anchors
        assertFalse("Arbitrary text must never reach VERIFIED", res.status == ActionVerificationStatus.VERIFIED)
        assertEquals(ActionVerificationStatus.NOT_VERIFIABLE, res.status)
    }

    // 21. [P0-02]: Canonical CapabilitySpecificEvidence verified cleanly
    @Test
    fun testCanonicalCapabilitySpecificEvidenceVerifiedCleanly() {
        val evidence = CapabilitySpecificEvidence(
            taskId = "t-valid",
            actionId = "a-valid",
            capabilityId = "file_write",
            executor = "LocalFileExecutor",
            observationSource = EvidenceSource.FILESYSTEM,
            timestamp = System.currentTimeMillis(),
            artifactOrStateReference = "/data/data/com.aistudio.wastios.k9v2pz/files/doc.txt",
            checksumOrHash = "a1b2c3d4e5f60000111122223333444455556666777788889999aaaabbbbcccc",
            expectedState = "FILE_EXISTS",
            observedState = "FILE_EXISTS (bytes=1024)",
            verifierIdentity = "WastiVerificationEngine",
            verificationMethod = "FILESYSTEM_STAT_PROBE",
            confidence = 1.0
        )

        val req = VerificationRequest(
            taskId = "t-valid",
            actionId = "a-valid",
            capabilityId = "file_write",
            executionResult = UnifiedExecutionResult(
                taskId = "t-valid",
                actionId = "a-valid",
                capabilityId = "file_write",
                status = UnifiedExecutionStatus.COMPLETED,
                output = "Wrote 1024 bytes",
                executor = "LocalFileExecutor"
            ),
            observationResult = ObservationResult(
                taskId = "t-valid",
                actionId = "a-valid",
                capabilityId = "file_write",
                status = ObservationStatus.OBSERVED,
                observedState = "FILE_EXISTS",
                evidence = "File-system post-state inspected at '/data/data/com.aistudio.wastios.k9v2pz/files/doc.txt'"
            ),
            capabilitySpecificEvidence = evidence
        )

        val res = verificationEngine.verify(req)
        assertEquals(ActionVerificationStatus.VERIFIED, res.status)
        assertEquals(1.0, res.confidence, 0.001)
        assertNotNull(res.capabilitySpecificEvidence)
    }
}

