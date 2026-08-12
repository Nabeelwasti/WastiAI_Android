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
@Config(sdk = [36])
class Stage3ExecutionFabricTest {

    private lateinit var context: Context
    private lateinit var workspaceManager: WorkspaceManager
    private lateinit var toolRegistry: AgentToolRegistry
    private lateinit var emergencyStop: WastiEmergencyStopController
    private lateinit var capabilityRegistry: WastiCapabilityRegistry
    private lateinit var permissionModel: WastiPermissionModel
    private lateinit var auditLogger: WastiAuditLogger
    private lateinit var securityPolicy: WastiSecurityPolicyEngine
    private lateinit var toolRouter: WastiAgentToolRouter

    private lateinit var providerRegistry: WastiExecutionProviderRegistry
    private lateinit var providerRouter: ExecutionProviderRouter
    private lateinit var localProvider: LocalAndroidProvider
    private lateinit var executeCodeTool: ExecuteCodeTool

    private val defaultTask = AgentTask(
        taskId = TaskId("task-stage3"),
        prompt = "Test Stage 3 Execution Fabric",
        status = AgenticState.Executing(),
        executionMode = ExecutionMode.PRIVILEGED
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        workspaceManager = WorkspaceManager(context)
        toolRegistry = AgentToolRegistry()
        emergencyStop = WastiEmergencyStopController()
        capabilityRegistry = WastiCapabilityRegistry()
        permissionModel = WastiPermissionModel().apply {
            setAutoApproveControlledForTesting(true)
            setAutoApproveBiometricForTesting(true)
        }
        auditLogger = WastiAuditLogger()
        securityPolicy = WastiSecurityPolicyEngine(workspaceManager, emergencyStop)

        providerRegistry = WastiExecutionProviderRegistry()
        providerRouter = ExecutionProviderRouter(providerRegistry)
        localProvider = LocalAndroidProvider(workspaceManager)

        // Register default local provider capability advertisement
        providerRegistry.registerProvider(
            localProvider,
            ProviderCapabilityAdvertisement(
                providerId = "local_android_provider",
                providerName = "Local Android Process Execution Provider",
                supportedLanguages = listOf("sh", "kotlin", "java", "python"),
                supportedExecutables = listOf("sh", "echo", "cat", "ls", "pwd", "true", "dalvikvm", "kotlinc", "javac", "java", "python3"),
                requiresNetwork = false,
                maxDurationMs = 60000L,
                reliabilityRating = 0.99
            )
        )

        executeCodeTool = ExecuteCodeTool(providerRouter)
        toolRegistry.register(executeCodeTool)

        toolRouter = WastiAgentToolRouter(
            registry = toolRegistry,
            securityPolicy = securityPolicy,
            permissionModel = permissionModel,
            emergencyStop = emergencyStop,
            capabilityRegistry = capabilityRegistry,
            auditLogger = auditLogger
        )
    }

    @Test
    fun test01_providerSelection_success() {
        val request = ExecutionRequest(
            executable = "echo",
            arguments = listOf("hello"),
            workingDirectory = ".",
            language = "sh"
        )

        val provider = providerRouter.selectProvider(request)
        assertNotNull("Router must select local provider for echo/sh request", provider)
        assertTrue(provider is LocalAndroidProvider)
    }

    @Test
    fun test02_unsupportedLanguage_returnsProviderUnavailable() = runBlocking {
        val request = ExecutionRequest(
            executable = "rustc",
            arguments = listOf("main.rs"),
            workingDirectory = ".",
            language = "rust"
        )

        val result = providerRouter.execute(request)
        assertEquals(ExecutionErrorType.PROVIDER_UNAVAILABLE, result.errorType)
        assertFalse(result.status.isSuccess)
    }

    @Test
    fun test03_unsupportedExecutable_rejectedByProvider() = runBlocking {
        val request = ExecutionRequest(
            executable = "unauthorized_binary_xyz",
            arguments = emptyList(),
            workingDirectory = "."
        )

        val result = localProvider.execute(request)
        assertEquals(ExecutionErrorType.INVALID_REQUEST, result.errorType)
        assertTrue(result.stderr.contains("UNSUPPORTED_EXECUTABLE"))
    }

