package com.example.data.agent.runtime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Stage7WastiNativeDevEnvironmentTest {

    private lateinit var context: Context
    private lateinit var realityRegistry: CapabilityRealityRegistry
    private lateinit var workspaceManager: WorkspaceManager
    private lateinit var runtimeManager: WastiRuntimeManager
    private lateinit var projectManager: WastiProjectManager
    private lateinit var buildAndTestManager: WastiBuildAndTestManager
    private lateinit var sandbox: WastiSandbox
    private lateinit var fabric: UnifiedExecutionFabric
    private lateinit var observationEngine: WastiObservationEngine
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val testWorkspaceDir = File(context.filesDir, "wasti_workspace")
        if (testWorkspaceDir.exists()) {
            testWorkspaceDir.deleteRecursively()
        }
        testWorkspaceDir.mkdirs()

        realityRegistry = CapabilityRealityRegistry()
        workspaceManager = WorkspaceManager(context)
        runtimeManager = WastiRuntimeManager(context, workspaceManager)
        projectManager = WastiProjectManager(context, workspaceManager)
        buildAndTestManager = WastiBuildAndTestManager(context, workspaceManager, runtimeManager)
        sandbox = WastiSandbox(context, workspaceManager, runtimeManager, projectManager, buildAndTestManager)
        fabric = UnifiedExecutionFabric(
            realityRegistry = realityRegistry,
            eventBus = AgentEventBus(),
            auditEngine = RealityAuditEngine(realityRegistry, WastiCredentialBroker()),
            appContext = context
        )
        observationEngine = WastiObservationEngine()
    }

    @After
    fun tearDown() {
        try {
            val testWorkspaceDir = File(context.filesDir, "wasti_workspace")
            if (testWorkspaceDir.exists()) {
                testWorkspaceDir.deleteRecursively()
            }
        } catch (_: Exception) {}
    }

    // 1. Runtime Manager & Reality Status Tests
    @Test
    fun testRuntimeManagerDiscoveryAndResolution() {
        val allRuntimes = runtimeManager.getAllRuntimes()
        assertTrue(allRuntimes.isNotEmpty())

        val shellRuntime = runtimeManager.getRuntime("SHELL")
        assertNotNull(shellRuntime)
        assertEquals(RuntimeSource.NATIVE_WASTI_RUNTIME, shellRuntime!!.source)

        val kotlinRuntime = runtimeManager.getRuntime("KOTLIN")
        assertNotNull(kotlinRuntime)
        assertEquals(RuntimeRealityStatus.AVAILABLE, kotlinRuntime!!.status)
        assertTrue(kotlinRuntime.compilerAvailable)

        val sqliteRuntime = runtimeManager.getRuntime("SQL")
        assertNotNull(sqliteRuntime)
        assertEquals(RuntimeRealityStatus.AVAILABLE, sqliteRuntime!!.status)

        val pyRuntime = runtimeManager.getRuntime("PYTHON")
        assertNotNull(pyRuntime)
        assertEquals("pip", pyRuntime!!.packageManagerName)

        val rustRuntime = runtimeManager.getRuntime("RUST")
        assertNotNull(rustRuntime)
        assertEquals(RuntimeRealityStatus.TOOLCHAIN_MISSING, rustRuntime!!.status)
        assertEquals("cargo", rustRuntime.packageManagerName)
    }

    @Test
    fun testPackageManagerResolution() {
        val res = runtimeManager.resolvePackage("KOTLIN", "kotlinx-coroutines-core")
        assertTrue(res.isSuccess)
        assertEquals(RuntimeRealityStatus.AVAILABLE, res.status)

        val rustRes = runtimeManager.resolvePackage("RUST", "tokio")
        assertFalse(rustRes.isSuccess)
        assertEquals(RuntimeRealityStatus.TOOLCHAIN_MISSING, rustRes.status)
    }

    // 2. Project Manager Lifecycle Tests
    @Test
    fun testProjectManagerCreateInspectListDelete() {
        val createRes = projectManager.createManagedProject(
            name = "analytics_tool",
            language = "PYTHON",
            template = "script",
            description = "Data processing utility",
            dependencies = listOf("requests", "pandas")
        )

        assertTrue(createRes.isSuccess)
        val meta = createRes.getOrThrow()
        assertEquals("analytics_tool", meta.name)
        assertEquals("PYTHON", meta.language)
        assertEquals(2, meta.dependencies.size)

        // Inspect
        val inspectRes = projectManager.inspectProject("analytics_tool")
        assertTrue(inspectRes.isSuccess)
        val insp = inspectRes.getOrThrow()
        assertEquals("PYTHON", insp.detectedLanguage)
        assertTrue(insp.totalFiles >= 2)
        assertTrue(insp.fileList.contains("wasti_project.json"))

        // List
        val projects = projectManager.listProjects()
        assertTrue(projects.contains("analytics_tool"))

        // Delete
        val deleted = projectManager.deleteProject("analytics_tool")
        assertTrue(deleted)
        assertFalse(projectManager.listProjects().contains("analytics_tool"))
    }

    // 3. Build & Test Manager Lifecycle Tests
    @Test
    fun testBuildAndTestManagerExecution() = runTest(testDispatcher) {
        // Create project first
        projectManager.createManagedProject("web_dashboard", "WEB_MARKUP", "static_page")

        // Build
        val buildRes = buildAndTestManager.buildProject(
            BuildRequest(
                projectId = "web_dashboard",
                projectPath = "projects/web_dashboard",
                language = "WEB_MARKUP"
            )
        )

        assertEquals(BuildStatus.SUCCESS, buildRes.status)
        assertEquals(0, buildRes.exitCode)
        assertTrue(buildRes.artifacts.isNotEmpty())
        assertEquals("VERIFIED_WEB_BUILD", buildRes.verificationState)

        // Test
        val testReport = buildAndTestManager.runTests(
            projectId = "web_dashboard",
            projectPath = "projects/web_dashboard",
            language = "WEB_MARKUP"
        )

        assertEquals(TestExecutionStatus.STATICALLY_VALIDATED, testReport.status)
        assertTrue(testReport.totalTests >= 1)
        assertEquals(testReport.totalTests, testReport.passedTests)
        assertEquals(0, testReport.failedTests)
    }

    @Test
    fun testDiagnosticsAnalysis() {
        val sampleLog = """
            Scanning files...
            SyntaxError: invalid syntax at main.py line 4
            Warning: deprecated feature used at utils.py line 12
        """.trimIndent()

        val report = buildAndTestManager.analyzeDiagnostics("test_proj", sampleLog)
        assertTrue(report.hasErrors)
        assertEquals(1, report.totalErrors)
        assertEquals(1, report.totalWarnings)
        assertEquals(2, report.findings.size)
        assertEquals("SyntaxError", report.findings[0].errorType)
    }

    // 4. Wasti Sandbox Security & Confinement Tests
    @Test
    fun testSandboxPathEscapeBlocked() = runTest(testDispatcher) {
        val res = sandbox.executeInSandbox(
            SandboxExecutionRequest(
                command = "sh",
                workingDirectory = "../../system"
            )
        )

        assertFalse(res.isSuccess)
        assertTrue(res.isPolicyBlocked)
        assertEquals("PATH_TRAVERSAL_BLOCKED", res.blockedReason)
        assertEquals("BLOCKED_WORKSPACE_BOUNDARY", res.verificationState)
    }

    // 5. Unified Execution Fabric Routing Tests
    @Test
    fun testFabricRoutingDevCapabilities() = runTest(testDispatcher) {
        // Managed Project Creation via Fabric
        val createReq = UnifiedExecutionRequest(
            taskId = "task_create_proj",
            actionId = "action_1",
            capabilityId = "project_dev_manager",
            parameters = mapOf(
                "action" to "create_managed_project",
                "projectName" to "fabric_test_proj",
                "language" to "PYTHON"
            )
        )

        val createResult = fabric.execute(createReq)
        assertEquals(UnifiedExecutionStatus.VERIFIED, createResult.status)
        assertEquals("WastiProjectManager", createResult.executor)

        // Observation of project creation
        val obsReq = ObservationRequest(
            taskId = createReq.taskId,
            actionId = createReq.actionId,
            capabilityId = createReq.capabilityId
        )
        val obsRes = observationEngine.observe(obsReq, context, createResult)
        assertEquals(ObservationStatus.OBSERVED, obsRes.status)

        // Build Project via Fabric
        val buildReq = UnifiedExecutionRequest(
            taskId = "task_build_proj",
            actionId = "action_2",
            capabilityId = "build_project",
            parameters = mapOf(
                "projectId" to "fabric_test_proj",
                "projectPath" to "projects/fabric_test_proj",
                "language" to "PYTHON"
            )
        )

        val buildResult = fabric.execute(buildReq)
        assertEquals(UnifiedExecutionStatus.VERIFIED, buildResult.status)
        assertEquals("WastiBuildAndTestManager", buildResult.executor)

        // Test Project via Fabric
        val testReq = UnifiedExecutionRequest(
            taskId = "task_test_proj",
            actionId = "action_3",
            capabilityId = "test_project",
            parameters = mapOf(
                "projectId" to "fabric_test_proj",
                "projectPath" to "projects/fabric_test_proj",
                "language" to "PYTHON"
            )
        )

        val testResult = fabric.execute(testReq)
        assertEquals(UnifiedExecutionStatus.VERIFIED, testResult.status)
        assertEquals("WastiBuildAndTestManager", testResult.executor)

        // Diagnostics via Fabric
        val diagReq = UnifiedExecutionRequest(
            taskId = "task_diag_proj",
            actionId = "action_4",
            capabilityId = "debug_project",
            parameters = mapOf(
                "projectId" to "fabric_test_proj",
                "logs" to "Error: variable not defined"
            )
        )

        val diagResult = fabric.execute(diagReq)
        assertEquals(UnifiedExecutionStatus.VERIFIED, diagResult.status)

        // Package Manager via Fabric
        val pkgReq = UnifiedExecutionRequest(
            taskId = "task_pkg",
            actionId = "action_5",
            capabilityId = "package_manager",
            parameters = mapOf(
                "language" to "KOTLIN",
                "packageName" to "kotlinx-serialization"
            )
        )

        val pkgResult = fabric.execute(pkgReq)
        assertEquals(UnifiedExecutionStatus.VERIFIED, pkgResult.status)
    }
}

