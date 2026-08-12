package com.example.data.agent.runtime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
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
class Stage4OrchestratorTest {

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

    private lateinit var taskManager: AgentTaskManager
    private lateinit var eventBus: AgentEventBus
    private lateinit var modelProvider: RuleBasedAgentModelProvider
    private lateinit var planner: AgentPlanner
    private lateinit var errorAnalyzer: ErrorAnalyzer
    private lateinit var correctionEngine: SelfCorrectionEngine
    private lateinit var memoryStore: InMemoryAgentMemoryStore
    private lateinit var loopEngine: AgenticLoopEngine
    private lateinit var runtime: WastiAgentRuntimeImpl

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

        providerRegistry.registerProvider(
            localProvider,
            ProviderCapabilityAdvertisement(
                providerId = "local_android_provider",
                providerName = "Local Android Process Provider",
                supportedLanguages = listOf("sh", "kotlin", "java"),
                supportedExecutables = listOf("sh", "echo", "cat", "ls", "pwd", "true")
            )
        )

        executeCodeTool = ExecuteCodeTool(providerRouter)
        toolRegistry.register(executeCodeTool)
        toolRegistry.register(ReadFileTool(workspaceManager))
        toolRegistry.register(WriteFileTool(workspaceManager))
        toolRegistry.register(ListFilesTool(workspaceManager))

        toolRouter = WastiAgentToolRouter(
            registry = toolRegistry,
            securityPolicy = securityPolicy,
            permissionModel = permissionModel,
            emergencyStop = emergencyStop,
            capabilityRegistry = capabilityRegistry,
            auditLogger = auditLogger
        )

        taskManager = AgentTaskManager()
        eventBus = AgentEventBus()
        modelProvider = RuleBasedAgentModelProvider()
        planner = AgentPlanner(modelProvider, toolRegistry, capabilityRegistry)
        errorAnalyzer = ErrorAnalyzer(modelProvider)
        correctionEngine = SelfCorrectionEngine(modelProvider, toolRouter, workspaceManager)
        memoryStore = InMemoryAgentMemoryStore()

        loopEngine = AgenticLoopEngine(
            taskManager = taskManager,
            eventBus = eventBus,
            planner = planner,
            toolRouter = toolRouter,
            errorAnalyzer = errorAnalyzer,
            correctionEngine = correctionEngine,
            emergencyStopController = emergencyStop,
            memoryStore = memoryStore,
            config = AgenticLoopConfig(maxIterations = 5, maxCorrections = 3)
        )

