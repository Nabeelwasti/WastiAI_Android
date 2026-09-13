package com.example.data.agent.runtime

import android.content.Context
import com.example.WastiApplication
import com.example.data.device.WastiDeviceController
import com.example.data.memory.MemoryManager
import com.example.data.memory.model.MemorySearchQuery
import com.example.data.ops.WebSearchEngine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class UnifiedExecutionStatus {
    PLANNED,
    AUTHORIZED,
    RUNNING,
    WAITING,
    COMPLETED,
    VERIFIED,
    FAILED,
    CANCELLED,
    UNAVAILABLE,
    AUTHENTICATION_REQUIRED,
    NOT_IMPLEMENTED,
    VERIFICATION_FAILED,
    VERIFICATION_UNAVAILABLE,
    DISPATCHED,
    EXECUTOR_COMPLETED,
    OBSERVED,
    UNKNOWN,
    AMBIGUOUS
}

enum class UnifiedVerificationStatus {
    VERIFIED,
    UNVERIFIED,
    FAILED,
    NOT_APPLICABLE,
    VERIFICATION_UNAVAILABLE
}

data class UnifiedExecutionRequest(
    val taskId: String = UUID.randomUUID().toString(),
    val actionId: String = UUID.randomUUID().toString(),
    val capabilityId: String,
    val parameters: Map<String, Any> = emptyMap(),
    val context: ContextSnapshot? = null,
    val requestedStrategy: ExecutionStrategy = ExecutionStrategy.NATIVE,
    val authorizationState: ActionAuthorizationState = ActionAuthorizationState.AUTHORIZED,
    val riskLevel: RiskLevel = RiskLevel.MEDIUM,
    val dryRun: Boolean = false,
    val timeoutMs: Long = 30000L,
    val originatingNodeId: String? = null
)

data class UnifiedExecutionResult(
    val taskId: String,
    val actionId: String,
    val capabilityId: String,
    val status: UnifiedExecutionStatus,
    val output: String,
    val error: String? = null,
    val executor: String,
    val providerOrModel: String? = null,
    val startedAt: Long = System.currentTimeMillis(),
    val completedAt: Long = System.currentTimeMillis(),
    val verificationStatus: UnifiedVerificationStatus = UnifiedVerificationStatus.UNVERIFIED,
    val verificationEvidence: String? = null,
    val exitCode: Int? = null,
    val details: Map<String, String> = emptyMap()
)

interface UnifiedExecutor {
    val name: String
    val supportedCapabilities: List<String>
    suspend fun execute(request: UnifiedExecutionRequest, context: Context?): UnifiedExecutionResult
}

/**
 * UnifiedExecutionFabric: Single authoritative execution fabric for all Wasti actions.
 * Every consequential action must pass through this execution pipeline.
 */
