package com.example.data.agent.runtime

import android.content.Context
import com.example.WastiApplication
import com.example.data.device.WastiDeviceController
import com.example.data.memory.MemoryManager
import com.example.data.memory.model.MemorySearchQuery
import com.example.data.ops.WebSearchEngine
import kotlinx.coroutines.withTimeoutOrNull
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
    val timeoutMs: Long = 30000L
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
    val startedAt: Long,
    val completedAt: Long,
    val verificationStatus: UnifiedVerificationStatus,
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
                    if (execResult.status == UnifiedExecutionStatus.COMPLETED) UnifiedExecutionStatus.VERIFIED else execResult.status
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

            capId.startsWith("wre_tool_") || com.example.data.tool.ToolRegistry.getTool(request.capabilityId) != null || capId in listOf(
                "terminal", "execute_code", "execute_command", "run_script", "sh", "cmd",
                "bash", "python", "python3", "python_runtime", "node", "nodejs",
                "node_runtime", "javascript", "npm"
            ) -> executeTerminalOperations(request, capId, ctx, startedAt)
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
                val waStatus: UnifiedVerificationStatus = if (res.success) UnifiedVerificationStatus.VERIFIED else UnifiedVerificationStatus.FAILED
                return createResult(
                    request = request,
                    status = if (res.success) UnifiedExecutionStatus.COMPLETED else UnifiedExecutionStatus.FAILED,
                    output = res.userFeedback,
                    error = if (res.success) null else res.userFeedback,
                    executor = "WastiDeviceController",
                    startedAt = startedAt,
                    verificationStatus = waStatus,
                    verificationEvidence = "WhatsApp intent dispatched"
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
            "scroll", "scroll_down", "scroll_up" -> {
                val direction = params["direction"]?.toString() ?: if (action == "scroll_up") "UP" else "DOWN"
                val res = WastiDeviceController.performScroll(ctx, direction)
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
                    output = "Unknown or unsupported device action: '$action'",
                    error = "Device action '$action' unsupported",
                    executor = "WastiDeviceController",
                    startedAt = startedAt,
                    verificationStatus = UnifiedVerificationStatus.FAILED
                )
            }
        }
    }

    private suspend fun executeMemorySearch(
        request: UnifiedExecutionRequest,
        startedAt: Long
    ): UnifiedExecutionResult {
        val query = request.parameters["query"]?.toString() ?: ""
        if (query.isBlank()) {
            return createResult(
                request = request,
                status = UnifiedExecutionStatus.FAILED,
                output = "Error: Query parameter empty for memory search.",
                error = "Empty query parameter",
                executor = "MemoryManager",
                startedAt = startedAt,
                verificationStatus = UnifiedVerificationStatus.FAILED
            )
        }

        val results = MemoryManager.hybridSearch(MemorySearchQuery(queryText = query, topK = 5))
        val outputText = if (results.isEmpty()) {
            "No matching long-term memories found."
        } else {
            results.joinToString("\n") { "- [${it.memory.category}] ${it.memory.key}: ${it.memory.value}" }
        }

        return createResult(
            request = request,
            status = UnifiedExecutionStatus.COMPLETED,
            output = outputText,
            executor = "MemoryManager",
            startedAt = startedAt,
            verificationStatus = UnifiedVerificationStatus.VERIFIED,
            verificationEvidence = "Found ${results.size} memory items"
        )
    }

    private fun executeSystemInfo(
        request: UnifiedExecutionRequest,
        context: Context?,
        startedAt: Long
    ): UnifiedExecutionResult {
        val osInfo = "Android OS ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT}), Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
        val memoryInfo = "Runtime max memory: ${Runtime.getRuntime().maxMemory() / (1024 * 1024)} MB, free: ${Runtime.getRuntime().freeMemory() / (1024 * 1024)} MB"
        val totalCaps = realityRegistry.getSystemRealityReport().size
        val output = "Wasti OS Environment Reality:\n- $osInfo\n- $memoryInfo\n- Registered capabilities: $totalCaps\n- UnifiedExecutionFabric active."

        return createResult(
            request = request,
            status = UnifiedExecutionStatus.VERIFIED,
            output = output,
            executor = "WastiEnvironmentInspector",
            startedAt = startedAt,
            verificationStatus = UnifiedVerificationStatus.VERIFIED,
            verificationEvidence = "Environment reality verified via UnifiedExecutionFabric"
        )
    }

    private suspend fun executeWebOperations(
        request: UnifiedExecutionRequest,
        operation: String,
        context: Context?,
        startedAt: Long
    ): UnifiedExecutionResult {
        val query = request.parameters["query"]?.toString() ?: request.parameters["url"]?.toString() ?: ""
        if (query.isBlank()) {
            return createResult(
                request = request,
                status = UnifiedExecutionStatus.FAILED,
                output = "Error: Query/URL parameter required for web operation.",
                error = "Missing query/url parameter",
                executor = "WebSearchEngine",
                startedAt = startedAt,
                verificationStatus = UnifiedVerificationStatus.FAILED
            )
        }

        return when (operation) {
            "deep_search", "deep_research" -> {
                val researchResult = WebSearchEngine.executeDeepResearch(query, maxSources = 3, context = context)
                val ok = researchResult.isEvidenceVerified
                createResult(
                    request = request,
                    status = if (ok) UnifiedExecutionStatus.VERIFIED else UnifiedExecutionStatus.FAILED,
                    output = researchResult.synthesisSummary,
                    error = if (ok) null else "Deep research could not verify online sources for topic",
                    executor = "WebSearchEngine_DeepResearch",
                    startedAt = startedAt,
                    verificationStatus = if (ok) UnifiedVerificationStatus.VERIFIED else UnifiedVerificationStatus.FAILED,
                    verificationEvidence = "Sources verified: ${researchResult.sourcesConsulted.size}, Facts extracted: ${researchResult.verifiedFacts.size}"
                )
            }
            "read_web_page" -> {
                val pageData = WebSearchEngine.scrapeWebPage(query)
                val ok = !pageData.startsWith("Failed to fetch")
                createResult(
                    request = request,
                    status = if (ok) UnifiedExecutionStatus.VERIFIED else UnifiedExecutionStatus.FAILED,
                    output = pageData,
                    error = if (ok) null else pageData,
                    executor = "WebSearchEngine",
                    startedAt = startedAt,
                    verificationStatus = if (ok) UnifiedVerificationStatus.VERIFIED else UnifiedVerificationStatus.FAILED,
                    verificationEvidence = "HTTP fetch result length: ${pageData.length}"
                )
            }
            else -> {
                val searchRes = WebSearchEngine.search(query, context)
                val ok = !searchRes.contains("\"error\"") && !searchRes.contains("Exception")
                createResult(
                    request = request,
                    status = if (ok) UnifiedExecutionStatus.VERIFIED else UnifiedExecutionStatus.FAILED,
                    output = searchRes,
                    error = if (ok) null else "Web search returned error structure",
                    executor = "WebSearchEngine",
                    startedAt = startedAt,
                    verificationStatus = if (ok) UnifiedVerificationStatus.VERIFIED else UnifiedVerificationStatus.FAILED,
                    verificationEvidence = "WebSearch query executed"
                )
            }
        }
    }

    private fun executeFileOperations(
        request: UnifiedExecutionRequest,
        op: String,
        context: Context?,
        startedAt: Long
    ): UnifiedExecutionResult {
        val ctx = context ?: appContext ?: com.example.WastiApplication.instance
        if (ctx == null) {
            return createResult(
                request = request,
                status = UnifiedExecutionStatus.UNAVAILABLE,
                output = "Context unavailable for workspace file operation.",
                error = "Null Context in executeFileOperations",
                executor = "WorkspaceManager",
                startedAt = startedAt,
                verificationStatus = UnifiedVerificationStatus.FAILED
            )
        }
        val wm = WorkspaceManager(ctx)
        val path = request.parameters["path"]?.toString() ?: request.parameters["filePath"]?.toString() ?: ""
        val content = request.parameters["content"]?.toString() ?: ""

        val dest = request.parameters["destination"]?.toString() ?: request.parameters["destPath"]?.toString() ?: request.parameters["target"]?.toString() ?: ""
        val query = request.parameters["query"]?.toString() ?: ""

        val action = request.parameters["action"]?.toString()
            ?.lowercase(Locale.ROOT)
            ?.trim()
            ?: op

        val resultStr: String
        val isSuccess: Boolean
        val errorMsg: String?

        when (action) {
            "read_file", "read" -> {
                val readRes = wm.readFile(path)
                isSuccess = readRes.isSuccess
                resultStr = readRes.getOrNull() ?: ""
                errorMsg = readRes.exceptionOrNull()?.message
            }
            "write_file", "write", "create_file", "modify_file" -> {
                val writeRes = wm.writeFile(path, content)
                isSuccess = writeRes.isSuccess
                resultStr = if (isSuccess) "Successfully wrote ${content.length} characters to $path" else ""
                errorMsg = writeRes.exceptionOrNull()?.message
            }
            "append_file", "append" -> {
                val appRes = wm.appendFile(path, content)
                isSuccess = appRes.isSuccess
                resultStr = if (isSuccess) "Successfully appended ${content.length} characters to $path" else ""
                errorMsg = appRes.exceptionOrNull()?.message
            }
            "list_files", "list", "files", "ls", "dir" -> {
                val listRes = wm.listDirectory(path.ifBlank { "." })
                isSuccess = listRes.isSuccess
                resultStr = listRes.getOrNull()?.joinToString("\n") ?: ""
                errorMsg = listRes.exceptionOrNull()?.message
            }
            "delete_file", "delete", "remove_file", "rm" -> {
                val delRes = wm.deleteFile(path)
                isSuccess = delRes.isSuccess && (delRes.getOrNull() == true)
                resultStr = if (isSuccess) "Successfully deleted $path" else "File $path did not exist or could not be deleted"
                errorMsg = delRes.exceptionOrNull()?.message
            }
            "create_directory", "mkdir", "create_dir" -> {
                val dirRes = wm.createDirectory(path)
                isSuccess = dirRes.isSuccess
                resultStr = if (isSuccess) "Successfully created directory $path" else ""
                errorMsg = dirRes.exceptionOrNull()?.message
            }
            "move_file", "move", "mv" -> {
                val mvRes = wm.moveFile(path, dest)
                isSuccess = mvRes.isSuccess
                resultStr = if (isSuccess) "Successfully moved $path to $dest" else ""
                errorMsg = mvRes.exceptionOrNull()?.message
            }
            "copy_file", "copy", "cp" -> {
                val cpRes = wm.copyFile(path, dest)
                isSuccess = cpRes.isSuccess
                resultStr = if (isSuccess) "Successfully copied $path to $dest" else ""
                errorMsg = cpRes.exceptionOrNull()?.message
            }
            "rename_file", "rename" -> {
                val rnRes = wm.renameFile(path, dest)
                isSuccess = rnRes.isSuccess
                resultStr = if (isSuccess) "Successfully renamed $path to $dest" else ""
                errorMsg = rnRes.exceptionOrNull()?.message
            }
            "search_files", "search", "find" -> {
                val sRes = wm.searchFiles(query.ifBlank { path }, path.takeIf { query.isNotBlank() } ?: "")
                isSuccess = sRes.isSuccess
                val matches = sRes.getOrNull() ?: emptyList()
                resultStr = if (matches.isEmpty()) "No matching files found." else matches.joinToString("\n")
                errorMsg = sRes.exceptionOrNull()?.message
            }
            "inspect_metadata", "metadata", "stat" -> {
                val metaRes = wm.inspectMetadata(path)
                isSuccess = metaRes.isSuccess
                resultStr = metaRes.getOrNull()?.entries?.joinToString("\n") { "${it.key}: ${it.value}" } ?: ""
                errorMsg = metaRes.exceptionOrNull()?.message
            }
            else -> {
                return createResult(
                    request = request,
                    status = UnifiedExecutionStatus.NOT_IMPLEMENTED,
                    output = "No WorkspaceManager executor is registered for file action '$action'.",
                    error = "Unsupported file action: $action",
                    executor = "WorkspaceManager",
                    startedAt = startedAt,
                    verificationStatus = UnifiedVerificationStatus.NOT_APPLICABLE
                )
            }
        }

        return createResult(
            request = request,
            status = if (isSuccess) UnifiedExecutionStatus.VERIFIED else UnifiedExecutionStatus.FAILED,
            output = if (isSuccess) resultStr else (errorMsg ?: "File operation failed"),
            error = errorMsg,
            executor = "WorkspaceManager",
            startedAt = startedAt,
            verificationStatus = if (isSuccess) UnifiedVerificationStatus.VERIFIED else UnifiedVerificationStatus.FAILED,
            verificationEvidence = if (isSuccess) "Workspace operation verified" else (errorMsg ?: "Failed")
        )
    }

    private fun executeProjectOperations(
        request: UnifiedExecutionRequest,
        context: Context?,
        startedAt: Long
    ): UnifiedExecutionResult {
        val ctx = context ?: appContext ?: com.example.WastiApplication.instance
        if (ctx == null) {
            return createResult(
                request = request,
                status = UnifiedExecutionStatus.UNAVAILABLE,
                output = "Context unavailable for project operation.",
                error = "Null Context in executeProjectOperations",
                executor = "WastiLanguagePlatform",
                startedAt = startedAt,
                verificationStatus = UnifiedVerificationStatus.FAILED
            )
        }
        val platform = WastiLanguagePlatform(ctx)
        val action = request.parameters["action"]?.toString()
            ?.lowercase(Locale.ROOT)
            ?.trim()
            ?: when (normalizedCapabilityId(request.capabilityId)) {
                "project_dev_manager", "project", "dev_environment" -> "create_project"
                else -> normalizedCapabilityId(request.capabilityId)
            }
        val projName = request.parameters["projectName"]?.toString() ?: "wasti_project"
        val language = request.parameters["language"]?.toString() ?: "PYTHON"
        val template = request.parameters["template"]?.toString() ?: "default"

        return when (action) {
            "create_project", "scaffold" -> {
                val createRes = platform.createProject(
                    ProjectCreationRequest(
                        projectName = projName,
                        language = language,
                        template = template
                    )
                )
                createResult(
                    request = request,
                    status = if (createRes.isSuccess) UnifiedExecutionStatus.VERIFIED else UnifiedExecutionStatus.FAILED,
                    output = createRes.message,
                    error = if (createRes.isSuccess) null else createRes.message,
                    executor = "WastiLanguagePlatform",
                    startedAt = startedAt,
                    verificationStatus = if (createRes.isSuccess) UnifiedVerificationStatus.VERIFIED else UnifiedVerificationStatus.FAILED,
                    verificationEvidence = "Project files created: ${createRes.createdFiles.size}"
                )
            }
            "create_managed_project" -> {
                val pm = WastiProjectManager(ctx)
                val createRes = pm.createManagedProject(
                    name = projName,
                    language = language,
                    template = template
                )
                if (createRes.isSuccess) {
                    val meta = createRes.getOrThrow()
                    createResult(
                        request = request,
                        status = UnifiedExecutionStatus.VERIFIED,
                        output = "Managed project '${meta.name}' created at ${meta.relativePath}",
                        executor = "WastiProjectManager",
                        startedAt = startedAt,
                        verificationStatus = UnifiedVerificationStatus.VERIFIED,
                        verificationEvidence = "Project created: ${meta.projectId}"
                    )
                } else {
                    createResult(
                        request = request,
                        status = UnifiedExecutionStatus.FAILED,
                        output = createRes.exceptionOrNull()?.message ?: "Project creation failed",
                        error = createRes.exceptionOrNull()?.message,
                        executor = "WastiProjectManager",
                        startedAt = startedAt,
                        verificationStatus = UnifiedVerificationStatus.FAILED
                    )
                }
            }
            "inspect_project" -> {
                val pm = WastiProjectManager(ctx)
                val inspRes = pm.inspectProject(projName)
                if (inspRes.isSuccess) {
                    val insp = inspRes.getOrThrow()
                    createResult(
                        request = request,
                        status = UnifiedExecutionStatus.VERIFIED,
                        output = "Project '${projName}': Language=${insp.detectedLanguage}, Files=${insp.totalFiles}, BuildTool=${insp.detectedBuildTool}",
                        executor = "WastiProjectManager",
                        startedAt = startedAt,
                        verificationStatus = UnifiedVerificationStatus.VERIFIED,
                        verificationEvidence = "Files inspected: ${insp.totalFiles}"
                    )
                } else {
                    createResult(
                        request = request,
                        status = UnifiedExecutionStatus.FAILED,
                        output = inspRes.exceptionOrNull()?.message ?: "Inspection failed",
                        error = inspRes.exceptionOrNull()?.message,
                        executor = "WastiProjectManager",
                        startedAt = startedAt,
                        verificationStatus = UnifiedVerificationStatus.FAILED
                    )
                }
            }
            "list_projects" -> {
                val pm = WastiProjectManager(ctx)
                val projects = pm.listProjects()
                createResult(
                    request = request,
                    status = UnifiedExecutionStatus.VERIFIED,
                    output = if (projects.isEmpty()) "No projects found in workspace." else "Projects (${projects.size}): ${projects.joinToString(", ")}",
                    executor = "WastiProjectManager",
                    startedAt = startedAt,
                    verificationStatus = UnifiedVerificationStatus.VERIFIED,
                    verificationEvidence = "Projects listed: ${projects.size}"
                )
            }
            "delete_project" -> {
                val pm = WastiProjectManager(ctx)
                val deleted = pm.deleteProject(projName)
                createResult(
                    request = request,
                    status = if (deleted) UnifiedExecutionStatus.VERIFIED else UnifiedExecutionStatus.FAILED,
                    output = if (deleted) "Project '$projName' deleted from workspace." else "Project '$projName' not found or could not be deleted.",
                    executor = "WastiProjectManager",
                    startedAt = startedAt,
                    verificationStatus = if (deleted) UnifiedVerificationStatus.VERIFIED else UnifiedVerificationStatus.FAILED
                )
            }
            "get_language_profile", "scan_languages" -> {
                val profile = platform.getProfile(language)
                if (profile != null) {
                    createResult(
                        request = request,
                        status = UnifiedExecutionStatus.VERIFIED,
                        output = "Language Profile [${profile.displayName}]: Runtime=${profile.runtimeState}, Compiler=${profile.compilerState}, Execution=${profile.executionState}, Reality=${profile.realityState}",
                        executor = "WastiLanguagePlatform",
                        startedAt = startedAt,
                        verificationStatus = UnifiedVerificationStatus.VERIFIED,
                        verificationEvidence = "Language profile retrieved"
                    )
                } else {
                    createResult(
                        request = request,
                        status = UnifiedExecutionStatus.FAILED,
                        output = "Language '$language' not recognized",
                        error = "Unknown language",
                        executor = "WastiLanguagePlatform",
                        startedAt = startedAt,
                        verificationStatus = UnifiedVerificationStatus.FAILED
                    )
                }
            }
            else -> {
                createResult(
                    request = request,
                    status = UnifiedExecutionStatus.FAILED,
                    output = "Unsupported project action '$action'",
                    error = "Unsupported project action",
                    executor = "WastiLanguagePlatform",
                    startedAt = startedAt,
                    verificationStatus = UnifiedVerificationStatus.FAILED
                )
            }
        }
    }

    private suspend fun executeBuildOperations(
        request: UnifiedExecutionRequest,
        context: Context?,
        startedAt: Long
    ): UnifiedExecutionResult {
        val ctx = context ?: appContext ?: com.example.WastiApplication.instance
        if (ctx == null) {
            return createResult(
                request = request,
                status = UnifiedExecutionStatus.UNAVAILABLE,
                output = "Context unavailable for build operation.",
                error = "Null Context in executeBuildOperations",
                executor = "WastiBuildAndTestManager",
                startedAt = startedAt,
                verificationStatus = UnifiedVerificationStatus.FAILED
            )
        }
        val btm = WastiBuildAndTestManager(ctx)
        val projId = request.parameters["projectId"]?.toString() ?: "wasti_project"
        val projPath = request.parameters["projectPath"]?.toString() ?: "projects/$projId"
        val language = request.parameters["language"]?.toString() ?: "PYTHON"

        val buildRes = btm.buildProject(
            BuildRequest(
                projectId = projId,
                projectPath = projPath,
                language = language
            )
        )

        val status = when (buildRes.status) {
            BuildStatus.SUCCESS -> UnifiedExecutionStatus.VERIFIED
            BuildStatus.TOOLCHAIN_MISSING, BuildStatus.DEPENDENCY_MISSING -> UnifiedExecutionStatus.UNAVAILABLE
            else -> UnifiedExecutionStatus.FAILED
        }

        return createResult(
            request = request,
            status = status,
            output = if (buildRes.stdout.isNotBlank()) buildRes.stdout else buildRes.stderr,
            error = if (buildRes.status == BuildStatus.SUCCESS) null else buildRes.stderr,
            executor = "WastiBuildAndTestManager",
            startedAt = startedAt,
            verificationStatus = if (buildRes.status == BuildStatus.SUCCESS) UnifiedVerificationStatus.VERIFIED else UnifiedVerificationStatus.FAILED,
            verificationEvidence = buildRes.verificationState
        )
    }

    private suspend fun executeTestOperations(
        request: UnifiedExecutionRequest,
        context: Context?,
        startedAt: Long
    ): UnifiedExecutionResult {
        val ctx = context ?: appContext ?: com.example.WastiApplication.instance
        if (ctx == null) {
            return createResult(
                request = request,
                status = UnifiedExecutionStatus.UNAVAILABLE,
                output = "Context unavailable for test operation.",
                error = "Null Context in executeTestOperations",
                executor = "WastiBuildAndTestManager",
                startedAt = startedAt,
                verificationStatus = UnifiedVerificationStatus.FAILED
            )
        }
        val btm = WastiBuildAndTestManager(ctx)
        val projId = request.parameters["projectId"]?.toString() ?: "wasti_project"
        val projPath = request.parameters["projectPath"]?.toString() ?: "projects/$projId"
        val language = request.parameters["language"]?.toString() ?: "PYTHON"

        val report = btm.runTests(projId, projPath, language)
        val isPass = report.status == TestExecutionStatus.PASSED || report.status == TestExecutionStatus.STATICALLY_VALIDATED

        return createResult(
            request = request,
            status = if (isPass) UnifiedExecutionStatus.VERIFIED else UnifiedExecutionStatus.FAILED,
            output = "Tests Run: ${report.totalTests}, Passed: ${report.passedTests}, Failed: ${report.failedTests}. ${report.stdout}",
            error = if (isPass) null else report.stderr,
            executor = "WastiBuildAndTestManager",
            startedAt = startedAt,
            verificationStatus = if (isPass) UnifiedVerificationStatus.VERIFIED else UnifiedVerificationStatus.FAILED,
            verificationEvidence = "PassedTests: ${report.passedTests}/${report.totalTests}"
        )
    }

    private fun executeDiagnosticOperations(
        request: UnifiedExecutionRequest,
        context: Context?,
        startedAt: Long
    ): UnifiedExecutionResult {
        val ctx = context ?: appContext ?: com.example.WastiApplication.instance
        if (ctx == null) {
            return createResult(
                request = request,
                status = UnifiedExecutionStatus.UNAVAILABLE,
                output = "Context unavailable for diagnostic operation.",
                error = "Null Context in executeDiagnosticOperations",
                executor = "WastiBuildAndTestManager",
                startedAt = startedAt,
                verificationStatus = UnifiedVerificationStatus.FAILED
            )
        }
        val btm = WastiBuildAndTestManager(ctx)
        val projId = request.parameters["projectId"]?.toString() ?: "wasti_project"
        val rawLogs = request.parameters["logs"]?.toString() ?: request.parameters["output"]?.toString() ?: ""

        val diag = btm.analyzeDiagnostics(projId, rawLogs)
        return createResult(
            request = request,
            status = UnifiedExecutionStatus.VERIFIED,
            output = "Diagnostics for '$projId': Errors=${diag.totalErrors}, Warnings=${diag.totalWarnings}. ${diag.rootCauseSummary}",
            executor = "WastiBuildAndTestManager",
            startedAt = startedAt,
            verificationStatus = UnifiedVerificationStatus.VERIFIED,
            verificationEvidence = "Findings: ${diag.findings.size}"
        )
    }

    private fun executePackageOperations(
        request: UnifiedExecutionRequest,
        context: Context?,
        startedAt: Long
    ): UnifiedExecutionResult {
        val ctx = context ?: appContext ?: com.example.WastiApplication.instance
        if (ctx == null) {
            return createResult(
                request = request,
                status = UnifiedExecutionStatus.UNAVAILABLE,
                output = "Context unavailable for package operation.",
                error = "Null Context in executePackageOperations",
                executor = "WastiRuntimeManager",
                startedAt = startedAt,
                verificationStatus = UnifiedVerificationStatus.FAILED
            )
        }
        val rm = WastiRuntimeManager(ctx)
        val language = request.parameters["language"]?.toString() ?: "PYTHON"
        val pkgName = request.parameters["packageName"]?.toString() ?: request.parameters["package"]?.toString() ?: ""

        val res = rm.resolvePackage(language, pkgName)
        val status = when (res.status) {
            RuntimeRealityStatus.AVAILABLE -> UnifiedExecutionStatus.VERIFIED
            RuntimeRealityStatus.NOT_INSTALLED, RuntimeRealityStatus.TOOLCHAIN_MISSING, RuntimeRealityStatus.UNAVAILABLE -> UnifiedExecutionStatus.UNAVAILABLE
            else -> UnifiedExecutionStatus.FAILED
        }

        return createResult(
            request = request,
            status = status,
            output = res.message,
            error = if (res.isSuccess) null else res.message,
            executor = "WastiRuntimeManager",
            startedAt = startedAt,
            verificationStatus = if (res.isSuccess) UnifiedVerificationStatus.VERIFIED else UnifiedVerificationStatus.FAILED,
            verificationEvidence = "Package ${res.packageName} state: ${res.status}"
        )
    }

    private suspend fun executeSandboxOperations(
        request: UnifiedExecutionRequest,
        context: Context?,
        startedAt: Long
    ): UnifiedExecutionResult {
        val ctx = context ?: appContext ?: com.example.WastiApplication.instance
        if (ctx == null) {
            return createResult(
                request = request,
                status = UnifiedExecutionStatus.UNAVAILABLE,
                output = "Context unavailable for sandbox execution.",
                error = "Null Context in executeSandboxOperations",
                executor = "WastiSandbox",
                startedAt = startedAt,
                verificationStatus = UnifiedVerificationStatus.FAILED
            )
        }
        val sandbox = WastiSandbox(ctx)
        val cmd = request.parameters["command"]?.toString() ?: "sh"
        val args = (request.parameters["arguments"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
        val workDir = request.parameters["workingDirectory"]?.toString() ?: ""

        val res = sandbox.executeInSandbox(
            SandboxExecutionRequest(
                command = cmd,
                arguments = args,
                workingDirectory = workDir
            )
        )

        val isNotInstalled = res.stderr.contains("NOT_INSTALLED") || res.verificationState.contains("NOT_INSTALLED")
        val status = when {
            res.isSuccess -> UnifiedExecutionStatus.VERIFIED
            res.isPolicyBlocked -> UnifiedExecutionStatus.FAILED
            isNotInstalled -> UnifiedExecutionStatus.UNAVAILABLE
            else -> UnifiedExecutionStatus.FAILED
        }

        return createResult(
            request = request,
            status = status,
            output = if (res.stdout.isNotBlank()) res.stdout else res.stderr,
            error = if (res.isSuccess) null else res.stderr,
            executor = "WastiSandbox",
            startedAt = startedAt,
            verificationStatus = if (res.isSuccess) UnifiedVerificationStatus.VERIFIED else UnifiedVerificationStatus.FAILED,
            verificationEvidence = res.verificationState
        )
    }

    private fun executeWasmSandboxOperations(
        request: UnifiedExecutionRequest,
        startedAt: Long
    ): UnifiedExecutionResult {
        val wasmRuntime = com.example.data.sandbox.WastiWasmRuntime.instance
        val toolName = request.parameters["toolName"]?.toString() ?: "sandboxed_wasm_eval"
        val expression = request.parameters["expression"]?.toString() ?: request.parameters["code"]?.toString() ?: ""
        val pMap = request.parameters.filterKeys { it !in setOf("toolName", "expression", "code", "action", "language") }
            .mapValues { it.value.toString() }

        val res = wasmRuntime.runSandboxedScript(toolName, expression, pMap)
        val status = if (res.isSuccess) UnifiedExecutionStatus.VERIFIED else UnifiedExecutionStatus.FAILED
        val output = res.stringOutput ?: (if (res.isSuccess) "WASM sandboxed tool executed successfully. Fuel: ${res.fuelConsumed}" else (res.diagnosticMessage ?: "WASM execution failed"))

        return createResult(
            request = request,
            status = status,
            output = output,
            error = if (res.isSuccess) null else res.diagnosticMessage,
            executor = "WastiWasmRuntime",
            startedAt = startedAt,
            verificationStatus = if (res.isSuccess) UnifiedVerificationStatus.VERIFIED else UnifiedVerificationStatus.FAILED,
            verificationEvidence = "WASM Execution verified, fuel: ${res.fuelConsumed}"
        )
    }

    private suspend fun executeTerminalOperations(
        request: UnifiedExecutionRequest,
        capId: String,
        context: Context?,
        startedAt: Long
    ): UnifiedExecutionResult {
        val ctx = context ?: appContext ?: com.example.WastiApplication.instance
        if (ctx == null) {
            return createResult(
                request = request,
                status = UnifiedExecutionStatus.UNAVAILABLE,
                output = "Context unavailable for terminal execution.",
                error = "Null Context in executeTerminalOperations",
                executor = "WreManager",
                startedAt = startedAt,
                verificationStatus = UnifiedVerificationStatus.FAILED
            )
        }

        // Stage 9B: Route terminal and code execution through WreManager
        val wreManager = com.example.data.wre.WreManager.getInstance(ctx)
        val rawCmd = request.parameters["command"]?.toString()
            ?: request.parameters["executable"]?.toString()
            ?: when {
                capId.startsWith("wre_tool_") -> capId.removePrefix("wre_tool_")
                capId == "python_runtime" -> "python3"
                capId in listOf("node_runtime", "nodejs", "javascript") -> "node"
                else -> capId
            }
        val rawArgs = (request.parameters["arguments"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
        val fullCmd = when {
            rawCmd == "sh" && rawArgs.size >= 2 && rawArgs[0] == "-c" -> rawArgs.subList(1, rawArgs.size).joinToString(" ")
            rawArgs.isNotEmpty() -> "$rawCmd ${rawArgs.joinToString(" ")}"
            else -> rawCmd
        }
        val workDir = request.parameters["workingDirectory"]?.toString() ?: "home/wasti"
        val timeout = (request.parameters["timeoutMs"] as? Number)?.toLong() ?: 30000L

        val wreReq = com.example.data.wre.ExecutionRequest(
            command = fullCmd,
            arguments = rawArgs,
            workingDirectory = workDir,
            timeoutMs = timeout,
            initiatedBy = "UnifiedExecutionFabric"
        )

        val wreResult = wreManager.execute(wreReq)
        val isPythonOrNode = capId in listOf("python", "python3", "python_runtime", "node", "nodejs", "node_runtime", "javascript", "npm")
        val isUnavailableOutput = wreResult.stderr.contains("not found") || wreResult.stderr.contains("unavailable") || wreResult.exitCode == 127
        
        val finalStatus = when {
            wreResult.status == com.example.data.wre.ExecutionStatus.SUCCESS -> UnifiedExecutionStatus.VERIFIED
            wreResult.status == com.example.data.wre.ExecutionStatus.UNAVAILABLE || (isPythonOrNode && isUnavailableOutput) -> UnifiedExecutionStatus.UNAVAILABLE
            wreResult.status == com.example.data.wre.ExecutionStatus.DENIED -> UnifiedExecutionStatus.FAILED
            else -> UnifiedExecutionStatus.FAILED
        }
        val finalVerStatus = when {
            finalStatus == UnifiedExecutionStatus.VERIFIED -> UnifiedVerificationStatus.VERIFIED
            finalStatus == UnifiedExecutionStatus.UNAVAILABLE -> UnifiedVerificationStatus.VERIFICATION_UNAVAILABLE
            else -> UnifiedVerificationStatus.FAILED
        }
        val finalOutput = when {
            isPythonOrNode && isUnavailableOutput -> "Python runtime is not currently available on this device."
            wreResult.stdout.isNotBlank() -> wreResult.stdout
            else -> wreResult.stderr
        }


        return createResult(
            request = request,
            status = finalStatus,
            output = finalOutput,
            error = if (wreResult.exitCode != 0) wreResult.stderr else null,
            executor = "WastiNativeExecutionProvider",
            startedAt = startedAt,
            verificationStatus = finalVerStatus,
            verificationEvidence = if (wreResult.verified) wreResult.verificationEvidence ?: "VERIFIED" else "UNVERIFIED",
            exitCode = wreResult.exitCode
        )
    }

    private fun createResult(
        request: UnifiedExecutionRequest,
        status: UnifiedExecutionStatus,
        output: String,
        error: String? = null,
        executor: String,
        startedAt: Long,
        verificationStatus: UnifiedVerificationStatus,
        verificationEvidence: String? = null,
        exitCode: Int? = null,
        details: Map<String, String> = emptyMap()
    ): UnifiedExecutionResult {
        return UnifiedExecutionResult(
            taskId = request.taskId,
            actionId = request.actionId,
            capabilityId = request.capabilityId,
            status = status,
            output = output,
            error = error,
            executor = executor,
            providerOrModel = null,
            startedAt = startedAt,
            completedAt = System.currentTimeMillis(),
            verificationStatus = verificationStatus,
            verificationEvidence = verificationEvidence,
            exitCode = exitCode,
            details = details
        )
    }

    private suspend fun emitEventAndAudit(request: UnifiedExecutionRequest, result: UnifiedExecutionResult) {
        val taskId = TaskId(request.taskId)
        if (result.status == UnifiedExecutionStatus.COMPLETED || result.status == UnifiedExecutionStatus.VERIFIED) {
            eventBus?.emit(AgentEvent.ToolCompleted(taskId, request.capabilityId, isSuccess = true))
            eventBus?.emit(AgentEvent.ObservationReceived(taskId, result.output.take(150)))
        } else if (result.status == UnifiedExecutionStatus.FAILED || result.status == UnifiedExecutionStatus.VERIFICATION_FAILED) {
            eventBus?.emit(AgentEvent.ToolFailed(taskId, request.capabilityId, result.error ?: "Unknown error"))
        }
    }
}
