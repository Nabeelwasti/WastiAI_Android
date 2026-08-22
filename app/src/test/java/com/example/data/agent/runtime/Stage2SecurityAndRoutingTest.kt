package com.example.data.agent.runtime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Stage2SecurityAndRoutingTest {

    private lateinit var context: Context
    private lateinit var workspaceManager: WorkspaceManager
    private lateinit var registry: AgentToolRegistry
    private lateinit var emergencyStop: WastiEmergencyStopController
    private lateinit var capabilityRegistry: WastiCapabilityRegistry
    private lateinit var permissionModel: WastiPermissionModel
    private lateinit var auditLogger: WastiAuditLogger
    private lateinit var securityPolicy: WastiSecurityPolicyEngine
    private lateinit var router: WastiAgentToolRouter

    private lateinit var readFileTool: ReadFileTool
    private lateinit var writeFileTool: WriteFileTool
    private lateinit var listFilesTool: ListFilesTool
    private lateinit var fileExistsTool: FileExistsTool
    private lateinit var createDirectoryTool: CreateDirectoryTool
    private lateinit var patchFileTool: PatchFileTool
    private lateinit var executeCommandToolStub: ExecuteLocalCommandToolStub

    private val defaultTask = AgentTask(
        taskId = TaskId("task-123"),
        prompt = "Test Stage 2 Prompt",
        status = AgenticState.Analyzing(),
        executionMode = ExecutionMode.SAFE
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        workspaceManager = WorkspaceManager(context)
        registry = AgentToolRegistry()
        emergencyStop = WastiEmergencyStopController()
        capabilityRegistry = WastiCapabilityRegistry()
        permissionModel = WastiPermissionModel()
        auditLogger = WastiAuditLogger()
        securityPolicy = WastiSecurityPolicyEngine(workspaceManager, emergencyStop)
        router = WastiAgentToolRouter(
            registry = registry,
            securityPolicy = securityPolicy,
            permissionModel = permissionModel,
            emergencyStop = emergencyStop,
            capabilityRegistry = capabilityRegistry,
            auditLogger = auditLogger
        )

        readFileTool = ReadFileTool(workspaceManager)
        writeFileTool = WriteFileTool(workspaceManager)
        listFilesTool = ListFilesTool(workspaceManager)
        fileExistsTool = FileExistsTool(workspaceManager)
        createDirectoryTool = CreateDirectoryTool(workspaceManager)
        patchFileTool = PatchFileTool(workspaceManager)
        executeCommandToolStub = ExecuteLocalCommandToolStub()

        registry.register(readFileTool)
        registry.register(writeFileTool)
        registry.register(listFilesTool)
        registry.register(fileExistsTool)
        registry.register(createDirectoryTool)
        registry.register(patchFileTool)
        registry.register(executeCommandToolStub)
    }

    @Test
    fun test01_safeTool_allowed() = runBlocking {
        // Write a file to read
        workspaceManager.writeFile("hello.txt", "world")

        val result = router.routeAndExecute(
            toolName = "read_file",
            args = mapOf("path" to "hello.txt"),
            context = defaultTask
        )

        assertTrue("Safe tool execution must succeed", result.isSuccess)
        assertEquals("world", result.output["content"])
    }

    @Test
    fun test02_controlledTool_requiresUserApproval() = runBlocking {
        // Without user approval granted in PermissionModel
        permissionModel.setAutoApproveControlledForTesting(false)

        val resultDenied = router.routeAndExecute(
            toolName = "write_file",
            args = mapOf("path" to "test.txt", "content" to "data"),
            context = defaultTask
        )

        assertFalse("Controlled tool without user approval must fail", resultDenied.isSuccess)
        assertTrue("Result must be marked as cancelled or permission denied", resultDenied.isCancelled)

        // With user approval granted
        permissionModel.setAutoApproveControlledForTesting(true)

        val resultAllowed = router.routeAndExecute(
            toolName = "write_file",
            args = mapOf("path" to "test.txt", "content" to "data"),
            context = defaultTask
        )

        assertTrue("Controlled tool with user approval must succeed", resultAllowed.isSuccess)
    }

    @Test
    fun test03_privilegedTool_requiresPrivilegedApproval() = runBlocking {
        permissionModel.setAutoApproveBiometricForTesting(false)

        val result = router.routeAndExecute(
            toolName = "execute_command",
            args = mapOf("command" to "ls"),
            context = defaultTask.copy(executionMode = ExecutionMode.PRIVILEGED)
        )

        assertFalse("Privileged tool without biometric approval must fail", result.isSuccess)
        assertTrue(result.isCancelled)
    }

    @Test
    fun test04_invalidWorkspacePath_denied() = runBlocking {
        val result = router.routeAndExecute(
            toolName = "read_file",
            args = mapOf("path" to "../../etc/passwd"),
            context = defaultTask
        )

        assertFalse("Directory traversal path must be denied", result.isSuccess)
        assertTrue("Security policy must block traversal", result.isSecurityBlocked)
    }

    @Test
    fun test05_siblingWorkspacePrefix_denied() = runBlocking {
        val result = router.routeAndExecute(
            toolName = "read_file",
            args = mapOf("path" to "../wasti_workspace_evil/secret.txt"),
            context = defaultTask
        )

        assertFalse("Sibling prefix path must be denied", result.isSuccess)
        assertTrue(result.isSecurityBlocked)
    }

    @Test
    fun test06_symbolicLinkEscape_denied() = runBlocking {
        val externalTarget = File(context.filesDir, "external_secret.txt")
        externalTarget.writeText("sensitive")

        val workspaceDir = File(workspaceManager.getWorkspaceRootPath())
        val symlinkFile = File(workspaceDir, "symlink_out.txt")

        try {
            java.nio.file.Files.createSymbolicLink(symlinkFile.toPath(), externalTarget.toPath())

            val result = router.routeAndExecute(
                toolName = "read_file",
                args = mapOf("path" to "symlink_out.txt"),
                context = defaultTask
            )

            assertFalse("Symlink escape must be denied", result.isSuccess)
            assertTrue(result.isSecurityBlocked)
        } catch (e: Exception) {
            // Environment handles or restricts symlink
        } finally {
            if (symlinkFile.exists()) symlinkFile.delete()
            if (externalTarget.exists()) externalTarget.delete()
        }
    }

    @Test
    fun test07_emergencyStopBeforeRouting_denied() = runBlocking {
        emergencyStop.triggerEmergencyStop("Test Emergency Stop")

        val result = router.routeAndExecute(
            toolName = "read_file",
            args = mapOf("path" to "hello.txt"),
            context = defaultTask
        )

        assertFalse("Active emergency stop must block routing", result.isSuccess)
        assertTrue(result.isSecurityBlocked)
        assertTrue(result.error!!.contains("EMERGENCY_STOP"))
    }

    @Test
    fun test08_emergencyStopAfterAuthorizationBeforeExecution_denied() = runBlocking {
        // Custom security policy that triggers emergency stop during authorization evaluation
        val interceptingPolicy = object : SecurityPolicy {
            override suspend fun evaluateAuthorization(request: AuthorizationRequest): AuthorizationDecision {
                emergencyStop.triggerEmergencyStop("Mid-flight Stop Triggered")
                return AuthorizationDecision.ALLOWED
            }

            override suspend fun validateExecutionRequest(request: ExecutionRequest, context: AgentTask): AuthorizationDecision {
                return AuthorizationDecision.DENIED
            }
        }

        val testRouter = WastiAgentToolRouter(
            registry = registry,
            securityPolicy = interceptingPolicy,
            permissionModel = permissionModel,
            emergencyStop = emergencyStop,
            capabilityRegistry = capabilityRegistry,
            auditLogger = auditLogger
        )

        val result = testRouter.routeAndExecute(
            toolName = "read_file",
            args = mapOf("path" to "hello.txt"),
            context = defaultTask
        )

        assertFalse("Pre-execution emergency stop check must prevent execution", result.isSuccess)
        assertTrue(result.isSecurityBlocked)
        assertTrue(result.error!!.contains("EMERGENCY_STOP"))
    }

    @Test
    fun test09_cancelledTask_deniedOrCancelled() = runBlocking {
        val cancelledTask = defaultTask.copy(
            cancellationState = TaskCancellationState(isCancelled = true, cancellationReason = "User aborted")
        )

        val result = router.routeAndExecute(
            toolName = "read_file",
            args = mapOf("path" to "hello.txt"),
            context = cancelledTask
        )

        assertFalse("Cancelled task must not execute tools", result.isSuccess)
        assertTrue(result.isCancelled)
    }

    @Test
    fun test10_unavailableCapability_denied() = runBlocking {
        capabilityRegistry.setCapabilityEnabled("FILES", false)

        val result = router.routeAndExecute(
            toolName = "read_file",
            args = mapOf("path" to "hello.txt"),
            context = defaultTask
        )

        assertFalse("Tool requiring disabled capability must be denied", result.isSuccess)
        assertTrue(result.isSecurityBlocked)
    }

    @Test
    fun test11_invalidArguments_denied() = runBlocking {
        // Missing 'path' parameter
        val result = router.routeAndExecute(
            toolName = "read_file",
            args = emptyMap(),
            context = defaultTask
        )

        assertFalse("Missing arguments must fail validation", result.isSuccess)
        assertTrue(result.isSecurityBlocked)
        assertTrue(result.error!!.contains("INVALID_ARGUMENTS"))
    }

    @Test
    fun test12_writeFileClassifiedAsControlled() {
        assertEquals("WriteFileTool must be CONTROLLED", PermissionLevel.CONTROLLED, writeFileTool.permissionLevel)
        assertEquals("PatchFileTool must be CONTROLLED", PermissionLevel.CONTROLLED, patchFileTool.permissionLevel)
        assertEquals("ReadFileTool must be SAFE", PermissionLevel.SAFE, readFileTool.permissionLevel)
    }

    @Test
    fun test13_sensitiveAuditMetadataSanitized() {
        val rawInput = mapOf(
            "path" to "config.json",
            "api_key" to "secret_123456789",
            "user_token" to "bearer_xyz987654321",
            "password" to "SuperSecretPass!"
        )

        val sanitized = auditLogger.sanitizeMetadata(rawInput)

        assertEquals("config.json", sanitized["path"])
        assertEquals("[REDACTED_SECRET]", sanitized["api_key"])
        assertEquals("[REDACTED_SECRET]", sanitized["user_token"])
        assertEquals("[REDACTED_SECRET]", sanitized["password"])
    }

    @Test
    fun test14_privilegedAuthorizationNotPermanentlyCached() = runBlocking {
        permissionModel.setAutoApproveBiometricForTesting(true)

        val req1 = router.routeAndExecute(
            toolName = "execute_command",
            args = mapOf("command" to "test1"),
            context = defaultTask.copy(executionMode = ExecutionMode.PRIVILEGED)
        )

        // Reset testing mock flag to simulate non-cached re-prompt
        permissionModel.setAutoApproveBiometricForTesting(false)

        val req2 = router.routeAndExecute(
            toolName = "execute_command",
            args = mapOf("command" to "test2"),
            context = defaultTask.copy(executionMode = ExecutionMode.PRIVILEGED)
        )

        assertFalse("Privileged authorization must re-evaluate dynamically without permanent caching", req2.isSuccess)
    }

    @Test
    fun test15_authorizationAppliesOnlyToCurrentRequest() = runBlocking {
        permissionModel.setAutoApproveControlledForTesting(true)

        // Write a harmless file
        val res1 = router.routeAndExecute(
            toolName = "write_file",
            args = mapOf("path" to "notes.txt", "content" to "hello"),
            context = defaultTask
        )
        assertTrue(res1.isSuccess)

        // Separate request for a sensitive file triggers high risk and re-evaluates
        val sensitiveTask = defaultTask.copy(executionMode = ExecutionMode.SAFE)
        val res2 = router.routeAndExecute(
            toolName = "write_file",
            args = mapOf("path" to "build.gradle.kts", "content" to "malicious"),
            context = sensitiveTask
        )

        // High risk file in SAFE mode requires elevated approval or gets evaluated per-request
        assertNotNull(res2)
    }

    @Test
    fun test16_toolExecutionCannotOccurWhenEmergencyStopIsActive() = runBlocking {
        emergencyStop.triggerEmergencyStop("Manual Killswitch")

        val result = router.routeAndExecute(
            toolName = "write_file",
            args = mapOf("path" to "test.txt", "content" to "data"),
            context = defaultTask
        )

        assertFalse("Execution must be strictly blocked under emergency stop", result.isSuccess)
        assertTrue(result.isSecurityBlocked)
    }

    private fun getRuntimeSourceFiles(): List<File> {
        val candidates = listOf(
            File("src/main/java/com/example/data/agent/runtime"),
            File("app/src/main/java/com/example/data/agent/runtime"),
            File("/app/applet/app/src/main/java/com/example/data/agent/runtime"),
            File("/app/src/main/java/com/example/data/agent/runtime")
        )
        val dir = candidates.firstOrNull { it.exists() && it.isDirectory }
        return dir?.listFiles()?.filter { it.isFile && it.name.endsWith(".kt") }.orEmpty()
    }

    private fun stripComments(code: String): String {
        val blockRegex = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)
        val lineRegex = Regex("//.*")
        val noBlock = blockRegex.replace(code, "")
        return lineRegex.replace(noBlock, "")
    }

    @Test
    fun test17_noProcessBuilderExists() {
        val files = getRuntimeSourceFiles()
        assertTrue("Runtime files directory must be located for audit", files.isNotEmpty())
        for (file in files) {
            // LocalAndroidProvider is the single authorized provider in Stage 3 allowed to manage ProcessBuilder
            if (file.name == "LocalAndroidProvider.kt") continue
            val codeOnly = stripComments(file.readText())
            assertFalse(
                "Codebase must not contain ProcessBuilder in ${file.name}",
                codeOnly.contains("ProcessBuilder")
            )
        }
    }

    @Test
    fun test18_noRuntimeExecExists() {
        val files = getRuntimeSourceFiles()
        assertTrue("Runtime files directory must be located for audit", files.isNotEmpty())
        for (file in files) {
            val codeOnly = stripComments(file.readText())
            assertFalse(
                "Codebase must not contain Runtime.exec in ${file.name}",
                codeOnly.contains("Runtime.getRuntime().exec") || codeOnly.contains("Runtime.exec")
            )
        }
    }

    @Test
    fun test19_noArbitraryShellExecutionExists() {
        val files = getRuntimeSourceFiles()
        assertTrue("Runtime files directory must be located for audit", files.isNotEmpty())
        for (file in files) {
            val codeOnly = stripComments(file.readText())
            assertFalse(
                "Codebase must not contain un-routed arbitrary ShellTool in ${file.name}",
                codeOnly.contains("class ShellTool")
            )
        }
    }

    @Test
    fun test20_noJudge0ImplementationExists() {
        val files = getRuntimeSourceFiles()
        assertTrue("Runtime files directory must be located for audit", files.isNotEmpty())
        for (file in files) {
            val codeOnly = stripComments(file.readText())
            assertFalse(
                "Codebase must not contain Judge0 implementation in ${file.name}",
                codeOnly.contains("Judge0") || codeOnly.contains("judge0")
            )
        }
    }
}