    @Test
    fun test04_validWorkspaceExecution_success() = runBlocking {
        workspaceManager.writeFile("script.sh", "echo 'stage3_works'")

        val result = toolRouter.routeAndExecute(
            toolName = "execute_code",
            args = mapOf(
                "executable" to "sh",
                "arguments" to listOf("script.sh"),
                "workingDirectory" to ".",
                "language" to "sh"
            ),
            context = defaultTask
        )

        assertTrue("Execution of valid script in workspace must succeed", result.isSuccess)
        val stdout = result.output["stdout"] as? String
        assertNotNull(stdout)
        assertTrue(stdout!!.contains("stage3_works"))
    }

    @Test
    fun test05_workspaceEscapeAttempt_blockedBySecurity() = runBlocking {
        val result = toolRouter.routeAndExecute(
            toolName = "execute_code",
            args = mapOf(
                "executable" to "sh",
                "arguments" to listOf("test.sh"),
                "workingDirectory" to "../../etc",
                "language" to "sh"
            ),
            context = defaultTask
        )

        assertFalse("Workspace escape attempt must be blocked", result.isSuccess)
        assertTrue(result.isSecurityBlocked)
    }

    @Test
    fun test06_timeoutHandling_returnsTimeoutResult() = runBlocking {
        workspaceManager.writeFile("loop.sh", "while true; do sleep 1; done")

        val result = localProvider.execute(
            ExecutionRequest(
                executable = "sh",
                arguments = listOf("loop.sh"),
                workingDirectory = ".",
                timeoutMs = 500L
            )
        )

        assertFalse(result.status.isSuccess)
        assertEquals(ExecutionErrorType.TIMEOUT, result.errorType)
        assertTrue(result.stderr.contains("TIMEOUT"))
    }

    @Test
    fun test07_processCancellation_destroysProcess() = runBlocking {
        workspaceManager.writeFile("sleep.sh", "sleep 10")

        val request = ExecutionRequest(
            executable = "sh",
            arguments = listOf("sleep.sh"),
            workingDirectory = ".",
            timeoutMs = 300L
        )

        val startTime = System.currentTimeMillis()
        val result = localProvider.execute(request)
        val elapsed = System.currentTimeMillis() - startTime

        assertTrue("Cancelled/timed out process must terminate quickly", elapsed < 3000L)
        assertEquals(ExecutionErrorType.TIMEOUT, result.errorType)
    }

    @Test
    fun test08_outputLimit_truncatesOutput() = runBlocking {
        val smallLimitProvider = LocalAndroidProvider(
            workspaceManager = workspaceManager,
            maxOutputSizeBytes = 50
        )

        workspaceManager.writeFile("long_output.sh", "for i in \$(seq 1 100); do echo \"Line \$i padding data text string\"; done")

        val result = smallLimitProvider.execute(
            ExecutionRequest(
                executable = "sh",
                arguments = listOf("long_output.sh"),
                workingDirectory = "."
            )
        )

        assertTrue(result.stdout.contains("[OUTPUT TRUNCATED"))
    }

    @Test
    fun test09_remoteProviderFailure_handledStructurally() = runBlocking {
        val mockFailHttpClient = object : RemoteSandboxCodeExecutionProvider.RemoteSandboxHttpClient {
            override suspend fun postExecutionRequest(
                endpoint: String,
                apiKey: String?,
                request: ExecutionRequest
            ): RemoteSandboxCodeExecutionProvider.RemoteSandboxResponse {
                return RemoteSandboxCodeExecutionProvider.RemoteSandboxResponse(
                    statusCode = 503,
                    isServiceUnavailable = true
                )
            }
        }

        val remoteProvider = RemoteSandboxCodeExecutionProvider(
            endpointUrlSupplier = { "https://sandbox.wasti.ai/execute" },
            apiKeySupplier = { "key123" },
            httpClientAdapter = mockFailHttpClient
        )

        val result = remoteProvider.execute(
            ExecutionRequest(executable = "python3", arguments = listOf("app.py"), workingDirectory = ".")
        )

        assertFalse(result.status.isSuccess)
        assertEquals(ExecutionErrorType.PROVIDER_UNAVAILABLE, result.errorType)
    }