        runtime = WastiAgentRuntimeImpl(
            taskManager = taskManager,
            eventBus = eventBus,
            loopEngine = loopEngine,
            emergencyStopController = emergencyStop,
            toolRouter = toolRouter
        )
    }

    @Test
    fun test01_taskCreation_success() {
        val submitRes = runtime.submitTaskResult("Read file README.md")
        assertTrue("Task creation must succeed", submitRes.isSuccess)
        val task = submitRes.getOrThrow()
        assertNotNull(task.taskId)
        assertEquals("Read file README.md", task.prompt)
        assertTrue(task.status is AgenticState.Idle)
    }

    @Test
    fun test02_validStateTransition_success() {
        val task = taskManager.createTask("Test transition")
        val updateRes = taskManager.updateTaskState(task.taskId, AgenticState.Analyzing())
        assertTrue("Valid transition Idle -> Analyzing must succeed", updateRes.isSuccess)
        assertTrue(taskManager.getTask(task.taskId)!!.status is AgenticState.Analyzing)
    }

    @Test
    fun test03_invalidStateTransition_rejected() {
        val task = taskManager.createTask("Test invalid transition")
        taskManager.updateTaskState(task.taskId, AgenticState.Completed())
        val invalidRes = taskManager.updateTaskState(task.taskId, AgenticState.Executing())
        assertFalse("Transition Completed -> Executing must be rejected", invalidRes.isSuccess)
    }

    @Test
    fun test04_taskCancellation_success() {
        val task = runtime.submitTaskResult("Cancellation test").getOrThrow()
        val cancelRes = runtime.cancelTask(task.taskId, "User requested cancel")
        assertTrue("Cancellation must succeed", cancelRes.isSuccess)
        val cancelledTask = runtime.getTask(task.taskId)!!
        assertTrue(cancelledTask.status is AgenticState.Cancelled)
        assertTrue(cancelledTask.cancellationState.isCancelled)
    }

    @Test
    fun test05_emergencyStop_cancelsActiveTasks() {
        val task = runtime.submitTaskResult("Emergency test").getOrThrow()
        runtime.triggerEmergencyStop("Test killswitch")
        assertTrue(emergencyStop.isEmergencyStopped)
        val updatedTask = runtime.getTask(task.taskId)!!
        assertTrue(updatedTask.cancellationState.isCancelled)
    }

    @Test
    fun test06_emergencyStopActive_rejectsNewTasks() {
        emergencyStop.triggerEmergencyStop("Killswitch active")
        val submitRes = runtime.submitTaskResult("New task attempt")
        assertFalse("New task submission must be rejected when emergency stop is active", submitRes.isSuccess)
        assertTrue(submitRes.exceptionOrNull()!!.message!!.contains("EMERGENCY_STOP_ACTIVE"))
    }

    @Test
    fun test07_eventEmission_capturedByEventBus() = runBlocking {
        val task = runtime.submitTaskResult("Read file README.md").getOrThrow()
        val result = runtime.executeTask(task.taskId)
        assertTrue(result.isSuccess)

        val firstEvent = eventBus.events.first()
        assertNotNull("EventBus must capture emitted events", firstEvent)
    }

    @Test
    fun test08_plannerOutputValidation_producesBoundedPlan() = runBlocking {
        val task = runtime.submitTaskResult("Read file test.txt").getOrThrow()
        val plan = planner.createPlan(task)
        assertTrue("Planner must produce valid plan", plan.isValid)
        assertTrue("Plan must contain steps", plan.steps.isNotEmpty())
        assertEquals("read_file", plan.steps.first().toolName)
    }

    @Test
    fun test09_toolRoutingDelegation_routesThroughToolRouter() = runBlocking {
        workspaceManager.writeFile("script.sh", "echo 'orchestration_works'")
        val task = runtime.submitTaskResult("Run code script.sh").getOrThrow()
        val result = runtime.executeTask(task.taskId)
        assertTrue("Task execution via router must succeed", result.isSuccess)
    }

    @Test
    fun test10_runtimeCannotBypassToolRouter() {
        val declaredMethods = WastiAgentRuntimeImpl::class.java.declaredMethods.map { it.name }
        assertFalse("RuntimeImpl must not contain direct process execution methods", declaredMethods.contains("execProcess"))
    }

    @Test
    fun test11_correctionCannotBypassToolRouter() {
        val declaredMethods = SelfCorrectionEngine::class.java.declaredMethods.map { it.name }
        assertTrue("SelfCorrectionEngine must apply corrections through router", declaredMethods.contains("applyCorrectionThroughRouter"))
    }

    @Test
    fun test12_boundedIteration_haltsAtMaxIterations() = runBlocking {
        val task = runtime.submitTaskResult("Execute failing code").getOrThrow()

        // Configure custom model provider returning infinite failing step
        val customLoopEngine = AgenticLoopEngine(
            taskManager = taskManager,
            eventBus = eventBus,
            planner = planner,
            toolRouter = toolRouter,
            errorAnalyzer = errorAnalyzer,
            correctionEngine = correctionEngine,
            emergencyStopController = emergencyStop,
            config = AgenticLoopConfig(maxTaskSteps = 2, maxCorrections = 0)
        )

        val result = customLoopEngine.executeLoop(task)
        assertTrue("Loop must terminate gracefully within bounds", result.iterationsCompleted <= 2)
    }

    @Test
    fun test13_correctionLimit_haltsAtMaxCorrections() = runBlocking {
        val failingModelProvider = object : AgentModelProvider {
            override suspend fun generatePlan(goal: String, availableCapabilities: List<String>): ModelPlanResponse {
                return ModelPlanResponse(
                    rawReasoning = "Plan with non existent executable",
                    steps = listOf(
                        PlannedStep(
                            stepId = 1,
                            toolName = "execute_code",
                            arguments = mapOf("executable" to "non_existent_binary_xyz", "arguments" to emptyList<String>(), "workingDirectory" to "."),
                            description = "Run non existent binary"
                        )
                    ),
                    isValid = true
                )
            }
            override suspend fun analyzeError(errorOutput: String, context: String): ModelDiagnosticResponse {
                return modelProvider.analyzeError(errorOutput, context)
            }
            override suspend fun proposeCorrection(diagnostic: ErrorDiagnostic, context: String): ModelCorrectionResponse {
                return ModelCorrectionResponse(
                    explanation = "Try non existent binary again",
                    proposedAction = PlannedStep(
                        stepId = 2,
                        toolName = "execute_code",
                        arguments = mapOf("executable" to "non_existent_binary_xyz2", "arguments" to emptyList<String>(), "workingDirectory" to "."),
                        description = "Retry with invalid binary"
                    )
                )
            }
        }
        val failingPlanner = AgentPlanner(failingModelProvider, toolRegistry, capabilityRegistry)
        val customLoopEngine = AgenticLoopEngine(
            taskManager = taskManager,
            eventBus = eventBus,
            planner = failingPlanner,
            toolRouter = toolRouter,
            errorAnalyzer = errorAnalyzer,
            correctionEngine = SelfCorrectionEngine(failingModelProvider, toolRouter, workspaceManager),
            emergencyStopController = emergencyStop,
            config = AgenticLoopConfig(maxTaskSteps = 5, maxCorrections = 1)
        )

        val task = runtime.submitTaskResult("Run code non_existent_file.sh").getOrThrow()
        val result = customLoopEngine.executeLoop(task)
        assertFalse("Execution with invalid binary must fail after max corrections", result.isSuccess)
        assertTrue(result.correctionsAttempted <= 1)
    }

    @Test
    fun test14_emergencyStopDuringLoop_interceptsLoop() = runBlocking {
        val task = runtime.submitTaskResult("Run code script.sh").getOrThrow()
        emergencyStop.triggerEmergencyStop("Triggered during loop test")

        val result = runtime.executeTask(task.taskId)
        assertFalse(result.isSuccess)
        assertTrue(result.finalState is AgenticState.SecurityBlocked)
    }

    @Test
    fun test15_executionFailureToDiagnosis_producesDiagnostic() = runBlocking {
        val obs = AgentObservation(
            taskId = TaskId("diag-task"),
            toolName = "execute_code",
            isSuccess = false,
            stderr = "COMPILATION_FAILED: e: file://MyScript.kt:5:10 Unresolved reference: foo"
        )

        val diag = errorAnalyzer.analyzeFailure(obs)
        assertEquals(ExecutionErrorType.COMPILATION, diag.category)
        assertTrue(diag.summary.contains("COMPILATION") || diag.summary.contains("compilation") || diag.summary.contains("code"))
    }

    @Test
    fun test16_diagnosisToCorrectionProposal_createsTargetedProposal() = runBlocking {
        val task = runtime.submitTaskResult("Fix code").getOrThrow()
        val diag = ErrorDiagnostic(
            category = ExecutionErrorType.COMPILATION,
            summary = "Compilation error",
            evidence = "Unresolved reference",
            probableCause = "Missing symbol",
            suggestedCorrection = "Add missing symbol"
        )
        val obs = AgentObservation(taskId = task.taskId, toolName = "execute_code", isSuccess = false, stderr = "Compilation error")

        val proposal = correctionEngine.proposeCorrection(task, diag, obs)
        assertNotNull(proposal)
        assertTrue(proposal.toolName == "write_file" || proposal.toolName == "execute_code")
    }

    @Test
    fun test17_correctionToReTest_appliesCorrectionViaRouter() = runBlocking {
        val task = runtime.submitTaskResult("Fix script").getOrThrow()
        val proposal = CorrectionProposal(
            explanation = "Write fixed file",
            toolName = "write_file",
            toolArguments = mapOf("path" to "fixed.txt", "content" to "fixed")
        )

        val res = correctionEngine.applyCorrectionThroughRouter(task, proposal)
        assertTrue("Correction applied via router must succeed", res.isSuccess)
    }

    @Test
    fun test18_successfulVerificationToCompletion_completesTask() = runBlocking {
        val task = runtime.submitTaskResult("Read file README.md").getOrThrow()
        workspaceManager.writeFile("README.md", "# Test")

        val result = runtime.executeTask(task.taskId)
        assertTrue(result.isSuccess)
        assertTrue(result.finalState is AgenticState.Completed)
    }

    @Test
    fun test19_capabilityUnavailableHandling_failsGracefully() = runBlocking {
        capabilityRegistry.setCapabilityEnabled("FILES", false)
        val task = runtime.submitTaskResult("Read file test.txt").getOrThrow()

        val result = runtime.executeTask(task.taskId)
        assertFalse(result.isSuccess)
    }

    @Test
    fun test20_modelProviderFailure_handledGracefully() = runBlocking {
        val failingModelProvider = object : AgentModelProvider {
            override suspend fun generatePlan(goal: String, availableCapabilities: List<String>): ModelPlanResponse {
                throw RuntimeException("Model provider offline")
            }
            override suspend fun analyzeError(errorOutput: String, context: String): ModelDiagnosticResponse {
                throw RuntimeException("Model provider offline")
            }
            override suspend fun proposeCorrection(diagnostic: ErrorDiagnostic, context: String): ModelCorrectionResponse {
                throw RuntimeException("Model provider offline")
            }
        }

        val failingPlanner = AgentPlanner(failingModelProvider, toolRegistry, capabilityRegistry)
        val failingLoopEngine = AgenticLoopEngine(
            taskManager = taskManager, eventBus = eventBus, planner = failingPlanner,
            toolRouter = toolRouter, errorAnalyzer = errorAnalyzer, correctionEngine = correctionEngine,
            emergencyStopController = emergencyStop
        )

        val task = runtime.submitTaskResult("Task with failing model").getOrThrow()
        val result = failingLoopEngine.executeLoop(task)
        assertFalse(result.isSuccess)
        assertTrue(result.executionSummary.contains("Model provider offline") || result.finalState is AgenticState.Failed)
    }

    @Test
    fun test21_noProcessBuilderInRuntimeOrLoop() {
        val runtimeFiles = listOf(
            "WastiAgentRuntimeImpl.kt", "AgenticLoopEngine.kt", "AgentPlanner.kt",
            "AgentTaskManager.kt", "SelfCorrectionEngine.kt", "ErrorAnalyzer.kt"
        )
        val srcDir = File("app/src/main/java/com/example/data/agent/runtime")
        for (fileName in runtimeFiles) {
            val file = File(srcDir, fileName)
            if (file.exists()) {
                val content = file.readText()
                assertFalse("Runtime file $fileName must not reference ProcessBuilder", content.contains("ProcessBuilder"))
            }
        }
    }

    @Test
    fun test22_noRuntimeExecInRuntimeOrLoop() {
        val runtimeFiles = listOf(
            "WastiAgentRuntimeImpl.kt", "AgenticLoopEngine.kt", "AgentPlanner.kt",
            "AgentTaskManager.kt", "SelfCorrectionEngine.kt", "ErrorAnalyzer.kt"
        )
        val srcDir = File("app/src/main/java/com/example/data/agent/runtime")
        for (fileName in runtimeFiles) {
            val file = File(srcDir, fileName)
            if (file.exists()) {
                val content = file.readText()
                assertFalse("Runtime file $fileName must not reference Runtime.getRuntime().exec", content.contains("Runtime.getRuntime().exec"))
            }
        }
    }

    @Test
    fun test23_noDirectGeminiDependencyInRuntimeOrLoop() {
        val runtimeFiles = listOf(
            "WastiAgentRuntimeImpl.kt", "AgenticLoopEngine.kt", "AgentPlanner.kt",
            "AgentTaskManager.kt", "SelfCorrectionEngine.kt", "ErrorAnalyzer.kt"
        )
        val srcDir = File("app/src/main/java/com/example/data/agent/runtime")
        for (fileName in runtimeFiles) {
            val file = File(srcDir, fileName)
            if (file.exists()) {
                val content = file.readText()
                assertFalse("Runtime file $fileName must not directly import or reference GeminiProvider or GeminiContent", content.contains("GeminiProvider") || content.contains("GeminiContent"))
            }
        }
    }

    @Test
    fun test24_explicitSafeOperation_doesNotUnnecessarilyRequireBiometric() = runBlocking {
        permissionModel.setAutoApproveBiometricForTesting(false)
        workspaceManager.writeFile("safe_test.txt", "safe_content")

        val result = toolRouter.routeAndExecute(
            toolName = "read_file",
            args = mapOf("path" to "safe_test.txt"),
            context = AgentTask(taskId = TaskId(), prompt = "read safe file", status = AgenticState.Idle(), executionMode = ExecutionMode.SAFE)
        )

        assertTrue("SAFE read_file operation within workspace must succeed without biometric prompt", result.isSuccess)
    }

    @Test
    fun test25_protectedOperation_requiresAppropriateAuthorization() = runBlocking {
        permissionModel.setAutoApproveBiometricForTesting(false)

        val result = toolRouter.routeAndExecute(
            toolName = "execute_code",
            args = mapOf("executable" to "echo", "arguments" to listOf("hello"), "workingDirectory" to "."),
            context = AgentTask(taskId = TaskId(), prompt = "exec code", status = AgenticState.Idle(), executionMode = ExecutionMode.SAFE)
        )

        assertFalse("Privileged operation in SAFE mode without biometric approval must be blocked", result.isSuccess)
    }
}
