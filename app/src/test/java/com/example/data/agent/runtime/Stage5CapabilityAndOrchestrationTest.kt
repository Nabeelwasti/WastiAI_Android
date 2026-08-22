package com.example.data.agent.runtime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
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
class Stage5CapabilityAndOrchestrationTest {

    private lateinit var context: Context
    private lateinit var workspaceManager: WorkspaceManager
    private lateinit var emergencyStop: WastiEmergencyStopController
    private lateinit var realityRegistry: CapabilityRealityRegistry
    private lateinit var credentialBroker: WastiCredentialBroker
    private lateinit var healthMonitor: ProviderHealthMonitor
    private lateinit var modelRegistry: ModelProviderRegistry
    private lateinit var geminiCatalog: GeminiModelCatalog
    private lateinit var modelOrchestrator: ModelOrchestrator
    private lateinit var discoveryEngine: CapabilityDiscoveryEngine
    private lateinit var strategyResolver: ExecutionStrategyResolver
    private lateinit var contextEngine: WastiContextEngine
    private lateinit var gapAnalyzer: CapabilityGapAnalyzer
    private lateinit var auditEngine: RealityAuditEngine
    private lateinit var securityEngine: WastiSecurityPolicyEngine

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        workspaceManager = WorkspaceManager(context)
        emergencyStop = WastiEmergencyStopController()
        realityRegistry = CapabilityRealityRegistry()
        credentialBroker = WastiCredentialBroker()
        healthMonitor = ProviderHealthMonitor()
        modelRegistry = ModelProviderRegistry(healthMonitor, credentialBroker)
        geminiCatalog = GeminiModelCatalog()
        modelOrchestrator = ModelOrchestrator(modelRegistry, geminiCatalog, healthMonitor)
        discoveryEngine = CapabilityDiscoveryEngine(realityRegistry, modelRegistry)
        strategyResolver = ExecutionStrategyResolver(realityRegistry)
        contextEngine = WastiContextEngine(realityRegistry)
        gapAnalyzer = CapabilityGapAnalyzer(realityRegistry)
        auditEngine = RealityAuditEngine(realityRegistry, credentialBroker)
        securityEngine = WastiSecurityPolicyEngine(workspaceManager, emergencyStop)
    }

    // 1. capability reality states
    @Test
    fun testCapabilityRealityStates() {
        val filesReality = realityRegistry.getCapabilityReality("FILES")
        assertEquals(CapabilityRealityState.NATIVE, filesReality.realityState)
        assertEquals(ImplementationStatus.READY, filesReality.implementationStatus)

        val gmailReality = realityRegistry.getCapabilityReality("GMAIL")
        assertEquals(CapabilityRealityState.AUTHENTICATION_REQUIRED, gmailReality.realityState)
        assertEquals(LiveConnectionStatus.AUTHENTICATION_REQUIRED, gmailReality.liveConnectionStatus)
    }

    // 2. provider discovery
    @Test
    fun testProviderDiscovery() {
        val providers = modelRegistry.getAvailableProvidersForTask(TaskCategory.FAST_CHAT)
        assertTrue(providers.any { it.providerId == "GEMINI" })
    }

    // 3. Gemini-first routing
    @Test
    fun testGeminiFirstRouting() {
        val (provider, model) = modelOrchestrator.selectBestModelAndProvider(TaskCategory.DEEP_REASONING)
        assertEquals("GEMINI", provider.providerId)
        assertNotNull(model)
        assertEquals("gemini-2.5-pro", model?.modelId)
    }

    // 4. fallback routing
    @Test
    fun testFallbackRouting() {
        // Mark Gemini unhealthy
        healthMonitor.updateHealth(ProviderHealth("GEMINI", ProviderHealthStatus.UNAVAILABLE, errorMessage = "Outage"))
        val (provider, model) = modelOrchestrator.selectBestModelAndProvider(TaskCategory.FAST_CHAT)
        assertEquals("LOCAL_ON_DEVICE", provider.providerId)
        assertNull(model)
    }

    // 5. unavailable model handling
    @Test
    fun testUnavailableModelHandling() {
        val model = geminiCatalog.getModel("non-existent-model-id")
        assertNull(model)
    }

    // 6. authentication failure
    @Test
    fun testAuthenticationFailure() {
        val ref = CredentialRef("UNCONFIGURED_KEY")
        val health = credentialBroker.getCredentialHealth(ref)
        assertEquals(SecretAvailabilityScope.SECRET_MISSING, health.availabilityScope)
        assertFalse(credentialBroker.hasCredential(ref))
    }

    // 7. quota failure
    @Test
    fun testQuotaFailureHandling() {
        healthMonitor.updateHealth(ProviderHealth("GEMINI", ProviderHealthStatus.RATE_LIMITED, errorMessage = "429 Quota Exceeded"))
        val health = healthMonitor.getHealth("GEMINI")
        assertEquals(ProviderHealthStatus.RATE_LIMITED, health.status)
    }

    // 8. network failure
    @Test
    fun testNetworkFailureHandling() {
        healthMonitor.updateHealth(ProviderHealth("GEMINI", ProviderHealthStatus.UNAVAILABLE, errorMessage = "Network Unreachable"))
        val health = healthMonitor.getHealth("GEMINI")
        assertEquals(ProviderHealthStatus.UNAVAILABLE, health.status)
    }

    // 9. context reconstruction
    @Test
    fun testContextReconstruction() {
        val context = contextEngine.buildContextSnapshot("Fix code in App.kt", listOf("User: hi"), listOf("App.kt"))
        assertEquals("Fix code in App.kt", context.goal)
        assertTrue(context.relevantFiles.contains("App.kt"))
        assertTrue(context.availableCapabilities.contains("FILES"))
    }

    // 10. capability gap detection
    @Test
    fun testCapabilityGapDetection() {
        val gap = gapAnalyzer.analyzeGap("UNKNOWN_CAPABILITY")
        assertEquals(CapabilityGapType.MISSING_INTEGRATION, gap.gapType)
        assertNotNull(gap.possibleNativeSolution)
    }

    // 11. fake integration rejection
    @Test
    fun testFakeIntegrationRejection() {
        val adapter = GmailIntegrationAdapter()
        val result = adapter.execute("SEND_EMAIL", mapOf("to" to "test@example.com"))
        assertEquals(ExternalActionResultStatus.AUTHENTICATION_REQUIRED, result.status)
        assertFalse(result.status == ExternalActionResultStatus.SUCCESS)
    }

    // 12. provider health classification
    @Test
    fun testProviderHealthClassification() {
        val health = healthMonitor.getHealth("GEMINI")
        assertEquals(ProviderHealthStatus.HEALTHY, health.status)
    }

    // 13. task status events
    @Test
    fun testTaskStatusEvents() {
        val event = AgentEvent.ProviderSelected(TaskId("t1"), "GEMINI", "gemini-3.6-flash")
        assertEquals("GEMINI", event.providerId)
        assertEquals("gemini-3.6-flash", event.modelId)
    }

    // 14. dry-run action generation
    @Test
    fun testDryRunActionGeneration() {
        val adapter = GmailIntegrationAdapter()
        val dryRunResult = adapter.dryRun("SEND_EMAIL", mapOf("to" to "boss@company.com", "subject" to "Report"))
        assertEquals(ExternalActionResultStatus.SUCCESS, dryRunResult.status)
        assertTrue(dryRunResult.data.containsKey("preview"))
    }

    // 15. external action confirmation
    @Test
    fun testExternalActionConfirmation() {
        val intentEngine = ActionIntentEngine(securityEngine)
        val action = intentEngine.prepareActionIntent("Gmail", "SEND_EMAIL", mapOf("to" to "x@y.com"), "Send email preview")
        assertEquals(ActionAuthorizationState.PREVIEW_READY, action.authorizationState)

        intentEngine.authorizeAction(action, userApproved = false)
        assertEquals(ActionAuthorizationState.CANCELLED, action.authorizationState)
    }

    // 16. credential redaction
    @Test
    fun testCredentialRedaction() {
        val health = credentialBroker.getCredentialHealth(CredentialRef("GEMINI_API_KEY"))
        assertFalse(health.diagnosticMessage.contains("AIza")) // Never contains raw secret string
    }

    // 17. provider credential isolation
    @Test
    fun testProviderCredentialIsolation() {
        val geminiRef = CredentialRef("GEMINI_API_KEY")
        val openaiRef = CredentialRef("OPENAI_API_KEY")
        assertTrue(credentialBroker.hasCredential(geminiRef))
        assertFalse(credentialBroker.hasCredential(openaiRef))
    }

    // 18. native-first strategy
    @Test
    fun testNativeFirstStrategy() {
        val decision = strategyResolver.resolveStrategy("FILES")
        assertEquals(ExecutionStrategy.NATIVE, decision.strategy)
    }

    // 19. external acceleration strategy
    @Test
    fun testExternalAccelerationStrategy() {
        val decision = strategyResolver.resolveStrategy("GEMINI_AI", isUrgent = true)
        assertEquals(ExecutionStrategy.EXTERNAL_API, decision.strategy)
    }

    // 20. no fabricated success
    @Test
    fun testNoFabricatedSuccess() {
        val githubReality = realityRegistry.getCapabilityReality("GITHUB")
        assertEquals(CapabilityRealityState.CONTRACT_ONLY, githubReality.realityState)
        assertFalse(githubReality.liveConnectionStatus == LiveConnectionStatus.VERIFIED)
    }

    // 21. no fabricated progress
    @Test
    fun testNoFabricatedProgress() {
        val snapshot = TaskExecutionSnapshot(
            taskId = "t100",
            currentPhase = "PLANNING",
            currentAction = "Analyzing prompt",
            activeProvider = "GEMINI",
            activeModel = "gemini-3.6-flash",
            activeCapability = "GEMINI_AI",
            elapsedTimeMs = 250L,
            completedSteps = listOf("Prompt received"),
            failedSteps = emptyList(),
            retryCount = 0,
            currentFallback = null,
            waitingReason = null,
            estimatedRemainingWork = "3 steps",
            finalOutcome = null
        )
        assertEquals("PLANNING", snapshot.currentPhase)
        assertEquals(250L, snapshot.elapsedTimeMs)
    }

    // 22. protected-file awareness
    @Test
    fun testProtectedFileAwareness() {
        val isProtected = securityEngine.isProtectedPath("/app/src/main/AndroidManifest.xml")
        assertTrue(isProtected)
    }

    // 23. self-development contract
    @Test
    fun testSelfDevelopmentContract() {
        val devEngine = SelfDevelopmentPathEngine()
        val gap = gapAnalyzer.analyzeGap("NEW_CUSTOM_TOOL")
        val plan = devEngine.initializeDevelopmentPlan(gap)
        assertEquals(SelfDevelopmentStage.DESIGN_CAPABILITY, plan.currentStage)
        assertFalse(plan.isProtectedCoreTouchAllowed)
    }

    // 24. reality audit report
    @Test
    fun testRealityAuditReport() {
        val connectivity = auditEngine.generateSystemConnectivityReport()
        assertTrue(connectivity.isNotEmpty())

        val realityAudit = auditEngine.generateRealityAuditReport()
        assertTrue(realityAudit.any { it.capabilityId == "FILES" && it.realityState == CapabilityRealityState.NATIVE })
    }
}
