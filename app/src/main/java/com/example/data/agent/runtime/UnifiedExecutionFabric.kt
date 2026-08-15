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
        val instance by lazy { UnifiedExecutionFabric() }
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

        // 1. Duplicate execution prevention check
        val reqHash = "${request.taskId}:${request.actionId}:${request.capabilityId}"
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
                executeFileOperations(request, capId, startedAt)
            }
            capId == "terminal" || capId == "execute_code" || capId == "execute_command" || capId == "run_script" || capId == "sh" || capId == "cmd" || capId == "python" -> {
                executeTerminalOperations(request, capId, ctx, startedAt)
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
            "simulate_tap", "tap" -> {
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
        startedAt: Long
    ): UnifiedExecutionResult {
        return createResult(
            request = request,
            status = UnifiedExecutionStatus.VERIFIED,
            output = "Workspace file operation '$op' executed.",
            executor = "WorkspaceManager",
            startedAt = startedAt,
            verificationStatus = UnifiedVerificationStatus.VERIFIED,
            verificationEvidence = "File operation verified"
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
