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
@Config(sdk = [34])
class Stage7MultiLanguageDevelopmentTest {

    private lateinit var context: Context
    private lateinit var realityRegistry: CapabilityRealityRegistry
    private lateinit var modelRegistry: ModelProviderRegistry
    private lateinit var discoveryEngine: CapabilityDiscoveryEngine
    private lateinit var workspaceManager: WorkspaceManager
    private lateinit var languagePlatform: WastiLanguagePlatform
    private lateinit var fabric: UnifiedExecutionFabric

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        realityRegistry = CapabilityRealityRegistry()
        modelRegistry = ModelProviderRegistry()
        discoveryEngine = CapabilityDiscoveryEngine(realityRegistry, modelRegistry)
        workspaceManager = WorkspaceManager(context)
        languagePlatform = WastiLanguagePlatform(context, workspaceManager)
        fabric = UnifiedExecutionFabric(
            realityRegistry = realityRegistry,
            eventBus = AgentEventBus(),
            auditEngine = RealityAuditEngine(realityRegistry, WastiCredentialBroker()),
            appContext = context
        )
    }

    // 1. Language Support Matrix Verification
    @Test
    fun testMultiLanguageMatrixPopulated() {
        val profiles = languagePlatform.getAllProfiles()
        assertTrue(profiles.isNotEmpty())

        val py = languagePlatform.getProfile("PYTHON")
        assertNotNull(py)
        assertTrue(py!!.codeGenerationSupported)
        assertTrue(py.codeEditingSupported)
        assertTrue(py.fileExtensions.contains(".py"))

        val kt = languagePlatform.getProfile("KOTLIN")
        assertNotNull(kt)
        assertEquals(RuntimeCapabilityState.AVAILABLE, kt!!.runtimeState)
        assertEquals(CapabilityRealityState.NATIVE, kt.realityState)

        val sh = languagePlatform.getProfile("SHELL")
        assertNotNull(sh)
        assertEquals(RuntimeCapabilityState.AVAILABLE, sh!!.runtimeState)
        assertEquals(CapabilityRealityState.NATIVE, sh.realityState)

        val rust = languagePlatform.getProfile("RUST")
        assertNotNull(rust)
        assertEquals(RuntimeCapabilityState.NOT_INSTALLED, rust!!.runtimeState)
        assertEquals(LanguageCapabilityStatus.COMPILER_UNAVAILABLE, rust.compilerState)
    }

    // 2. Code Generation vs Execution Separation
    @Test
    fun testCodeGenerationSupportedEvenWhenRuntimeNotInstalled() {
        val goProfile = languagePlatform.getProfile("GO")
        assertNotNull(goProfile)
        assertTrue("Code generation should be supported", goProfile!!.codeGenerationSupported)
        assertTrue("Code editing should be supported", goProfile.codeEditingSupported)
        assertEquals(RuntimeCapabilityState.NOT_INSTALLED, goProfile.runtimeState)
        assertEquals(LanguageCapabilityStatus.EXECUTION_UNAVAILABLE, goProfile.executionState)
    }

    // 3. Project Creation - Python Scaffolding
    @Test
    fun testCreatePythonProjectScaffolding() {
        val req = ProjectCreationRequest(
            projectName = "DataAnalyzer",
            language = "PYTHON",
            template = "script",
            description = "Analyzes sales data",
            dependencies = listOf("requests", "pandas")
        )
        val result = languagePlatform.createProject(req)
        assertTrue(result.isSuccess)
        assertEquals("Python 3", result.language)
        assertTrue(result.createdFiles.contains("projects/DataAnalyzer/README.md"))
        assertTrue(result.createdFiles.contains("projects/DataAnalyzer/main.py"))
        assertTrue(result.createdFiles.contains("projects/DataAnalyzer/requirements.txt"))

        val readPy = workspaceManager.readFile("projects/DataAnalyzer/main.py")
        assertTrue(readPy.isSuccess)
        assertTrue(readPy.getOrThrow().contains("DataAnalyzer"))
    }

    // 4. Project Creation - Kotlin Scaffolding
    @Test
    fun testCreateKotlinProjectScaffolding() {
        val req = ProjectCreationRequest(
            projectName = "WastiModule",
            language = "KOTLIN",
            template = "android_module"
        )
        val result = languagePlatform.createProject(req)
        assertTrue(result.isSuccess)
        assertTrue(result.createdFiles.contains("projects/WastiModule/Main.kt"))

        val readKt = workspaceManager.readFile("projects/WastiModule/Main.kt")
        assertTrue(readKt.isSuccess)
        assertTrue(readKt.getOrThrow().contains("package WastiModule"))
    }

    // 5. Capability Discovery for Project Dev Manager
    @Test
    fun testCapabilityDiscoveryForNewProject() {
        val plan = discoveryEngine.inspectCapabilitiesForRequest("Create a python project for data analytics")
        assertTrue(plan.requiredCapabilities.contains("PROJECT_DEV_MANAGER"))
        assertTrue(plan.availableCapabilities.contains("PROJECT_DEV_MANAGER"))
        assertEquals("create_development_project", plan.requiredAction)
        assertEquals("DEVELOPMENT", plan.capabilityCategory)
        assertEquals(ExecutionStrategy.NATIVE, plan.executionStrategies.first())
    }

    // 6. Unified Execution Fabric - Project Creation Routing
    @Test
    fun testUnifiedExecutionFabricCreateProject() = runBlocking {
        val execReq = UnifiedExecutionRequest(
            capabilityId = "project_dev_manager",
            parameters = mapOf(
                "action" to "create_project",
                "projectName" to "WebDemo",
                "language" to "WEB_MARKUP"
            )
        )
        val result = fabric.execute(execReq)
        assertEquals(UnifiedExecutionStatus.VERIFIED, result.status)
        assertEquals("WastiLanguagePlatform", result.executor)
        assertTrue(result.output.contains("WebDemo"))
        assertEquals(UnifiedVerificationStatus.VERIFIED, result.verificationStatus)

        val readHtml = workspaceManager.readFile("projects/WebDemo/index.html")
        assertTrue(readHtml.isSuccess)
        assertTrue(readHtml.getOrThrow().contains("WebDemo"))
    }

    // 7. Unified Execution Fabric - Workspace File Operations
    @Test
    fun testUnifiedExecutionFabricWorkspaceFileOps() = runBlocking {
        val writeReq = UnifiedExecutionRequest(
            capabilityId = "files",
            parameters = mapOf(
                "action" to "write_file",
                "path" to "notes/todo.txt",
                "content" to "Stage 7 Native Environment"
            )
        )
        val writeResult = fabric.execute(writeReq)
        assertEquals(UnifiedExecutionStatus.VERIFIED, writeResult.status)

        val readReq = UnifiedExecutionRequest(
            capabilityId = "files",
            parameters = mapOf(
                "action" to "read_file",
                "path" to "notes/todo.txt"
            )
        )
        val readResult = fabric.execute(readReq)
        assertEquals(UnifiedExecutionStatus.VERIFIED, readResult.status)
        assertEquals("Stage 7 Native Environment", readResult.output)
    }

    // 8. Unified Execution Fabric - Language Profile Inspection
    @Test
    fun testUnifiedExecutionFabricLanguageProfileQuery() = runBlocking {
        val queryReq = UnifiedExecutionRequest(
            capabilityId = "project_dev_manager",
            parameters = mapOf(
                "action" to "get_language_profile",
                "language" to "KOTLIN"
            )
        )
        val result = fabric.execute(queryReq)
        assertEquals(UnifiedExecutionStatus.VERIFIED, result.status)
        assertTrue(result.output.contains("Kotlin"))
        assertTrue(result.output.contains("Runtime=AVAILABLE"))
    }
}