class UnifiedExecutionFabric(
    val realityRegistry: CapabilityRealityRegistry = CapabilityRealityRegistry(),
    val eventBus: AgentEventBus? = null,
    val auditEngine: RealityAuditEngine? = null,
    val securityPolicyEngine: WastiSecurityPolicyEngine? = null,
    observationEngine: WastiObservationEngine? = null,
    verificationEngine: WastiVerificationEngine? = null,
    val appContext: Context? = null
) {
    val observationEngine: WastiObservationEngine = observationEngine ?: WastiObservationEngine(realityRegistry = realityRegistry, appContext = appContext)
    val verificationEngine: WastiVerificationEngine = verificationEngine ?: WastiVerificationEngine()
    private val customExecutors = ConcurrentHashMap<String, UnifiedExecutor>()
    private val activeExecutionHashes = ConcurrentHashMap.newKeySet<String>()
    private val _telemetryStream = MutableStateFlow<List<UnifiedExecutionResult>>(emptyList())
    val telemetryStream: StateFlow<List<UnifiedExecutionResult>> = _telemetryStream.asStateFlow()

    private fun normalizedCapabilityId(capabilityId: String): String =
        capabilityId.trim().lowercase(Locale.ROOT)

    companion object {
        @Volatile
        private var defaultInstance: UnifiedExecutionFabric? = null

        val instance: UnifiedExecutionFabric
            get() = getInstance()

        /**
         * The first supplied application context becomes the process-wide default.
         * Callers may still pass an explicit context to execute() for a specific action.
         */
        fun getInstance(context: Context? = null): UnifiedExecutionFabric =
            defaultInstance ?: synchronized(this) {
                defaultInstance ?: UnifiedExecutionFabric(appContext = context)
                    .also { defaultInstance = it }
            }
    }

    fun registerExecutor(executor: UnifiedExecutor) {
        for (capability in executor.supportedCapabilities) {
            customExecutors[normalizedCapabilityId(capability)] = executor
        }
    }

    suspend fun execute(
        request: UnifiedExecutionRequest,
        context: Context? = null
    ): UnifiedExecutionResult {
        val startedAt = System.currentTimeMillis()
        val taskId = TaskId(request.taskId)
        val ctx = context ?: appContext ?: WastiApplication.instance

        if (com.example.data.di.WastiServiceLocator.emergencyStopController.isEmergencyStopped) {
            val stopReason = com.example.data.di.WastiServiceLocator.emergencyStopController.getReason() ?: "Emergency stop active"
            eventBus?.emit(AgentEvent.TaskCancelled(taskId, stopReason))
            return UnifiedExecutionResult(
                taskId = request.taskId,
                actionId = request.actionId,
                capabilityId = request.capabilityId,
                status = UnifiedExecutionStatus.CANCELLED,
                output = "Execution rejected: Emergency stop is active ($stopReason)",
                error = "EMERGENCY_STOP_ACTIVE: $stopReason",
                executor = "UnifiedExecutionFabric",
                startedAt = startedAt,
                completedAt = System.currentTimeMillis(),
                verificationStatus = UnifiedVerificationStatus.FAILED,
                verificationEvidence = "Execution blocked by active emergency stop latch"
            )
        }

        if (request.timeoutMs <= 0L) {
            return createResult(
                request = request,
                status = UnifiedExecutionStatus.FAILED,
                output = "Execution timeout must be greater than zero.",
                error = "Invalid timeoutMs: ${request.timeoutMs}",
                executor = "UnifiedExecutionFabric",
                startedAt = startedAt,
                verificationStatus = UnifiedVerificationStatus.NOT_APPLICABLE
            )
        }

        // This protects only the same in-flight task/action/capability identity.
        // Separate actions and tasks can run concurrently.
        val reqHash = "${request.taskId}:${request.actionId}:${normalizedCapabilityId(request.capabilityId)}"
        if (!activeExecutionHashes.add(reqHash)) {
            val completedAt = System.currentTimeMillis()
            eventBus?.emit(AgentEvent.TaskFailed(taskId, "Duplicate execution request rejected"))
            return UnifiedExecutionResult(
                taskId = request.taskId,
                actionId = request.actionId,
                capabilityId = request.capabilityId,
                status = UnifiedExecutionStatus.FAILED,
                output = "Error: Duplicate execution request detected.",
                error = "Duplicate execution request for hash $reqHash",
                executor = "UnifiedExecutionFabric",
                startedAt = startedAt,
                completedAt = completedAt,
                verificationStatus = UnifiedVerificationStatus.FAILED,
                verificationEvidence = "Duplicate request blocked"
            )
        }

        try {
            // 2. Capability Reality Check
            val reality = realityRegistry.getCapabilityReality(request.capabilityId)
            when (reality.realityState) {
                CapabilityRealityState.UNAVAILABLE -> {
                    eventBus?.emit(AgentEvent.CapabilityUnavailable(taskId, request.capabilityId, "Capability reality state is UNAVAILABLE"))
                    return createResult(
                        request = request,
                        status = UnifiedExecutionStatus.UNAVAILABLE,
                        output = "Capability '${request.capabilityId}' is currently unavailable on this device/environment.",
                        error = "Capability '${request.capabilityId}' state: UNAVAILABLE",
                        executor = "CapabilityRealityRegistry",
                        startedAt = startedAt,
                        verificationStatus = UnifiedVerificationStatus.NOT_APPLICABLE
                    )
                }
                CapabilityRealityState.AUTHENTICATION_REQUIRED -> {
                    eventBus?.emit(AgentEvent.Authenticating(taskId, request.capabilityId))
                    return createResult(
                        request = request,
                        status = UnifiedExecutionStatus.AUTHENTICATION_REQUIRED,
                        output = "Authentication required to execute '${request.capabilityId}'.",
                        error = "Capability '${request.capabilityId}' state: AUTHENTICATION_REQUIRED",
                        executor = "CapabilityRealityRegistry",
                        startedAt = startedAt,
                        verificationStatus = UnifiedVerificationStatus.NOT_APPLICABLE
                    )
                }
                CapabilityRealityState.CONTRACT_ONLY, CapabilityRealityState.PLACEHOLDER -> {
                    return createResult(
                        request = request,
                        status = UnifiedExecutionStatus.NOT_IMPLEMENTED,
                        output = "Capability '${request.capabilityId}' is contract-only or placeholder and cannot be executed live.",
                        error = "Capability '${request.capabilityId}' state: ${reality.realityState}",
                        executor = "CapabilityRealityRegistry",
                        startedAt = startedAt,
                        verificationStatus = UnifiedVerificationStatus.NOT_APPLICABLE
                    )
                }
                CapabilityRealityState.FAILED -> {
                    return createResult(
                        request = request,
                        status = UnifiedExecutionStatus.FAILED,
                        output = "Capability '${request.capabilityId}' is currently in a failed state.",
                        error = "Capability reality state: FAILED",
                        executor = "CapabilityRealityRegistry",
                        startedAt = startedAt,
                        verificationStatus = UnifiedVerificationStatus.UNVERIFIED
                    )
                }
                CapabilityRealityState.QUOTA_EXHAUSTED -> {
                    return createResult(
                        request = request,
                        status = UnifiedExecutionStatus.WAITING,
                        output = "Capability '${request.capabilityId}' is temporarily unavailable because its quota is exhausted.",
                        error = "Capability reality state: QUOTA_EXHAUSTED",
                        executor = "CapabilityRealityRegistry",
                        startedAt = startedAt,
                        verificationStatus = UnifiedVerificationStatus.UNVERIFIED
                    )
                }
                else -> { /* proceed */ }
            }

            if (
                reality.authenticationStatus == CapabilityAuthStatus.REQUIRED_NOT_PROVIDED ||
                reality.authenticationStatus == CapabilityAuthStatus.EXPIRED
            ) {
                eventBus?.emit(AgentEvent.Authenticating(taskId, request.capabilityId))
                return createResult(
                    request = request,
                    status = UnifiedExecutionStatus.AUTHENTICATION_REQUIRED,
                    output = "Authentication is required to execute '${request.capabilityId}'.",
                    error = "Capability authentication state: ${reality.authenticationStatus}",
                    executor = "CapabilityRealityRegistry",
                    startedAt = startedAt,
                    verificationStatus = UnifiedVerificationStatus.NOT_APPLICABLE
                )
            }

            if (reality.executionStatus == CapabilityExecutionStatus.UNAVAILABLE) {
                eventBus?.emit(AgentEvent.CapabilityUnavailable(taskId, request.capabilityId, "Capability execution is unavailable"))
                return createResult(
                    request = request,
                    status = UnifiedExecutionStatus.UNAVAILABLE,
                    output = "Capability '${request.capabilityId}' cannot execute in the current environment.",
                    error = "Capability execution status: UNAVAILABLE",
                    executor = "CapabilityRealityRegistry",
                    startedAt = startedAt,
                    verificationStatus = UnifiedVerificationStatus.NOT_APPLICABLE
                )
            }

            if (reality.executionStatus == CapabilityExecutionStatus.BLOCKED_BY_POLICY) {
                eventBus?.emit(AgentEvent.SecurityBlocked(taskId, "Blocked by security policy"))
                return createResult(
                    request = request,
                    status = UnifiedExecutionStatus.FAILED,
                    output = "Execution of '${request.capabilityId}' blocked by policy.",
                    error = "Capability '${request.capabilityId}' execution status: BLOCKED_BY_POLICY",
                    executor = "WastiSecurityPolicyEngine",
                    startedAt = startedAt,
                    verificationStatus = UnifiedVerificationStatus.FAILED
                )
            }

            // 3. Authorization Policy Check
            when (request.authorizationState) {
                ActionAuthorizationState.CANCELLED -> {
                    eventBus?.emit(AgentEvent.TaskCancelled(taskId, "Cancelled by user or authorization policy"))
                    return createResult(
                        request = request,
                        status = UnifiedExecutionStatus.CANCELLED,
                        output = "Action '${request.actionId}' cancelled.",
                        error = "Action authorization state: CANCELLED",
                        executor = "ActionIntentSystem",
                        startedAt = startedAt,
                        verificationStatus = UnifiedVerificationStatus.NOT_APPLICABLE
                    )
                }
                ActionAuthorizationState.REQUIRES_CONFIRMATION -> {
                    eventBus?.emit(AgentEvent.WaitingForUser(taskId, "Awaiting user confirmation"))
                    return createResult(
                        request = request,
                        status = UnifiedExecutionStatus.WAITING,
                        output = "Action '${request.actionId}' requires explicit user confirmation.",
                        error = "Action authorization state: REQUIRES_CONFIRMATION",
                        executor = "ActionIntentSystem",
                        startedAt = startedAt,
                        verificationStatus = UnifiedVerificationStatus.UNVERIFIED
                    )
                }
                else -> { /* Authorized */ }
            }

            // 4. Dry Run Preview
            if (request.dryRun) {
                return createResult(
                    request = request,
                    status = UnifiedExecutionStatus.PLANNED,
                    output = "Dry run preview: Action '${request.actionId}' on '${request.capabilityId}' with params ${request.parameters}.",
                    executor = "UnifiedExecutionFabric",
                    startedAt = startedAt,
                    verificationStatus = UnifiedVerificationStatus.NOT_APPLICABLE,
                    verificationEvidence = "Dry-run execution preview"
                )
            }

            // 5. Real Execution Dispatch
            eventBus?.emit(AgentEvent.ToolStarted(taskId, request.capabilityId))

            val execResult = try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    kotlinx.coroutines.withTimeout(request.timeoutMs) {
                        // Autonomous Hardware Swarm Offloader: Offload heavy tasks or when mobile host is constrained
                        if (request.requestedStrategy != ExecutionStrategy.LOCAL_FALLBACK) {
                            val offloadDecision = com.example.data.node.AutonomousHardwareOffloader.evaluateOffload(ctx, request)
                            if (offloadDecision.shouldOffload && offloadDecision.targetNode != null) {
                                return@withTimeout com.example.data.node.AutonomousHardwareOffloader.executeWithOffload(
                                    request = request,
                                    targetNode = offloadDecision.targetNode,
                                    context = ctx
                                )
                            }
                        }

                        val customExec = customExecutors[normalizedCapabilityId(request.capabilityId)]
                        if (customExec != null) {
                            customExec.execute(request, ctx)
                        } else {
                            executeBuiltIn(request, ctx)
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                createResult(
                    request = request,
                    status = UnifiedExecutionStatus.FAILED,
                    output = "Execution timed out after ${request.timeoutMs} ms.",
                    error = "Execution timeout: ${e.message}",
                    executor = "UnifiedExecutionFabric",
                    startedAt = startedAt,
                    verificationStatus = UnifiedVerificationStatus.FAILED,
                    verificationEvidence = "Timeout exceeded"
                )
            } catch (e: Exception) {
                createResult(
                    request = request,
                    status = UnifiedExecutionStatus.FAILED,
                    output = "Execution failed with exception: ${e.message}",
                    error = e.stackTraceToString(),
                    executor = "UnifiedExecutionFabric",
                    startedAt = startedAt,
                    verificationStatus = UnifiedVerificationStatus.FAILED,
                    verificationEvidence = "Exception thrown during execution"
                )
            }

            // 6. Observation Phase
            val obsRequest = ObservationRequest(
                taskId = request.taskId,
                actionId = request.actionId,
                capabilityId = request.capabilityId,
                expectedOutcome = request.parameters["expectedOutcome"]?.toString() ?: "",
                observationStrategy = ObservationStrategy.SCREEN_SCRAPE,
                timeoutMs = request.timeoutMs
            )
            val obsResult = observationEngine.observe(obsRequest, ctx, execResult)
            eventBus?.emit(AgentEvent.ObservationReceived(taskId, obsResult.evidence))

            // 7. Verification Phase
            val verRequest = VerificationRequest(
                taskId = request.taskId,
                actionId = request.actionId,
                capabilityId = request.capabilityId,
                expectedOutcome = obsRequest.expectedOutcome,
                executionResult = execResult,
                observationResult = obsResult
            )
            val verResult = verificationEngine.verify(verRequest)

            // 8. Final Result Assembly with Truthful Status Mapping
            val finalStatus = when (verResult.status) {
                ActionVerificationStatus.VERIFIED -> {
                    if (execResult.status == UnifiedExecutionStatus.COMPLETED) {
                        if (customExecutors.containsKey(normalizedCapabilityId(request.capabilityId))) {
                            UnifiedExecutionStatus.COMPLETED
                        } else {
                            UnifiedExecutionStatus.VERIFIED
                        }
                    } else {
                        execResult.status
                    }
                }
                ActionVerificationStatus.FAILED -> {
                    if (execResult.status == UnifiedExecutionStatus.COMPLETED || execResult.status == UnifiedExecutionStatus.VERIFIED) {
                        UnifiedExecutionStatus.VERIFICATION_FAILED
                    } else {
                        execResult.status
                    }
                }
                ActionVerificationStatus.VERIFICATION_UNAVAILABLE, ActionVerificationStatus.NOT_VERIFIABLE -> {
                    execResult.status
                }
                ActionVerificationStatus.UNKNOWN -> execResult.status
            }

            val finalVerStatus = when (verResult.status) {
                ActionVerificationStatus.VERIFIED -> UnifiedVerificationStatus.VERIFIED
                ActionVerificationStatus.FAILED -> UnifiedVerificationStatus.FAILED
                ActionVerificationStatus.VERIFICATION_UNAVAILABLE, ActionVerificationStatus.NOT_VERIFIABLE -> UnifiedVerificationStatus.VERIFICATION_UNAVAILABLE
                ActionVerificationStatus.UNKNOWN -> UnifiedVerificationStatus.UNVERIFIED
            }

            val finalResult = execResult.copy(
                status = finalStatus,
                verificationStatus = finalVerStatus,
                verificationEvidence = verResult.evidence,
                error = execResult.error ?: verResult.failureReason
            )

            emitEventAndAudit(request, finalResult)
            return finalResult
        } finally {
            activeExecutionHashes.remove(reqHash)
        }
    }

    private suspend fun executeBuiltIn(
        request: UnifiedExecutionRequest,
        context: Context?
    ): UnifiedExecutionResult {
        val startedAt = System.currentTimeMillis()
        val ctx = context ?: appContext ?: WastiApplication.instance
        val capId = normalizedCapabilityId(request.capabilityId)
        val action = request.parameters["action"]?.toString()
            ?.lowercase(Locale.ROOT)
            ?.trim()
            ?: capId

        // Dispatch guard: Terminal tools FIRST, before ToolRegistry check to prevent recursive routing
        val isTerminalTool = capId.startsWith("wre_tool_") || capId in listOf(
            "terminal", "execute_code", "execute_command", "run_script", "sh", "cmd",
            "bash", "python", "python3", "python_runtime", "node", "nodejs",
            "node_runtime", "javascript", "npm"
        )

        return when {
            capId == "device_control" || capId.startsWith("device_") ||
                capId in listOf(
                    "open_app", "send_whatsapp", "whatsapp", "send_email", "email",
                    "send_sms", "sms", "read_screen", "readscreen", "simulate_tap",
                    "tap", "click_element"
                ) -> executeDeviceControl(request, action, ctx, startedAt)

            capId == "memory_search" || capId == "memory" ->
                executeMemorySearch(request, startedAt)

            capId in listOf("system_info", "system", "inspect_environment", "environment", "status") ->
                executeSystemInfo(request, ctx, startedAt)

            capId in listOf("search_web", "read_web_page", "b2b_xray_search") ->
                executeWebOperations(request, if (action in setOf("search_web", "read_web_page", "b2b_xray_search")) action else capId, ctx, startedAt)

            capId in listOf("files", "read_file", "write_file", "list_files", "delete_file") ->
                executeFileOperations(request, capId, ctx, startedAt)

            capId in listOf(
                "project_dev_manager", "create_project", "create_managed_project",
                "inspect_project", "list_projects", "delete_project", "scan_languages",
                "get_language_profile", "dev_environment", "project"
            ) -> executeProjectOperations(request, ctx, startedAt)

            capId in listOf("build_project", "build", "compile_project", "compile", "build_manager") ->
                executeBuildOperations(request, ctx, startedAt)

            capId in listOf("test_project", "test", "run_tests", "test_runner") ->
                executeTestOperations(request, ctx, startedAt)

            capId in listOf("debug_project", "debug", "analyze_diagnostics", "debug_diagnostics") ->
                executeDiagnosticOperations(request, ctx, startedAt)

            capId in listOf("package_manager", "resolve_package", "install_package") ->
                executePackageOperations(request, ctx, startedAt)

            capId in listOf("wasti_sandbox", "sandbox") ->
                executeSandboxOperations(request, ctx, startedAt)

            capId in listOf("wasm", "wasm_sandbox", "wasm_runtime") ||
                (capId in listOf("terminal", "execute_code") && request.parameters["language"]?.toString()?.lowercase() == "wasm") ->
                executeWasmSandboxOperations(request, startedAt)

            capId in listOf("local_server", "start_server", "stop_server", "server_status", "server") ->
                executeLocalServerOperations(request, ctx, startedAt)

            capId in listOf("navigate_to", "open_screen", "navigate") ->
                executeNavigationOperations(request, startedAt)

            capId in listOf("python_bridge", "termux_bridge") ->
                executeBridgeOperations(request, capId, ctx, startedAt)

            // CRITICAL: Terminal/WRE dispatch MUST come BEFORE ToolRegistry to prevent recursive routing
            isTerminalTool -> executeTerminalOperations(request, capId, ctx, startedAt)

            // ToolRegistry check: guarded to prevent terminal tools from recursing
            !isTerminalTool && (com.example.data.tool.ToolRegistry.getTool(request.capabilityId) != null ||
            com.example.data.tool.ToolRegistry.getTool(capId) != null) ->
                executeToolRegistryOperation(request, startedAt)

            capId in listOf(
                "local_neural_inference", "local_neural", "local_ai", "neural_inference",
                "local_model", "local_model_inference", "wasti_smollm"
            ) -> executeLocalNeuralInference(request, ctx, startedAt)

            else -> {
                createResult(
                    request = request,
                    status = UnifiedExecutionStatus.NOT_IMPLEMENTED,
                    output = "Execution path for capability '${request.capabilityId}' is not implemented.",
                    error = "No real executor registered for capability '${request.capabilityId}'",
                    executor = "UnifiedExecutionFabric",
                    startedAt = startedAt,
                    verificationStatus = UnifiedVerificationStatus.NOT_APPLICABLE
                )
            }
        }
    }

    private fun executeLocalServerOperations(
        request: UnifiedExecutionRequest,
        context: Context?,
        startedAt: Long
    ): UnifiedExecutionResult {
        val serverManager = com.example.data.server.WastiLocalServerManager.getInstance(context)
        val action = request.parameters["action"]?.toString() ?: "status"
        val port = (request.parameters["port"] as? Number)?.toInt() ?: 8080

        return when (action.lowercase(Locale.ROOT)) {
            "start", "start_server" -> {
                val res = serverManager.startServer(port)
                if (res.isSuccess) {
                    val info = res.getOrNull()!!
                    createResult(
                        request = request,
                        status = UnifiedExecutionStatus.VERIFIED,
                        output = "Local server started on port ${info.port} (${info.host})",
                        executor = "WastiLocalServerManager",
                        startedAt = startedAt,
                        verificationStatus = UnifiedVerificationStatus.VERIFIED,
                        verificationEvidence = "Server bound to http://${info.host}:${info.port}"
                    )
                } else {
                    createResult(
                        request = request,
                        status = UnifiedExecutionStatus.FAILED,
                        output = "Failed to start local server: ${res.exceptionOrNull()?.message}",
                        error = res.exceptionOrNull()?.message,
                        executor = "WastiLocalServerManager",
                        startedAt = startedAt,
                        verificationStatus = UnifiedVerificationStatus.FAILED
                    )
                }
            }
            "stop", "stop_server" -> {
                val res = serverManager.stopServer("Requested via UnifiedExecutionFabric")
                if (res.isSuccess) {
                    createResult(
                        request = request,
                        status = UnifiedExecutionStatus.COMPLETED,
                        output = "Local server stopped successfully",
                        executor = "WastiLocalServerManager",
                        startedAt = startedAt,
                        verificationStatus = UnifiedVerificationStatus.VERIFIED,
                        verificationEvidence = "Server port released"
                    )
                } else {
                    createResult(
                        request = request,
                        status = UnifiedExecutionStatus.FAILED,
                        output = "Failed to stop server: ${res.exceptionOrNull()?.message}",
                        error = res.exceptionOrNull()?.message,
                        executor = "WastiLocalServerManager",
                        startedAt = startedAt,
                        verificationStatus = UnifiedVerificationStatus.FAILED
                    )
                }
            }
            else -> {
                val info = serverManager.serverInfo.value
                createResult(
                    request = request,
                    status = UnifiedExecutionStatus.VERIFIED,
                    output = "Local Server Status: state=${info.state}, port=${info.port}, requests=${info.requestsHandled}",
                    executor = "WastiLocalServerManager",
                    startedAt = startedAt,
                    verificationStatus = UnifiedVerificationStatus.VERIFIED,
                    verificationEvidence = "State: ${info.state}"
                )
            }
        }
    }

    private fun executeNavigationOperations(
        request: UnifiedExecutionRequest,
        startedAt: Long
    ): UnifiedExecutionResult {
        val destination = request.parameters["destination"]?.toString()
            ?: request.parameters["screen"]?.toString()
            ?: request.parameters["tab"]?.toString()
            ?: "dashboard"

        com.example.data.action.WastiAppActionBus.tryDispatch(
            com.example.data.action.WastiAppAction.NavigateTo(destination)
        )

        return createResult(
            request = request,
            status = UnifiedExecutionStatus.VERIFIED,
            output = "Navigated to destination screen: $destination",
            executor = "WastiAppActionBus",
            startedAt = startedAt,
            verificationStatus = UnifiedVerificationStatus.VERIFIED,
            verificationEvidence = "Dispatched NavigateTo($destination) to WastiAppActionBus"
        )
    }

    private suspend fun executeBridgeOperations(
        request: UnifiedExecutionRequest,
        capId: String,
        context: Context?,
        startedAt: Long
    ): UnifiedExecutionResult {
        val bridgeManager = com.example.data.bridge.WastiNativeBridgeManager.getInstance(context)
        val script = request.parameters["script"]?.toString() ?: request.parameters["code"]?.toString() ?: ""
        val command = request.parameters["command"]?.toString() ?: "echo 'TEST'"

        return if (capId == "python_bridge" || capId == "execute_python") {
            val res = bridgeManager.executePythonScript(script)
            createResult(
                request = request,
                status = if (res.isSuccess) UnifiedExecutionStatus.VERIFIED else UnifiedExecutionStatus.FAILED,
                output = res.stdout.ifBlank { res.stderr },
                error = if (res.isSuccess) null else res.stderr,
                executor = "WastiNativeBridgeManager:Python",
                startedAt = startedAt,
                verificationStatus = if (res.isSuccess) UnifiedVerificationStatus.VERIFIED else UnifiedVerificationStatus.FAILED,
                verificationEvidence = res.verificationEvidence ?: "Exit code: ${res.exitCode}"
            )
        } else {
            val res = bridgeManager.executeTermuxCommand(command)
            createResult(
                request = request,
                status = if (res.isSuccess) UnifiedExecutionStatus.VERIFIED else UnifiedExecutionStatus.FAILED,
                output = res.stdout.ifBlank { res.stderr },
                error = if (res.isSuccess) null else res.stderr,
                executor = "WastiNativeBridgeManager:Termux",
                startedAt = startedAt,
                verificationStatus = if (res.isSuccess) UnifiedVerificationStatus.VERIFIED else UnifiedVerificationStatus.FAILED,
                verificationEvidence = res.verificationEvidence ?: "Exit code: ${res.exitCode}"
            )
        }
    }

    private fun executeDeviceControl(
        request: UnifiedExecutionRequest,
        action: String,
        context: Context?,
        startedAt: Long
    ): UnifiedExecutionResult {
        val params = request.parameters
        val target = params["target"]?.toString() ?: params["recipient"]?.toString() ?: ""
        val content = params["content"]?.toString() ?: params["message"]?.toString() ?: params["body"]?.toString() ?: ""
        val subject = params["subject"]?.toString() ?: ""

        val ctx = context ?: WastiApplication.instance

        when (action) {
            "open_app", "openapp" -> {
                if (target.isBlank()) {
                    return createResult(
                        request = request,
                        status = UnifiedExecutionStatus.FAILED,
                        output = "Error: Target app name or package required.",
                        error = "Missing target parameter for open_app",
                        executor = "WastiDeviceController",
                        startedAt = startedAt,
                        verificationStatus = UnifiedVerificationStatus.FAILED
                    )
                }
                if (ctx == null) {
                    return createResult(
                        request = request,
                        status = UnifiedExecutionStatus.UNAVAILABLE,
                        output = "Context unavailable on device runtime.",
                        error = "Null Context in openApp",
                        executor = "WastiDeviceController",
                        startedAt = startedAt,
                        verificationStatus = UnifiedVerificationStatus.FAILED
                    )
                }
                val res = WastiDeviceController.openApp(ctx, target)
                val vStatus: UnifiedVerificationStatus = if (res.success) UnifiedVerificationStatus.VERIFIED else UnifiedVerificationStatus.FAILED
                return createResult(
                    request = request,
                    status = if (res.success) UnifiedExecutionStatus.COMPLETED else UnifiedExecutionStatus.FAILED,
                    output = res.userFeedback,
                    error = if (res.success) null else res.userFeedback,
                    executor = "WastiDeviceController",
                    startedAt = startedAt,
                    verificationStatus = vStatus,
                    verificationEvidence = "Intent launch result: ${res.actionType}"
                )
            }
            "send_whatsapp", "whatsapp" -> {
                if (target.isBlank()) {
                    return createResult(
                        request = request,
                        status = UnifiedExecutionStatus.FAILED,
                        output = "Error: WhatsApp recipient phone number required.",
                        error = "Missing recipient for send_whatsapp",
                        executor = "WastiDeviceController",
                        startedAt = startedAt,
                        verificationStatus = UnifiedVerificationStatus.FAILED
                    )
                }
                if (ctx == null) {
                    return createResult(
                        request = request,
                        status = UnifiedExecutionStatus.UNAVAILABLE,
                        output = "Context unavailable for WhatsApp execution.",
                        error = "Null Context in sendWhatsAppMessage",
                        executor = "WastiDeviceController",
                        startedAt = startedAt,
                        verificationStatus = UnifiedVerificationStatus.FAILED
                    )
                }
                val res = WastiDeviceController.sendWhatsAppMessage(ctx, target, content)
                val waStatus: UnifiedVerificationStatus = if (res.success) UnifiedVerificationStatus.VERIFICATION_UNAVAILABLE else UnifiedVerificationStatus.FAILED
                return createResult(
                    request = request,
                    status = if (res.success) UnifiedExecutionStatus.COMPLETED else UnifiedExecutionStatus.FAILED,
                    output = res.userFeedback,
                    error = if (res.success) null else res.userFeedback,
                    executor = "WastiDeviceController",
                    startedAt = startedAt,
                    verificationStatus = waStatus,
                    verificationEvidence = "WhatsApp intent dispatched (external sandbox verification unavailable)"
                )
            }
            "send_email", "email" -> {
                if (target.isBlank()) {
                    return createResult(
                        request = request,
                        status = UnifiedExecutionStatus.FAILED,
                        output = "Error: Email recipient required.",
                        error = "Missing recipient for send_email",
                        executor = "WastiDeviceController",
                        startedAt = startedAt,
                        verificationStatus = UnifiedVerificationStatus.FAILED
                    )
                }
                if (ctx == null) {
                    return createResult(
                        request = request,
                        status = UnifiedExecutionStatus.UNAVAILABLE,
                        output = "Context unavailable for Email execution.",
                        error = "Null Context in sendEmail",
                        executor = "WastiDeviceController",
                        startedAt = startedAt,
                        verificationStatus = UnifiedVerificationStatus.FAILED
                    )
                }
                val res = WastiDeviceController.sendEmail(ctx, target, subject, content)
                val emailStatus: UnifiedVerificationStatus = if (res.success) UnifiedVerificationStatus.VERIFIED else UnifiedVerificationStatus.FAILED
                return createResult(
                    request = request,
                    status = if (res.success) UnifiedExecutionStatus.COMPLETED else UnifiedExecutionStatus.FAILED,
                    output = res.userFeedback,
                    error = if (res.success) null else res.userFeedback,
                    executor = "WastiDeviceController",
                    startedAt = startedAt,
                    verificationStatus = emailStatus,
                    verificationEvidence = "Email intent dispatched"
                )
            }
            "send_sms", "sms" -> {
                if (target.isBlank()) {
                    return createResult(
                        request = request,
                        status = UnifiedExecutionStatus.FAILED,
                        output = "Error: SMS recipient required.",
                        error = "Missing recipient for send_sms",
                        executor = "WastiDeviceController",
                        startedAt = startedAt,
                        verificationStatus = UnifiedVerificationStatus.FAILED
                    )
                }
                if (ctx == null) {
                    return createResult(
                        request = request,
                        status = UnifiedExecutionStatus.UNAVAILABLE,
                        output = "Context unavailable for SMS execution.",
                        error = "Null Context in sendSMS",
                        executor = "WastiDeviceController",
                        startedAt = startedAt,
                        verificationStatus = UnifiedVerificationStatus.FAILED
                    )
                }
                val res = WastiDeviceController.sendSMS(ctx, target, content)
                val smsStatus: UnifiedVerificationStatus = if (res.success) UnifiedVerificationStatus.VERIFIED else UnifiedVerificationStatus.FAILED
                return createResult(
                    request = request,
                    status = if (res.success) UnifiedExecutionStatus.COMPLETED else UnifiedExecutionStatus.FAILED,
                    output = res.userFeedback,
                    error = if (res.success) null else res.userFeedback,
                    executor = "WastiDeviceController",
                    startedAt = startedAt,
                    verificationStatus = smsStatus,
                    verificationEvidence = "SMS intent dispatched"
                )
            }
            "read_screen", "readscreen" -> {
                val resText = WastiDeviceController.readScreenContent(ctx)
                val isInactive = resText.contains("Accessibility Service Inactive") || resText.isBlank()
                return createResult(
                    request = request,
                    status = if (isInactive) UnifiedExecutionStatus.UNAVAILABLE else UnifiedExecutionStatus.VERIFIED,
                    output = resText,
                    error = if (isInactive) "Wasti Accessibility Service is inactive" else null,
                    executor = "WastiDeviceController",
                    startedAt = startedAt,
                    verificationStatus = if (isInactive) UnifiedVerificationStatus.UNVERIFIED else UnifiedVerificationStatus.VERIFIED,
                    verificationEvidence = if (isInactive) "Accessibility Service inactive" else "Screen node layout scraped"
                )
            }
            "simulate_tap", "tap", "click_element" -> {
                val elementId = params["targetElement"]?.toString() ?: target
                if (elementId.isBlank()) {
                    return createResult(
                        request = request,
                        status = UnifiedExecutionStatus.FAILED,
                        output = "Error: Target element required for simulate_tap.",
                        error = "Missing targetElement parameter",
                        executor = "WastiDeviceController",
                        startedAt = startedAt,
                        verificationStatus = UnifiedVerificationStatus.FAILED
                    )
                }
                val res = WastiDeviceController.simulateTap(ctx, elementId)
                return createResult(
                    request = request,
                    status = if (res.success) UnifiedExecutionStatus.VERIFIED else UnifiedExecutionStatus.FAILED,
                    output = res.userFeedback,
                    error = if (res.success) null else res.userFeedback,
                    executor = "WastiDeviceController",
                    startedAt = startedAt,
                    verificationStatus = if (res.success) UnifiedVerificationStatus.VERIFIED else UnifiedVerificationStatus.FAILED,
                    verificationEvidence = res.userFeedback
                )
            }
            "simulate_tap_at", "click_coord", "tap_at" -> {
                val x = (params["x"]?.toString()?.toFloatOrNull()) ?: -1f
                val y = (params["y"]?.toString()?.toFloatOrNull()) ?: -1f
                if (x < 0f || y < 0f) {
                    return createResult(
                        request = request,
                        status = UnifiedExecutionStatus.FAILED,
                        output = "Error: Valid x and y coordinates required for simulate_tap_at.",
                        error = "Invalid coordinates ($x, $y)",
                        executor = "WastiDeviceController",
                        startedAt = startedAt,
                        verificationStatus = UnifiedVerificationStatus.FAILED
                    )
                }
                val res = WastiDeviceController.simulateTapAt(ctx, x, y)
                return createResult(
                    request = request,
                    status = if (res.success) UnifiedExecutionStatus.VERIFIED else UnifiedExecutionStatus.FAILED,
                    output = res.userFeedback,
                    error = if (res.success) null else res.userFeedback,
                    executor = "WastiDeviceController",
                    startedAt = startedAt,
                    verificationStatus = if (res.success) UnifiedVerificationStatus.VERIFIED else UnifiedVerificationStatus.FAILED,
                    verificationEvidence = res.userFeedback
                )
            }
            "simulate_swipe", "swipe" -> {
                val startX = (params["startX"]?.toString()?.toFloatOrNull()) ?: 0f
                val startY = (params["startY"]?.toString()?.toFloatOrNull()) ?: 0f
                val endX = (params["endX"]?.toString()?.toFloatOrNull()) ?: 0f
                val endY = (params["endY"]?.toString()?.toFloatOrNull()) ?: 0f
                val duration = (params["duration"]?.toString()?.toLongOrNull()) ?: 300L
                val res = WastiDeviceController.simulateSwipe(ctx, startX, startY, endX, endY, duration)
                return createResult(
                    request = request,
                    status = if (res.success) UnifiedExecutionStatus.VERIFIED else UnifiedExecutionStatus.FAILED,
                    output = res.userFeedback,
                    error = if (res.success) null else res.userFeedback,
                    executor = "WastiDeviceController",
                    startedAt = startedAt,
                    verificationStatus = if (res.success) UnifiedVerificationStatus.VERIFIED else UnifiedVerificationStatus.FAILED,
                    verificationEvidence = res.userFeedback
                )
            }
            "type_text", "set_text" -> {
                val text = params["text"]?.toString() ?: content
                val targetElement = params["targetElement"]?.toString() ?: target.takeIf { it.isNotBlank() }
                val res = WastiDeviceController.typeText(ctx, text, targetElement)
                return createResult(
                    request = request,
                    status = if (res.success) UnifiedExecutionStatus.VERIFIED else UnifiedExecutionStatus.FAILED,
                    output = res.userFeedback,
                    error = if (res.success) null else res.userFeedback,
                    executor = "WastiDeviceController",
                    startedAt = startedAt,
                    verificationStatus = if (res.success) UnifiedVerificationStatus.VERIFIED else UnifiedVerificationStatus.FAILED,
                    verificationEvidence = res.userFeedback
                )
            }
            "press_back", "back", "nav_back" -> {
                val res = WastiDeviceController.performBack(ctx)
                return createResult(
                    request = request,
                    status = if (res.success) UnifiedExecutionStatus.VERIFIED else UnifiedExecutionStatus.FAILED,
                    output = res.userFeedback,
                    error = if (res.success) null else res.userFeedback,
                    executor = "WastiDeviceController",
                    startedAt = startedAt,
                    verificationStatus = if (res.success) UnifiedVerificationStatus.VERIFIED else UnifiedVerificationStatus.FAILED,
                    verificationEvidence = res.userFeedback
                )
            }
            "press_home", "home", "nav_home" -> {
                val res = WastiDeviceController.performHome(ctx)
                return createResult(
                    request = request,
                    status = if (res.success) UnifiedExecutionStatus.VERIFIED else UnifiedExecutionStatus.FAILED,
                    output = res.userFeedback,
                    error = if (res.success) null else res.userFeedback,
                    executor = "WastiDeviceController",
                    startedAt = startedAt,
                    verificationStatus = if (res.success) UnifiedVerificationStatus.VERIFIED else UnifiedVerificationStatus.FAILED,
                    verificationEvidence = res.userFeedback
                )
            }
            "press_recents", "recents", "app_switcher" -> {
                val res = WastiDeviceController.performRecents(ctx)
                return createResult(
                    request = request,
                    status = if (res.success) UnifiedExecutionStatus.VERIFIED else UnifiedExecutionStatus.FAILED,
                    output = res.userFeedback,
                    error = if (res.success) null else res.userFeedback,
                    executor = "WastiDeviceController",
                    startedAt = startedAt,
                    verificationStatus = if (res.success) UnifiedVerificationStatus.VERIFIED else UnifiedVerificationStatus.FAILED,
                    verificationEvidence = res.userFeedback
                )
            }
            "notifications", "open_notifications" -> {
                val res = WastiDeviceController.performNotifications(ctx)
                return createResult(
                    request = request,
                    status = if (res.success) UnifiedExecutionStatus.VERIFIED else UnifiedExecutionStatus.FAILED,
                    output = res.userFeedback,
                    error = if (res.success) null else res.userFeedback,
                    executor = "WastiDeviceController",
                    startedAt = startedAt,
                    verificationStatus = if (res.success) UnifiedVerificationStatus.VERIFIED else UnifiedVerificationStatus.FAILED,
                    verificationEvidence = res.userFeedback
                )
            }
            "quick_settings", "open_quick_settings" -> {
                val res = WastiDeviceController.performQuickSettings(ctx)
                return createResult(
                    request = request,
                    status = if (res.success) UnifiedExecutionStatus.VERIFIED else UnifiedExecutionStatus.FAILED,
                    output = res.userFeedback,
                    error = if (res.success) null else res.userFeedback,
                    executor = "WastiDeviceController",
                    startedAt = startedAt,
                    verificationStatus = if (res.success) UnifiedVerificationStatus.VERIFIED else UnifiedVerificationStatus.FAILED,
                    verificationEvidence = res.userFeedback
                )
            }
            else -> {
                return createResult(
                    request = request,
                    status = UnifiedExecutionStatus.FAILED,
                    output = "Unknown device control action: $action",
                    error = "Device action '$action' not recognized",
                    executor = "WastiDeviceController",
                    startedAt = startedAt,
                    verificationStatus = UnifiedVerificationStatus.FAILED
                )
            }
        }
    }

    // Placeholder methods - these would be implemented in actual code
    private fun executeMemorySearch(request: UnifiedExecutionRequest, startedAt: Long): UnifiedExecutionResult = TODO()
    private fun executeSystemInfo(request: UnifiedExecutionRequest, context: Context?, startedAt: Long): UnifiedExecutionResult = TODO()
    private fun executeWebOperations(request: UnifiedExecutionRequest, action: String, context: Context?, startedAt: Long): UnifiedExecutionResult = TODO()
    private fun executeFileOperations(request: UnifiedExecutionRequest, capId: String, context: Context?, startedAt: Long): UnifiedExecutionResult = TODO()
    private fun executeProjectOperations(request: UnifiedExecutionRequest, context: Context?, startedAt: Long): UnifiedExecutionResult = TODO()
    private fun executeBuildOperations(request: UnifiedExecutionRequest, context: Context?, startedAt: Long): UnifiedExecutionResult = TODO()
    private fun executeTestOperations(request: UnifiedExecutionRequest, context: Context?, startedAt: Long): UnifiedExecutionResult = TODO()
    private fun executeDiagnosticOperations(request: UnifiedExecutionRequest, context: Context?, startedAt: Long): UnifiedExecutionResult = TODO()
    private fun executePackageOperations(request: UnifiedExecutionRequest, context: Context?, startedAt: Long): UnifiedExecutionResult = TODO()
    private fun executeSandboxOperations(request: UnifiedExecutionRequest, context: Context?, startedAt: Long): UnifiedExecutionResult = TODO()
    private fun executeWasmSandboxOperations(request: UnifiedExecutionRequest, startedAt: Long): UnifiedExecutionResult = TODO()
    private fun executeTerminalOperations(request: UnifiedExecutionRequest, capId: String, context: Context?, startedAt: Long): UnifiedExecutionResult = TODO()
    private fun executeToolRegistryOperation(request: UnifiedExecutionRequest, startedAt: Long): UnifiedExecutionResult = TODO()
    private fun executeLocalNeuralInference(request: UnifiedExecutionRequest, context: Context?, startedAt: Long): UnifiedExecutionResult = TODO()
    private fun createResult(request: UnifiedExecutionRequest, status: UnifiedExecutionStatus, output: String, error: String? = null, executor: String, startedAt: Long, verificationStatus: UnifiedVerificationStatus, verificationEvidence: String? = null): UnifiedExecutionResult = TODO()
    private fun emitEventAndAudit(request: UnifiedExecutionRequest, result: UnifiedExecutionResult): Unit = TODO()
}
