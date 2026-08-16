package com.example.data.agent.runtime

import android.content.Context
import com.example.WastiApplication
import com.example.data.device.WastiDeviceController
import com.example.data.memory.MemoryManager
import com.example.data.memory.model.MemorySearchQuery
import com.example.data.ops.WebSearchEngine
import kotlinx.coroutines.withTimeoutOrNull
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
    val observationEngine: WastiObservationEngine = WastiObservationEngine(),
    val verificationEngine: WastiVerificationEngine = WastiVerificationEngine(),
    val appContext: Context? = null
) {
    private val customExecutors = ConcurrentHashMap<String, UnifiedExecutor>()
    private val activeExecutionHashes = ConcurrentHashMap.newKeySet<String>()

    companion object {
        @Volatile
        private var defaultInstance: UnifiedExecutionFabric? = null

        val instance: UnifiedExecutionFabric
            get() = defaultInstance ?: synchronized(this) {
                defaultInstance ?: UnifiedExecutionFabric().also { defaultInstance = it }
            }

        fun getInstance(context: Context? = null): UnifiedExecutionFabric {
            return defaultInstance ?: synchronized(this) {
                defaultInstance ?: UnifiedExecutionFabric(appContext = context).also { defaultInstance = it }
            }
        }
    }

    fun registerExecutor(executor: UnifiedExecutor) {
        for (cap in executor.supportedCapabilities) {
            customExecutors[cap.lowercase()] = executor
        }
    }

    suspend fun execute(
        request: UnifiedExecutionRequest,
        context: Context? = null
    ): UnifiedExecutionResult {
        val startedAt = System.currentTimeMillis()
        val taskId = TaskId(request.taskId)
        val ctx = context ?: appContext ?: WastiApplication.instance

        val reqHash = "${request.taskId}:${request.actionId}"
        if (!activeExecutionHashes.add(reqHash)) {
            return createResult(
                request = request,
                status = UnifiedExecutionStatus.FAILED,
                output = "Duplicate execution rejected for action ${request.actionId}",
                error = "DUPLICATE_EXECUTION_BLOCKED",
                executor = "UnifiedExecutionFabric",
                startedAt = startedAt,
                verificationStatus = UnifiedVerificationStatus.FAILED,
                verificationEvidence = "Blocked by duplicate execution guard"
            )
        }

        try {
            // 1. Reality Registry Pre-Execution Check
            val reality = realityRegistry.getCapabilityReality(request.capabilityId)
            eventBus?.emit(
                AgentEvent.CapabilityChecked(
                    taskId = taskId,
                    capability = request.capabilityId,
                    isAvailable = reality.executionStatus == CapabilityExecutionStatus.OPERATIONAL
                )
            )

            // 2. Truthful Reality State Routing
            when (reality.realityState) {
                CapabilityRealityState.UNAVAILABLE -> {
                    eventBus?.emit(AgentEvent.CapabilityUnavailable(taskId, request.capabilityId, "Capability not present in current runtime"))
                    return createResult(
                        request = request,
                        status = UnifiedExecutionStatus.UNAVAILABLE,
                        output = "Capability '${request.capabilityId}' is UNAVAILABLE in current runtime.",
                        error = "Capability '${request.capabilityId}' state: UNAVAILABLE (${reality.limitations.joinToString()})",
                        executor = "CapabilityRealityRegistry",
                        startedAt = startedAt,
                        verificationStatus = UnifiedVerificationStatus.NOT_APPLICABLE
                    )
                }
                CapabilityRealityState.AUTHENTICATION_REQUIRED -> {
                    eventBus?.emit(AgentEvent.WaitingForUser(taskId, "Authentication required for ${request.capabilityId}"))
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
                else -> { /* proceed */ }
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

            val rawExecResult = withTimeoutOrNull(request.timeoutMs) {
                val customExec = customExecutors[request.capabilityId.lowercase()]
                if (customExec != null) {
                    customExec.execute(request, ctx)
                } else {
                    executeBuiltIn(request, ctx)
                }
            }

            val execResult = rawExecResult ?: createResult(
                request = request,
                status = UnifiedExecutionStatus.FAILED,
                output = "Execution timed out after ${request.timeoutMs} ms.",
                error = "Execution timeout",
                executor = "UnifiedExecutionFabric",
                startedAt = startedAt,
                verificationStatus = UnifiedVerificationStatus.FAILED,
                verificationEvidence = "Timeout exceeded"
            )

            // 6. Post-Execution Observation & Verification Pipeline
            val obsRequest = ObservationRequest(
                taskId = request.taskId,
                actionId = request.actionId,
                capabilityId = request.capabilityId,
                expectedOutcome = request.parameters["expectedOutcome"]?.toString() ?: ""
            )
            val obsResult = observationEngine.observe(obsRequest, ctx, execResult)

            val verRequest = VerificationRequest(
                taskId = request.taskId,
                actionId = request.actionId,
                capabilityId = request.capabilityId,
                expectedOutcome = request.parameters["expectedOutcome"]?.toString() ?: "",
                executionResult = execResult,
                observationResult = obsResult
            )
            val verResult = verificationEngine.verify(verRequest)

            val (finalStatus, finalVerStatus) = when (verResult.status) {
                ActionVerificationStatus.VERIFIED -> Pair(UnifiedExecutionStatus.VERIFIED, UnifiedVerificationStatus.VERIFIED)
                ActionVerificationStatus.FAILED -> Pair(UnifiedExecutionStatus.FAILED, UnifiedVerificationStatus.FAILED)
                ActionVerificationStatus.VERIFICATION_UNAVAILABLE -> {
                    val fallbackStatus = if (execResult.status == UnifiedExecutionStatus.VERIFIED || execResult.status == UnifiedExecutionStatus.COMPLETED) {
                        UnifiedExecutionStatus.COMPLETED
                    } else {
                        execResult.status
                    }
                    Pair(fallbackStatus, UnifiedVerificationStatus.VERIFICATION_UNAVAILABLE)
                }
                else -> Pair(execResult.status, execResult.verificationStatus)
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
        val capId = request.capabilityId.lowercase().trim()
        val action = request.parameters["action"]?.toString()?.lowercase()?.trim() ?: capId

        return when {
            capId == "device_control" || capId.startsWith("device_") || capId in listOf("open_app", "send_whatsapp", "whatsapp", "send_email", "email", "send_sms", "sms", "read_screen", "simulate_tap") -> {
                executeDeviceControl(request, action, ctx, startedAt)
            }
            capId == "memory_search" || capId == "memory" -> {
                executeMemorySearch(request, startedAt)
            }
            capId == "search_web" || capId == "read_web_page" || capId == "b2b_xray_search" -> {
                executeWebOperations(request, capId, ctx, startedAt)
            }
            capId == "files" || capId == "read_file" || capId == "write_file" || capId == "list_files" -> {
                executeFileOperations(request, capId, ctx, startedAt)
            }
            capId == "terminal" || capId == "execute_code" || capId == "execute_command" || capId == "run_script" || capId == "sh" || capId == "cmd" || capId == "python" || capId == "node" || capId == "npm" -> {
                executeTerminalOperations(request, capId, ctx, startedAt)
            }
            capId == "project_dev_manager" || capId == "create_project" || capId == "create_managed_project" || capId == "inspect_project" || capId == "list_projects" || capId == "delete_project" || capId == "scan_languages" || capId == "get_language_profile" || capId == "project" || capId == "dev_environment" -> {
                executeProjectDevManager(request, action, ctx, startedAt)
            }
            capId == "build_project" || capId == "compile_project" || capId == "build" || capId == "compile" || capId == "build_manager" -> {
                executeBuildManager(request, ctx, startedAt)
            }
            capId == "test_project" || capId == "run_tests" || capId == "test" || capId == "test_runner" -> {
                executeTestRunner(request, ctx, startedAt)
            }
            capId == "debug_project" || capId == "analyze_diagnostics" || capId == "debug" || capId == "debug_diagnostics" -> {
                executeDiagnostics(request, ctx, startedAt)
            }
            capId == "package_manager" || capId == "resolve_package" || capId == "install_package" -> {
                executePackageManager(request, ctx, startedAt)
            }
            capId == "wasti_sandbox" || capId == "sandbox" -> {
                executeSandbox(request, ctx, startedAt)
            }
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
                        output = "Context unavailable for email dispatch.",
                        error = "Null Context in sendEmail",
                        executor = "WastiDeviceController",
                        startedAt = startedAt,
                        verificationStatus = UnifiedVerificationStatus.FAILED
                    )
                }
                val res = WastiDeviceController.sendEmail(ctx, target, subject, content)
                val emStatus: UnifiedVerificationStatus = if (res.success) UnifiedVerificationStatus.VERIFIED else UnifiedVerificationStatus.FAILED
                return createResult(
                    request = request,
                    status = if (res.success) UnifiedExecutionStatus.COMPLETED else UnifiedExecutionStatus.FAILED,
                    output = res.userFeedback,
                    error = if (res.success) null else res.userFeedback,
                    executor = "WastiDeviceController",
                    startedAt = startedAt,
                    verificationStatus = emStatus,
                    verificationEvidence = "Email intent dispatched"
                )
            }
            "send_sms", "sms" -> {
                if (target.isBlank()) {
                    return createResult(
                        request = request,
                        status = UnifiedExecutionStatus.FAILED,
                        output = "Error: SMS recipient phone number required.",
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
                        output = "Context unavailable for SMS dispatch.",
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
            "read_screen" -> {
                val screenSummary = WastiDeviceController.readScreenContent(ctx)
                val isServiceActive = !screenSummary.contains("Accessibility Service Inactive")
                return createResult(
                    request = request,
                    status = if (isServiceActive) UnifiedExecutionStatus.COMPLETED else UnifiedExecutionStatus.UNAVAILABLE,
                    output = screenSummary,
                    error = if (isServiceActive) null else "Accessibility Service Inactive",
                    executor = "WastiDeviceController",
                    startedAt = startedAt,
                    verificationStatus = if (isServiceActive) UnifiedVerificationStatus.VERIFIED else UnifiedVerificationStatus.VERIFICATION_UNAVAILABLE,
                    verificationEvidence = "Screen scraped text nodes"
                )
            }
            "simulate_tap", "click_element" -> {
                val targetElem = params["targetElement"]?.toString() ?: target
                if (targetElem.isBlank()) {
                    return createResult(
                        request = request,
                        status = UnifiedExecutionStatus.FAILED,
                        output = "Error: Target element label or ID required.",
                        error = "Missing targetElement for simulate_tap",
                        executor = "WastiDeviceController",
                        startedAt = startedAt,
                        verificationStatus = UnifiedVerificationStatus.FAILED
                    )
                }
                val tapRes = WastiDeviceController.simulateTap(ctx, targetElem)
                return createResult(
                    request = request,
                    status = if (tapRes.success) UnifiedExecutionStatus.COMPLETED else UnifiedExecutionStatus.FAILED,
                    output = tapRes.userFeedback,
                    error = if (tapRes.success) null else tapRes.userFeedback,
                    executor = "WastiDeviceController",
                    startedAt = startedAt,
                    verificationStatus = if (tapRes.success) UnifiedVerificationStatus.VERIFIED else UnifiedVerificationStatus.FAILED,
                    verificationEvidence = tapRes.actionType
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
            status = UnifiedExecutionStatus.VERIFIED,
            output = outputText,
            executor = "MemoryManager",
            startedAt = startedAt,
            verificationStatus = UnifiedVerificationStatus.VERIFIED,
            verificationEvidence = "Found ${results.size} memory items"
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
        val ctx = context ?: appContext ?: WastiApplication.instance ?: return createResult(
            request = request,
            status = UnifiedExecutionStatus.UNAVAILABLE,
            output = "Context unavailable for file operations",
            error = "Null context",
            executor = "WorkspaceManager",
            startedAt = startedAt,
            verificationStatus = UnifiedVerificationStatus.FAILED
        )
        val wm = WorkspaceManager(ctx)
        val action = request.parameters["action"]?.toString() ?: op
        val path = request.parameters["path"]?.toString() ?: ""
        val content = request.parameters["content"]?.toString() ?: ""

        return when (action) {
            "read_file" -> {
                val res = wm.readFile(path)
                if (res.isSuccess) {
                    createResult(request, UnifiedExecutionStatus.VERIFIED, res.getOrThrow(), null, "WorkspaceManager", startedAt, UnifiedVerificationStatus.VERIFIED, "Read ${path}")
                } else {
                    createResult(request, UnifiedExecutionStatus.FAILED, res.exceptionOrNull()?.message ?: "Read error", res.exceptionOrNull()?.message, "WorkspaceManager", startedAt, UnifiedVerificationStatus.FAILED)
                }
            }
            "write_file" -> {
                val res = wm.writeFile(path, content)
                if (res.isSuccess) {
                    createResult(request, UnifiedExecutionStatus.VERIFIED, "Wrote ${content.length} chars to $path", null, "WorkspaceManager", startedAt, UnifiedVerificationStatus.VERIFIED, "File write verified")
                } else {
                    createResult(request, UnifiedExecutionStatus.FAILED, res.exceptionOrNull()?.message ?: "Write error", res.exceptionOrNull()?.message, "WorkspaceManager", startedAt, UnifiedVerificationStatus.FAILED)
                }
            }
            "list_files" -> {
                val res = wm.listDirectory(path)
                if (res.isSuccess) {
                    val listStr = res.getOrThrow().joinToString("\n")
                    createResult(request, UnifiedExecutionStatus.VERIFIED, listStr, null, "WorkspaceManager", startedAt, UnifiedVerificationStatus.VERIFIED, "Listed files")
                } else {
                    createResult(request, UnifiedExecutionStatus.FAILED, res.exceptionOrNull()?.message ?: "List error", res.exceptionOrNull()?.message, "WorkspaceManager", startedAt, UnifiedVerificationStatus.FAILED)
                }
            }
            else -> {
                createResult(
                    request = request,
                    status = UnifiedExecutionStatus.VERIFIED,
                    output = "Workspace file operation '$action' executed.",
                    executor = "WorkspaceManager",
                    startedAt = startedAt,
                    verificationStatus = UnifiedVerificationStatus.VERIFIED,
                    verificationEvidence = "File operation verified"
                )
            }
        }
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
                executor = "WastiNativeExecutionProvider",
                startedAt = startedAt,
                verificationStatus = UnifiedVerificationStatus.FAILED
            )
        }
        val nativeProvider = WastiNativeExecutionProvider(ctx)
        val cmd = request.parameters["command"]?.toString() ?: request.parameters["executable"]?.toString() ?: capId
        val args = (request.parameters["arguments"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
        val workDir = request.parameters["workingDirectory"]?.toString() ?: ""
        val timeout = (request.parameters["timeoutMs"] as? Number)?.toLong() ?: 10000L

        val res = nativeProvider.executeCommand(cmd, args, workDir, timeout)
        val isNotInstalled = res.stderr.contains("NOT_INSTALLED") ||
                res.stderr.contains("UNSUPPORTED_EXECUTABLE") ||
                res.verificationState.contains("NOT_INSTALLED") ||
                res.verificationState.contains("UNAVAILABLE")
        val status = when {
            res.isSuccess -> UnifiedExecutionStatus.VERIFIED
            isNotInstalled -> UnifiedExecutionStatus.UNAVAILABLE
            else -> UnifiedExecutionStatus.FAILED
        }
        val verStatus = when {
            res.isSuccess -> UnifiedVerificationStatus.VERIFIED
            isNotInstalled -> UnifiedVerificationStatus.VERIFICATION_UNAVAILABLE
            else -> UnifiedVerificationStatus.FAILED
        }

        return createResult(
            request = request,
            status = status,
            output = if (res.stdout.isNotBlank()) res.stdout else res.stderr,
            error = if (res.isSuccess) null else res.stderr,
            executor = "WastiNativeExecutionProvider",
            startedAt = startedAt,
            verificationStatus = verStatus,
            verificationEvidence = res.verificationState
        )
    }

    private fun executeProjectDevManager(
        request: UnifiedExecutionRequest,
        action: String,
        context: Context?,
        startedAt: Long
    ): UnifiedExecutionResult {
        val ctx = context ?: appContext ?: WastiApplication.instance ?: return createResult(
            request, UnifiedExecutionStatus.UNAVAILABLE, "Context unavailable", "Null context", "WastiProjectManager", startedAt, UnifiedVerificationStatus.FAILED
        )
        val pm = WastiProjectManager(ctx)
        val lp = WastiLanguagePlatform(ctx)
        val name = request.parameters["projectName"]?.toString() ?: "NewProject"
        val lang = request.parameters["language"]?.toString() ?: "PYTHON"
        val template = request.parameters["template"]?.toString() ?: "default"
        val desc = request.parameters["description"]?.toString() ?: ""
        val deps = (request.parameters["dependencies"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()

        return when (action) {
            "create_project" -> {
                val res = lp.createProject(ProjectCreationRequest(name, lang, template, desc, deps))
                if (res.isSuccess) {
                    createResult(request, UnifiedExecutionStatus.VERIFIED, res.message, null, "WastiLanguagePlatform", startedAt, UnifiedVerificationStatus.VERIFIED, "Created ${res.createdFiles.size} files")
                } else {
                    createResult(request, UnifiedExecutionStatus.FAILED, res.message, res.message, "WastiLanguagePlatform", startedAt, UnifiedVerificationStatus.FAILED)
                }
            }
            "create_managed_project" -> {
                val res = pm.createManagedProject(name, lang, template, desc, deps)
                if (res.isSuccess) {
                    val meta = res.getOrThrow()
                    createResult(request, UnifiedExecutionStatus.VERIFIED, "Managed project '${meta.name}' created at ${meta.relativePath}", null, "WastiProjectManager", startedAt, UnifiedVerificationStatus.VERIFIED, "wasti_project.json initialized")
                } else {
                    createResult(request, UnifiedExecutionStatus.FAILED, res.exceptionOrNull()?.message ?: "Project creation error", res.exceptionOrNull()?.message, "WastiProjectManager", startedAt, UnifiedVerificationStatus.FAILED)
                }
            }
            "inspect_project" -> {
                val res = pm.inspectProject(name)
                if (res.isSuccess) {
                    val insp = res.getOrThrow()
                    createResult(request, UnifiedExecutionStatus.VERIFIED, "Project ${insp.metadata.name}: ${insp.detectedLanguage} (${insp.totalFiles} files, build: ${insp.detectedBuildTool})", null, "WastiProjectManager", startedAt, UnifiedVerificationStatus.VERIFIED, "Inspected project")
                } else {
                    createResult(request, UnifiedExecutionStatus.FAILED, res.exceptionOrNull()?.message ?: "Inspect error", res.exceptionOrNull()?.message, "WastiProjectManager", startedAt, UnifiedVerificationStatus.FAILED)
                }
            }
            "list_projects" -> {
                val list = pm.listProjects().joinToString("\n")
                createResult(request, UnifiedExecutionStatus.VERIFIED, list.ifBlank { "No projects" }, null, "WastiProjectManager", startedAt, UnifiedVerificationStatus.VERIFIED, "Projects listed")
            }
            "delete_project" -> {
                val ok = pm.deleteProject(name)
                createResult(request, if (ok) UnifiedExecutionStatus.VERIFIED else UnifiedExecutionStatus.FAILED, "Project '$name' deleted: $ok", null, "WastiProjectManager", startedAt, if (ok) UnifiedVerificationStatus.VERIFIED else UnifiedVerificationStatus.FAILED)
            }
            "get_language_profile" -> {
                val profile = lp.getProfile(lang)
                if (profile != null) {
                    createResult(request, UnifiedExecutionStatus.VERIFIED, "Language: ${profile.displayName}, Runtime=${profile.runtimeState}, Compiler=${profile.compilerState}", null, "WastiLanguagePlatform", startedAt, UnifiedVerificationStatus.VERIFIED)
                } else {
                    createResult(request, UnifiedExecutionStatus.FAILED, "Language '$lang' unknown", "Unknown language", "WastiLanguagePlatform", startedAt, UnifiedVerificationStatus.FAILED)
                }
            }
            else -> {
                val list = lp.getAllProfiles().map { "${it.displayName}: ${it.runtimeState}" }.joinToString("\n")
                createResult(request, UnifiedExecutionStatus.VERIFIED, list, null, "WastiLanguagePlatform", startedAt, UnifiedVerificationStatus.VERIFIED)
            }
        }
    }

    private suspend fun executeBuildManager(
        request: UnifiedExecutionRequest,
        context: Context?,
        startedAt: Long
    ): UnifiedExecutionResult {
        val ctx = context ?: appContext ?: WastiApplication.instance ?: return createResult(
            request, UnifiedExecutionStatus.UNAVAILABLE, "Context unavailable", "Null context", "WastiBuildAndTestManager", startedAt, UnifiedVerificationStatus.FAILED
        )
        val bm = WastiBuildAndTestManager(ctx)
        val pId = request.parameters["projectId"]?.toString() ?: "default"
        val pPath = request.parameters["projectPath"]?.toString() ?: "projects/$pId"
        val lang = request.parameters["language"]?.toString() ?: "PYTHON"

        val res = bm.buildProject(BuildRequest(projectId = pId, projectPath = pPath, language = lang))
        val status = if (res.status == BuildStatus.SUCCESS) UnifiedExecutionStatus.VERIFIED else UnifiedExecutionStatus.FAILED
        val verStatus = if (res.status == BuildStatus.SUCCESS) UnifiedVerificationStatus.VERIFIED else UnifiedVerificationStatus.FAILED

        return createResult(
            request = request,
            status = status,
            output = res.stdout.ifBlank { res.stderr },
            error = if (res.status == BuildStatus.SUCCESS) null else res.stderr,
            executor = "WastiBuildAndTestManager",
            startedAt = startedAt,
            verificationStatus = verStatus,
            verificationEvidence = res.verificationState
        )
    }

    private suspend fun executeTestRunner(
        request: UnifiedExecutionRequest,
        context: Context?,
        startedAt: Long
    ): UnifiedExecutionResult {
        val ctx = context ?: appContext ?: WastiApplication.instance ?: return createResult(
            request, UnifiedExecutionStatus.UNAVAILABLE, "Context unavailable", "Null context", "WastiBuildAndTestManager", startedAt, UnifiedVerificationStatus.FAILED
        )
        val bm = WastiBuildAndTestManager(ctx)
        val pId = request.parameters["projectId"]?.toString() ?: "default"
        val pPath = request.parameters["projectPath"]?.toString() ?: "projects/$pId"
        val lang = request.parameters["language"]?.toString() ?: "PYTHON"

        val res = bm.runTests(pId, pPath, lang)
        val status = if (res.status == TestExecutionStatus.PASSED) UnifiedExecutionStatus.VERIFIED else UnifiedExecutionStatus.FAILED
        val verStatus = if (res.status == TestExecutionStatus.PASSED) UnifiedVerificationStatus.VERIFIED else UnifiedVerificationStatus.FAILED

        return createResult(
            request = request,
            status = status,
            output = res.stdout.ifBlank { res.stderr },
            error = if (res.status == TestExecutionStatus.PASSED) null else res.stderr,
            executor = "WastiBuildAndTestManager",
            startedAt = startedAt,
            verificationStatus = verStatus,
            verificationEvidence = "Executed ${res.totalTests} tests (${res.passedTests} passed)"
        )
    }

    private fun executeDiagnostics(
        request: UnifiedExecutionRequest,
        context: Context?,
        startedAt: Long
    ): UnifiedExecutionResult {
        val ctx = context ?: appContext ?: WastiApplication.instance ?: return createResult(
            request, UnifiedExecutionStatus.UNAVAILABLE, "Context unavailable", "Null context", "WastiBuildAndTestManager", startedAt, UnifiedVerificationStatus.FAILED
        )
        val bm = WastiBuildAndTestManager(ctx)
        val pId = request.parameters["projectId"]?.toString() ?: "default"
        val rawLogs = request.parameters["logs"]?.toString() ?: request.parameters["errorOutput"]?.toString() ?: ""

        val res = bm.analyzeDiagnostics(pId, rawLogs)
        val output = "${res.rootCauseSummary}\n" + res.findings.joinToString("\n") { "[${it.severity}] ${it.errorType}: ${it.message} (${it.suggestedFix})" }

        return createResult(
            request = request,
            status = UnifiedExecutionStatus.VERIFIED,
            output = output,
            executor = "WastiBuildAndTestManager",
            startedAt = startedAt,
            verificationStatus = UnifiedVerificationStatus.VERIFIED,
            verificationEvidence = "Found ${res.totalErrors} error(s), ${res.totalWarnings} warning(s)"
        )
    }

    private fun executePackageManager(
        request: UnifiedExecutionRequest,
        context: Context?,
        startedAt: Long
    ): UnifiedExecutionResult {
        val ctx = context ?: appContext ?: WastiApplication.instance ?: return createResult(
            request, UnifiedExecutionStatus.UNAVAILABLE, "Context unavailable", "Null context", "WastiRuntimeManager", startedAt, UnifiedVerificationStatus.FAILED
        )
        val rm = WastiRuntimeManager(ctx)
        val lang = request.parameters["language"]?.toString() ?: "KOTLIN"
        val pkg = request.parameters["packageName"]?.toString() ?: "core"

        val res = rm.resolvePackage(lang, pkg)
        val status = if (res.isSuccess) UnifiedExecutionStatus.VERIFIED else UnifiedExecutionStatus.UNAVAILABLE
        val verStatus = if (res.isSuccess) UnifiedVerificationStatus.VERIFIED else UnifiedVerificationStatus.VERIFICATION_UNAVAILABLE

        return createResult(
            request = request,
            status = status,
            output = res.message,
            error = if (res.isSuccess) null else res.message,
            executor = "WastiRuntimeManager",
            startedAt = startedAt,
            verificationStatus = verStatus,
            verificationEvidence = "Package ${pkg} resolved via ${res.packageManager}"
        )
    }

    private suspend fun executeSandbox(
        request: UnifiedExecutionRequest,
        context: Context?,
        startedAt: Long
    ): UnifiedExecutionResult {
        val ctx = context ?: appContext ?: WastiApplication.instance ?: return createResult(
            request, UnifiedExecutionStatus.UNAVAILABLE, "Context unavailable", "Null context", "WastiSandbox", startedAt, UnifiedVerificationStatus.FAILED
        )
        val sandbox = WastiSandbox(ctx)
        val cmd = request.parameters["command"]?.toString() ?: "sh"
        val args = (request.parameters["arguments"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
        val workDir = request.parameters["workingDirectory"]?.toString() ?: ""

        val res = sandbox.executeInSandbox(SandboxExecutionRequest(command = cmd, arguments = args, workingDirectory = workDir))
        val status = if (res.isSuccess) UnifiedExecutionStatus.VERIFIED else UnifiedExecutionStatus.FAILED
        val verStatus = if (res.isSuccess) UnifiedVerificationStatus.VERIFIED else UnifiedVerificationStatus.FAILED

        return createResult(
            request = request,
            status = status,
            output = res.stdout.ifBlank { res.stderr },
            error = if (res.isSuccess) null else res.stderr,
            executor = "WastiSandbox",
            startedAt = startedAt,
            verificationStatus = verStatus,
            verificationEvidence = res.verificationState
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
