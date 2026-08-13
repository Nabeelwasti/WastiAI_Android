package com.example.data.agent.runtime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Stage7CapabilityDiscoveryAndTerminalTest {

    private lateinit var context: Context
    private lateinit var realityRegistry: CapabilityRealityRegistry
    private lateinit var modelRegistry: ModelProviderRegistry
    private lateinit var discoveryEngine: CapabilityDiscoveryEngine
    private lateinit var strategyResolver: ExecutionStrategyResolver
    private lateinit var workspaceManager: WorkspaceManager
    private lateinit var nativeProvider: WastiNativeExecutionProvider
    private lateinit var fabric: UnifiedExecutionFabric

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        realityRegistry = CapabilityRealityRegistry()
        modelRegistry = ModelProviderRegistry()
        discoveryEngine = CapabilityDiscoveryEngine(realityRegistry, modelRegistry)
        strategyResolver = ExecutionStrategyResolver(realityRegistry)
        workspaceManager = WorkspaceManager(context)
        nativeProvider = WastiNativeExecutionProvider(context, workspaceManager)
        fabric = UnifiedExecutionFabric(
            realityRegistry = realityRegistry,
            eventBus = AgentEventBus(),
            auditEngine = RealityAuditEngine(realityRegistry, WastiCredentialBroker()),
            appContext = context
        )
    }

    // 1. Capability Discovery - Existing Native Capability
    @Test
    fun testCapabilityDiscoveryExistingNativeCapability() {
        val plan = discoveryEngine.inspectCapabilitiesForRequest("Read workspace files and write output")
        assertTrue(plan.requiredCapabilities.contains("FILES"))
        assertTrue(plan.availableCapabilities.contains("FILES"))
        assertEquals("workspace_file_operation", plan.requiredAction)
        assertEquals("STORAGE", plan.capabilityCategory)
        assertEquals(ExecutionStrategy.NATIVE, plan.executionStrategies.first())
    }

    // 2. Capability Discovery - Unavailable Runtime
    @Test
    fun testCapabilityDiscoveryUnavailablePythonRuntime() {
        val plan = discoveryEngine.inspectCapabilitiesForRequest("Run python script to analyze data")
        assertTrue(plan.requiredCapabilities.contains("TERMINAL"))
        assertTrue(plan.requiredCapabilities.contains("PYTHON_RUNTIME"))
        assertTrue(plan.unavailableCapabilities.contains("PYTHON_RUNTIME"))
        assertNotNull(plan.reasonIfUnavailable)
        assertTrue(plan.reasonIfUnavailable!!.contains("Python runtime is not currently available"))
    }

    // 3. Capability Discovery - Authentication Required
    @Test
    fun testCapabilityDiscoveryAuthenticationRequired() {
        val plan = discoveryEngine.inspectCapabilitiesForRequest("Send email via Gmail")
        assertTrue(plan.requiredCapabilities.contains("GMAIL"))
        assertTrue(plan.unavailableCapabilities.contains("GMAIL"))
        assertEquals(CapabilityAuthStatus.REQUIRED_NOT_PROVIDED, plan.authenticationRequirement)
        assertTrue(plan.missingDependencies.contains("OAuth Authentication for Gmail"))
    }

    // 4. Execution Strategy Resolver Hierarchy
    @Test
    fun testExecutionStrategyResolverHierarchy() {
        // Priority 1: NATIVE for workspace files
        val nativeDecision = strategyResolver.resolveStrategy("FILES")
        assertEquals(ExecutionStrategy.NATIVE, nativeDecision.strategy)
        assertEquals("WorkspaceManager", nativeDecision.executorProvider)

        // Priority 7: HUMAN_ASSISTANCE for Gmail (Auth required)
        val authDecision = strategyResolver.resolveStrategy("GMAIL")
        assertEquals(ExecutionStrategy.HUMAN_ASSISTANCE, authDecision.strategy)
        assertTrue(authDecision.reasoning.contains("Authentication required"))

        // Priority 7: HUMAN_ASSISTANCE for unknown / unavailable capability
        val unavailDecision = strategyResolver.resolveStrategy("UNKNOWN_CAPABILITY")
        assertEquals(ExecutionStrategy.HUMAN_ASSISTANCE, unavailDecision.strategy)
    }

    // 5. Workspace Boundary - Valid Path vs Traversal Rejection
    @Test
    fun testWorkspacePathBoundaryEnforcement() {
        val validWrite = workspaceManager.writeFile("test/doc.txt", "Hello Wasti")
        assertTrue(validWrite.isSuccess)

        val readValid = workspaceManager.readFile("test/doc.txt")
        assertTrue(readValid.isSuccess)
        assertEquals("Hello Wasti", readValid.getOrThrow())

        val traversalAttempt = workspaceManager.resolvePathSafely("../../../etc/passwd")
        assertTrue(traversalAttempt.isFailure)
        assertTrue(traversalAttempt.exceptionOrNull() is SecurityException)
    }

    // 6. Native Command Execution & Output Capture
    @Test
    fun testNativeCommandExecutionSuccess() = runBlocking {
        val result = nativeProvider.executeCommand("sh", listOf("-c", "echo WastiNativeRuntime"))
        assertTrue(result.isSuccess)
        assertEquals(0, result.exitCode)
        assertTrue(result.stdout.contains("WastiNativeRuntime"))
        assertEquals("VERIFIED_NATIVE_EXECUTION", result.verificationState)
    }

    // 7. Native Command Execution - Missing Binary Truth Reporting
    @Test
    fun testNativeCommandExecutionMissingPython() = runBlocking {
        val result = nativeProvider.executeCommand("python3", listOf("-c", "print('hello')"))
        assertFalse(result.isSuccess)
        assertEquals(127, result.exitCode)
        assertTrue(result.stderr.contains("Python runtime is not currently available in the native Wasti environment"))
        assertEquals("FAILED_RUNTIME_NOT_INSTALLED", result.verificationState)
    }

    // 8. Runtime Detection
    @Test
    fun testRuntimeCapabilitiesDetection() {
        val runtimes = nativeProvider.detectRuntimes()
        assertNotNull(runtimes["SHELL"])
        assertEquals(RuntimeCapabilityState.AVAILABLE, runtimes["SHELL"]?.state)

        assertNotNull(runtimes["PYTHON_RUNTIME"])
        assertEquals(RuntimeCapabilityState.NOT_INSTALLED, runtimes["PYTHON_RUNTIME"]?.state)
    }

    // 9. Unified Execution Routing for Terminal Commands
    @Test
    fun testUnifiedExecutionRoutingTerminal() = runBlocking {
        val req = UnifiedExecutionRequest(
            capabilityId = "terminal",
            parameters = mapOf("command" to "sh", "arguments" to listOf("-c", "echo UnifiedFabricTerminal"))
        )
        val result = fabric.execute(req)
        assertEquals(UnifiedExecutionStatus.VERIFIED, result.status)
        assertTrue(result.output.contains("UnifiedFabricTerminal"))
        assertEquals("WastiNativeExecutionProvider", result.executor)
        assertEquals(UnifiedVerificationStatus.VERIFIED, result.verificationStatus)
    }

    // 10. Unified Execution Routing for Missing Python Execution
    @Test
    fun testUnifiedExecutionRoutingMissingPython() = runBlocking {
        val req = UnifiedExecutionRequest(
            capabilityId = "python",
            parameters = mapOf("command" to "python3", "arguments" to listOf("script.py"))
        )
        val result = fabric.execute(req)
        assertEquals(UnifiedExecutionStatus.UNAVAILABLE, result.status)
        assertTrue(result.output.contains("Python runtime is not currently available"))
        assertEquals(UnifiedVerificationStatus.VERIFICATION_UNAVAILABLE, result.verificationStatus)
    }
}