    @Test
    fun test10_authenticationFailure_handledStructurally() = runBlocking {
        val mockAuthFailHttpClient = object : RemoteSandboxCodeExecutionProvider.RemoteSandboxHttpClient {
            override suspend fun postExecutionRequest(
                endpoint: String,
                apiKey: String?,
                request: ExecutionRequest
            ): RemoteSandboxCodeExecutionProvider.RemoteSandboxResponse {
                return RemoteSandboxCodeExecutionProvider.RemoteSandboxResponse(
                    statusCode = 401,
                    isAuthFailed = true
                )
            }
        }

        val remoteProvider = RemoteSandboxCodeExecutionProvider(
            endpointUrlSupplier = { "https://sandbox.wasti.ai/execute" },
            apiKeySupplier = { "invalid_key" },
            httpClientAdapter = mockAuthFailHttpClient
        )

        val result = remoteProvider.execute(
            ExecutionRequest(executable = "python3", arguments = listOf("app.py"), workingDirectory = ".")
        )

        assertFalse(result.status.isSuccess)
        assertEquals(ExecutionErrorType.AUTHENTICATION_FAILED, result.errorType)
    }

    @Test
    fun test11_quotaFailure_handledStructurally() = runBlocking {
        val mockQuotaHttpClient = object : RemoteSandboxCodeExecutionProvider.RemoteSandboxHttpClient {
            override suspend fun postExecutionRequest(
                endpoint: String,
                apiKey: String?,
                request: ExecutionRequest
            ): RemoteSandboxCodeExecutionProvider.RemoteSandboxResponse {
                return RemoteSandboxCodeExecutionProvider.RemoteSandboxResponse(
                    statusCode = 429,
                    isQuotaExhausted = true
                )
            }
        }

        val remoteProvider = RemoteSandboxCodeExecutionProvider(
            endpointUrlSupplier = { "https://sandbox.wasti.ai/execute" },
            apiKeySupplier = { "valid_key" },
            httpClientAdapter = mockQuotaHttpClient
        )

        val result = remoteProvider.execute(
            ExecutionRequest(executable = "python3", arguments = listOf("app.py"), workingDirectory = ".")
        )

        assertFalse(result.status.isSuccess)
        assertEquals(ExecutionErrorType.QUOTA_EXHAUSTED, result.errorType)
    }

    @Test
    fun test12_emergencyStop_interceptsExecuteCodeTool() = runBlocking {
        emergencyStop.triggerEmergencyStop("Test Stage 3 Killswitch")

        val result = toolRouter.routeAndExecute(
            toolName = "execute_code",
            args = mapOf(
                "executable" to "echo",
                "arguments" to listOf("hello"),
                "workingDirectory" to "."
            ),
            context = defaultTask
        )

        assertFalse("Emergency stop must intercept execute_code tool", result.isSuccess)
        assertTrue(result.isSecurityBlocked)
    }

    @Test
    fun test13_capabilityUnavailable_blocksExecuteCodeTool() = runBlocking {
        capabilityRegistry.setCapabilityEnabled("CODING", false)

        val result = toolRouter.routeAndExecute(
            toolName = "execute_code",
            args = mapOf(
                "executable" to "echo",
                "arguments" to listOf("hello"),
                "workingDirectory" to "."
            ),
            context = defaultTask
        )

        assertFalse("Disabled CODING capability must block execute_code", result.isSuccess)
        assertTrue(result.isSecurityBlocked)
    }

    @Test
    fun test14_providerFallback_switchesToBackupProvider() = runBlocking {
        val failingProvider = object : CodeExecutionProvider {
            override suspend fun execute(request: ExecutionRequest): ExecutionResult {
                return ExecutionResult(
                    stdout = "", stderr = "Down", exitCode = -1, executionTimeMs = 0L,
                    status = ExecutionStatus(false, "Down"), errorType = ExecutionErrorType.PROVIDER_UNAVAILABLE
                )
            }
        }

        val fallbackRegistry = WastiExecutionProviderRegistry()
        val routerWithFallback = ExecutionProviderRouter(fallbackRegistry)

        // Register primary (failing) provider
        fallbackRegistry.registerProvider(
            failingProvider,
            ProviderCapabilityAdvertisement(
                providerId = "failing_primary", providerName = "Failing Primary",
                supportedLanguages = listOf("python"), supportedExecutables = listOf("python3"),
                reliabilityRating = 0.50
            )
        )

        // Register backup provider
        fallbackRegistry.registerProvider(
            localProvider,
            ProviderCapabilityAdvertisement(
                providerId = "backup_local", providerName = "Backup Local",
                supportedLanguages = listOf("python", "sh"), supportedExecutables = listOf("python3", "sh", "echo"),
                reliabilityRating = 0.99
            )
        )

        val selected = routerWithFallback.selectProvider(
            ExecutionRequest(executable = "python3", arguments = emptyList(), workingDirectory = ".", language = "python")
        )

        assertNotNull(selected)
        assertTrue("Router must select higher reliability backup provider", selected is LocalAndroidProvider)
    }

    @Test
    fun test15_structuredArgumentValidation_failsOnMissingExecutable() = runBlocking {
        val result = toolRouter.routeAndExecute(
            toolName = "execute_code",
            args = mapOf("workingDirectory" to "."),
            context = defaultTask
        )

        assertFalse(result.isSuccess)
        assertTrue(result.isSecurityBlocked)
        assertTrue(result.error!!.contains("INVALID_ARGUMENTS"))
    }

    @Test
    fun test16_noUnrestrictedCommandStringExecution() {
        val fields = ExecutionRequest::class.java.declaredFields.map { it.name }
        assertFalse("ExecutionRequest must NOT contain generic command string field", fields.contains("command"))
        assertTrue("ExecutionRequest must contain executable", fields.contains("executable"))
        assertTrue("ExecutionRequest must contain arguments", fields.contains("arguments"))
    }

    @Test
    fun test17_noCredentialLogging() = runBlocking {
        val mockHttpClient = object : RemoteSandboxCodeExecutionProvider.RemoteSandboxHttpClient {
            override suspend fun postExecutionRequest(
                endpoint: String,
                apiKey: String?,
                request: ExecutionRequest
            ): RemoteSandboxCodeExecutionProvider.RemoteSandboxResponse {
                return RemoteSandboxCodeExecutionProvider.RemoteSandboxResponse(statusCode = 200, stdout = "Success")
            }
        }

        val remoteProvider = RemoteSandboxCodeExecutionProvider(
            endpointUrlSupplier = { "https://sandbox.wasti.ai/execute" },
            apiKeySupplier = { "SECRET_API_KEY_12345" },
            httpClientAdapter = mockHttpClient
        )

        val result = remoteProvider.execute(
            ExecutionRequest(executable = "python3", arguments = listOf("app.py"), workingDirectory = ".")
        )

        assertFalse("Stdout must not contain API key", result.stdout.contains("SECRET_API_KEY_12345"))
        assertFalse("Stderr must not contain API key", result.stderr.contains("SECRET_API_KEY_12345"))
    }

    @Test
    fun test18_noSecurityPolicyBypass() = runBlocking {
        // SAFE execution mode without privileged biometric approval will trigger security policy requirement
        val safeTask = defaultTask.copy(executionMode = ExecutionMode.SAFE)
        permissionModel.setAutoApproveBiometricForTesting(false)

        val result = toolRouter.routeAndExecute(
            toolName = "execute_code",
            args = mapOf(
                "executable" to "sh",
                "arguments" to listOf("-c", "echo test"),
                "workingDirectory" to "."
            ),
            context = safeTask
        )

        assertFalse("Privileged tool 'execute_code' in SAFE mode without biometric approval must be blocked", result.isSuccess)
    }

    @Test
    fun test19_noToolRouterBypass() = runBlocking {
        val routerTools = toolRouter.resolveTool(defaultTask, "execute_code")
        assertNotNull("ExecuteCodeTool must be registered and routed through ToolRouter", routerTools)
        assertEquals("execute_code", routerTools!!.name)
    }
}
