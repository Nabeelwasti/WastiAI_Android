package com.example.data.agent.runtime

import android.content.Context
import com.example.service.WastiAccessibilityService
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.Locale

/**
 * Canonical post-execution observation layer for Wasti.
 *
 * The engine has no execution authority and stores no shadow capability state:
 * it observes the result of the single UnifiedExecutionFabric and reports the
 * evidence back to the coordinating brain. Independent read-only observations
 * can run concurrently through [observeAll].
 */
class WastiObservationEngine(
    private val realityRegistry: CapabilityRealityRegistry? = null,
    private val appContext: Context? = null,
    private val deviceObservationDelayMs: Long = DEFAULT_DEVICE_OBSERVATION_DELAY_MS
) {
    private val effectiveRegistry: CapabilityRealityRegistry
        get() = realityRegistry ?: UnifiedExecutionFabric.instance.realityRegistry

    init {
        require(deviceObservationDelayMs >= 0L) { "deviceObservationDelayMs must not be negative" }
    }

    data class ObservationWork(
        val request: ObservationRequest,
        val executorResult: UnifiedExecutionResult,
        val context: Context? = null
    )

    /** Compatibility overload for callers that keep Context on the engine. */
    suspend fun observe(
        request: ObservationRequest,
        executorResult: UnifiedExecutionResult
    ): ObservationResult = observe(request, appContext, executorResult)

    /** Compatibility overload for callers that supply Context per observation. */
    suspend fun observe(
        request: ObservationRequest,
        context: Context?,
        executorResult: UnifiedExecutionResult
    ): ObservationResult {
        observationBlockedByExecutionState(request, executorResult)?.let { return it }

        val capabilityId = normalizeCapabilityId(request.capabilityId)
        val category = effectiveRegistry.get(request.capabilityId)?.category?.uppercase(Locale.ROOT)
            ?: effectiveRegistry.get(capabilityId)?.category?.uppercase(Locale.ROOT)

        return when {
            capabilityId in FILESYSTEM_CAPABILITIES || category == "FILESYSTEM" || category == "FILES" ->
                observeFileSystem(request, effectiveContext(context), executorResult)

            capabilityId == "device_control" || category == "DEVICE_CONTROL" || category == "ANDROID_CONTROL" ->
                observeDeviceControl(request, executorResult)

            capabilityId in MEMORY_CAPABILITIES || category == "MEMORY" ->
                observeMemory(request, executorResult)

            category == "COMMUNICATION" -> observeCommunication(request, executorResult)

            else -> observeSystemAndCode(request, executorResult)
        }
    }

    /**
     * Observes independent completed actions concurrently while preserving input
     * order. Observation is read-only; writes remain exclusively in the fabric.
     */
    suspend fun observeAll(
        work: List<ObservationWork>,
        maxConcurrency: Int = DEFAULT_MAX_CONCURRENT_OBSERVATIONS
    ): List<ObservationResult> {
        require(maxConcurrency > 0) { "maxConcurrency must be positive" }
        if (work.isEmpty()) return emptyList()

        val limiter = Semaphore(maxConcurrency)
        return coroutineScope {
            work.map { item ->
                async {
                    limiter.withPermit {
                        observe(item.request, item.context ?: appContext, item.executorResult)
                    }
                }
            }.awaitAll()
        }
    }

    /**
     * A terminal execution failure does not prove that reality is unchanged:
     * a process can fail after a partial side effect. Return UNKNOWN rather than
     * making an unsafe claim that no change happened.
     */
    private fun observationBlockedByExecutionState(
        request: ObservationRequest,
        executorResult: UnifiedExecutionResult
    ): ObservationResult? {
        val statusName = executorResult.status.name
        return when {
            executorResult.status == UnifiedExecutionStatus.UNAVAILABLE -> result(
                request = request,
                status = ObservationStatus.UNAVAILABLE,
                observedState = executorResult.output,
                evidence = "Execution capability unavailable: ${executorResult.error ?: executorResult.output}",
                confidence = 0.0
            )
            executorResult.status == UnifiedExecutionStatus.FAILED ||
                executorResult.status == UnifiedExecutionStatus.CANCELLED ||
                statusName == "VERIFICATION_FAILED" -> result(
                request = request,
                status = ObservationStatus.UNKNOWN,
                observedState = executorResult.output,
                evidence = "Execution ended as $statusName; post-state may be partially changed and requires a fresh observation.",
                confidence = 0.2
            )
            statusName == "RUNNING" || statusName == "PENDING" || statusName == "SCHEDULED" -> result(
                request = request,
                status = ObservationStatus.UNKNOWN,
                observedState = executorResult.output,
                evidence = "Execution is still $statusName; final state cannot be observed yet.",
                confidence = 0.0
            )
            else -> null
        }
    }

    private fun observeFileSystem(
        request: ObservationRequest,
        context: Context?,
        executorResult: UnifiedExecutionResult
    ): ObservationResult {
        val rawPath = request.parameters["path"]?.toString()
            ?: request.parameters["file"]?.toString()
            ?: executorResult.details["path"]?.toString()

        if (rawPath.isNullOrBlank()) {
            return result(
                request = request,
                status = ObservationStatus.UNKNOWN,
                observedState = executorResult.output,
                evidence = "No target file path was supplied in request parameters or execution details.",
                confidence = 0.0
            )
        }

        val file = resolveFile(rawPath, context) ?: return result(
            request = request,
            status = ObservationStatus.UNKNOWN,
            observedState = executorResult.output,
            evidence = "Could not resolve target path '$rawPath' for observation.",
            confidence = 0.0
        )
        val snapshot = fileSnapshot(file) ?: return result(
            request = request,
            status = ObservationStatus.UNKNOWN,
            observedState = executorResult.output,
            evidence = "File metadata could not be read at '${file.path}'.",
            confidence = 0.0
        )

        return when (normalizeCapabilityId(request.capabilityId)) {
            "create_file", "write_file" -> {
                if (snapshot.exists) {
                    result(
                        request = request,
                        status = changeStatus(executorResult),
                        observedState = snapshot.describe(),
                        evidence = "Post-execution file is present at '${file.path}'. A baseline is required to prove content changed.",
                        confidence = if (isVerified(executorResult)) 1.0 else 0.9
                    )
                } else {
                    result(
                        request = request,
                        status = ObservationStatus.NOT_OBSERVED,
                        observedState = "File not found at '${file.path}'",
                        evidence = "Expected file was absent after ${request.capabilityId}.",
                        confidence = 1.0
                    )
                }
            }
            "delete_file" -> {
                if (!snapshot.exists) {
                    result(
                        request = request,
                        status = changeStatus(executorResult),
                        observedState = "File is absent from disk",
                        evidence = "Target path '${file.path}' is absent after delete request. A baseline is required to prove this action removed it.",
                        confidence = if (isVerified(executorResult)) 1.0 else 0.9
                    )
                } else {
                    result(
                        request = request,
                        status = ObservationStatus.NOT_OBSERVED,
                        observedState = snapshot.describe(),
                        evidence = "File remains at '${file.path}' after delete request.",
                        confidence = 1.0
                    )
                }
            }
            else -> result(
                request = request,
                status = if (snapshot.exists) ObservationStatus.OBSERVED else ObservationStatus.NOT_OBSERVED,
                observedState = snapshot.describe(),
                evidence = "File-system post-state inspected at '${file.path}'.",
                confidence = 1.0
            )
        }
    }

    private fun observeSystemAndCode(
        request: ObservationRequest,
        executorResult: UnifiedExecutionResult
    ): ObservationResult {
        val capabilityId = normalizeCapabilityId(request.capabilityId)
        return when (capabilityId) {
            in MEMORY_CAPABILITIES -> observeMemory(request, executorResult)

            "search_web", "web_search", "read_web_page", "b2b_xray_search" -> result(
                request = request,
                status = ObservationStatus.OBSERVED,
                observedState = executorResult.output,
                evidence = "Web result returned through the canonical execution fabric.",
                confidence = if (isExecutionSuccessful(executorResult)) 1.0 else 0.5
            )

            "system_info", "system", "inspect_environment", "environment", "status",
            "project_dev_manager", "create_project", "create_managed_project", "inspect_project",
            "list_projects", "delete_project", "project", "dev_environment",
            "navigate_to", "open_screen", "navigate" -> successfulExecutionObservation(
                request = request,
                executorResult = executorResult,
                description = "Environment, navigation, or project operation"
            )

            "build_project", "compile_project", "build", "compile", "build_manager",
            "test_project", "run_tests", "test", "test_runner",
            "debug_project", "analyze_diagnostics", "debug", "debug_diagnostics",
            "package_manager", "resolve_package", "install_package",
            "wasti_sandbox", "sandbox", "wasm", "wasm_sandbox", "wasm_runtime",
            "local_server", "start_server", "stop_server", "server_status", "server",
            "python_bridge", "termux_bridge",
            "terminal", "execute_code", "execute_command", "run_script", "sh", "cmd", "python", "node", "npm" ->
                verifiedExecutionObservation(request, executorResult, capabilityId)

            else -> {
                if (isExecutionSuccessful(executorResult)) {
                    result(
                        request = request,
                        status = ObservationStatus.UNAVAILABLE,
                        observedState = executorResult.output,
                        evidence = "Capability '${request.capabilityId}' completed, but this process has no direct post-execution observer for its external state.",
                        confidence = 0.0
                    )
                } else {
                    result(
                        request = request,
                        status = ObservationStatus.UNKNOWN,
                        observedState = executorResult.output,
                        evidence = "No observer is registered for capability '${request.capabilityId}'.",
                        confidence = 0.0
                    )
                }
            }
        }
    }

    private fun successfulExecutionObservation(
        request: ObservationRequest,
        executorResult: UnifiedExecutionResult,
        description: String
    ): ObservationResult = if (isExecutionSuccessful(executorResult)) {
        result(
            request = request,
            status = ObservationStatus.OBSERVED,
            observedState = executorResult.output,
            evidence = "$description completed through UnifiedExecutionFabric: ${executorResult.output.take(EVIDENCE_PREVIEW_LENGTH)}",
            confidence = if (isVerified(executorResult)) 1.0 else 0.8
        )
    } else {
        result(
            request = request,
            status = ObservationStatus.UNKNOWN,
            observedState = executorResult.output,
            evidence = "$description has no successful terminal execution state.",
            confidence = 0.0
        )
    }

    private fun verifiedExecutionObservation(
        request: ObservationRequest,
        executorResult: UnifiedExecutionResult,
        capabilityId: String
    ): ObservationResult = when {
        isVerified(executorResult) -> result(
            request = request,
            status = ObservationStatus.OBSERVED,
            observedState = executorResult.output,
            evidence = "Verified $capabilityId operation: ${executorResult.output.take(EVIDENCE_PREVIEW_LENGTH)}",
            confidence = 1.0
        )
        isExecutionSuccessful(executorResult) -> result(
            request = request,
            status = ObservationStatus.UNKNOWN,
            observedState = executorResult.output,
            evidence = "$capabilityId completed, but no independent verification evidence was supplied.",
            confidence = 0.4
        )
        else -> result(
            request = request,
            status = ObservationStatus.UNKNOWN,
            observedState = executorResult.output,
            evidence = "$capabilityId did not reach a successful terminal execution state.",
            confidence = 0.0
        )
    }

    private suspend fun observeDeviceControl(
        request: ObservationRequest,
        executorResult: UnifiedExecutionResult
    ): ObservationResult {
        val service = WastiAccessibilityService.instance ?: return result(
            request = request,
            status = ObservationStatus.UNAVAILABLE,
            observedState = "Accessibility Service Inactive",
            evidence = "Physical UI observation requires an active WastiAccessibilityService.",
            confidence = 0.0
        )

        val action = executorResult.details["action"]?.toString()
            ?: request.parameters["action"]?.toString().orEmpty()
        val target = executorResult.details["target"]?.toString()
            ?: request.parameters["target"]?.toString()
            ?: request.parameters["package"]?.toString().orEmpty()

        return when (action) {
            "read_screen" -> result(
                request = request,
                status = ObservationStatus.OBSERVED,
                observedState = executorResult.output,
                evidence = "Live screen content returned via the Accessibility API.",
                confidence = 1.0
            )
            "open_app" -> observeAppLaunch(request, executorResult, service, target)
            "simulate_tap", "click_element" -> {
                val latestObservation = service.latestUiObservation
                result(
                    request = request,
                    status = ObservationStatus.OBSERVED,
                    observedState = latestObservation?.text ?: executorResult.output,
                    evidence = "Accessibility action '$action' completed; latest UI event is ${latestObservation?.eventType ?: "unavailable"}.",
                    confidence = if (latestObservation == null) 0.7 else 0.9
                )
            }
            "send_whatsapp", "send_email", "send_sms" -> result(
                request = request,
                status = ObservationStatus.UNAVAILABLE,
                observedState = executorResult.output,
                evidence = "Messaging intent was dispatched, but third-party delivery state is not observable from this sandbox.",
                confidence = 0.0
            )
            else -> result(
                request = request,
                status = ObservationStatus.OBSERVED,
                observedState = executorResult.output,
                evidence = "Device-control action '${action.ifBlank { "unspecified" }}' returned through the Accessibility path.",
                confidence = 0.7
            )
        }
    }

    private suspend fun observeAppLaunch(
        request: ObservationRequest,
        executorResult: UnifiedExecutionResult,
        service: WastiAccessibilityService,
        target: String
    ): ObservationResult {
        if (deviceObservationDelayMs > 0L) delay(deviceObservationDelayMs)

        val activePackage = service.rootInActiveWindow?.packageName?.toString().orEmpty()
        val latestObservation = service.latestUiObservation
        val targetMatches = target.isNotBlank() && (
            activePackage.contains(target, ignoreCase = true) ||
                latestObservation?.packageName?.contains(target, ignoreCase = true) == true
            )

        return when {
            targetMatches -> result(
                request = request,
                status = ObservationStatus.CHANGED,
                observedState = "Target '$target' is active (window package '$activePackage')",
                evidence = "Accessibility observed an active package matching '$target'.",
                confidence = 0.95
            )
            executorResult.output.contains("dispatched", ignoreCase = true) ||
                executorResult.output.contains("launched", ignoreCase = true) -> result(
                request = request,
                status = ObservationStatus.OBSERVED,
                observedState = executorResult.output,
                evidence = "Launch intent was dispatched but the target package was not confirmed in the active window.",
                confidence = 0.75
            )
            target.isBlank() -> result(
                request = request,
                status = ObservationStatus.UNKNOWN,
                observedState = "No launch target was supplied; active package is '$activePackage'.",
                evidence = "Cannot verify an app launch without a target package or application identifier.",
                confidence = 0.0
            )
            else -> result(
                request = request,
                status = ObservationStatus.NOT_OBSERVED,
                observedState = "Target '$target' not observed; active package is '$activePackage'.",
                evidence = "Accessibility active-window state did not match the launch target.",
                confidence = 0.9
            )
        }
    }

    private fun observeMemory(
        request: ObservationRequest,
        executorResult: UnifiedExecutionResult
    ): ObservationResult = result(
        request = request,
        status = ObservationStatus.OBSERVED,
        observedState = executorResult.output,
        evidence = "Memory operation returned a result through the canonical memory capability.",
        confidence = if (isExecutionSuccessful(executorResult)) 1.0 else 0.4
    )

    private fun observeCommunication(
        request: ObservationRequest,
        executorResult: UnifiedExecutionResult
    ): ObservationResult = result(
        request = request,
        status = ObservationStatus.UNAVAILABLE,
        observedState = executorResult.output,
        evidence = "External communication was dispatched, but recipient-side delivery cannot be observed from this process.",
        confidence = 0.0
    )

    private fun effectiveContext(perObservationContext: Context?): Context? =
        perObservationContext ?: appContext

    private fun resolveFile(rawPath: String, context: Context?): File? = runCatching {
        val requested = File(rawPath)
        val resolved = if (requested.isAbsolute) requested else context?.filesDir?.let { File(it, rawPath) } ?: requested
        resolved.canonicalFile
    }.getOrNull()

    private fun fileSnapshot(file: File): FileSnapshot? = runCatching {
        FileSnapshot(
            exists = file.exists(),
            isDirectory = file.isDirectory,
            sizeBytes = if (file.exists() && file.isFile) file.length() else null,
            lastModified = if (file.exists()) file.lastModified() else null
        )
    }.getOrNull()

    private fun changeStatus(executorResult: UnifiedExecutionResult): ObservationStatus =
        if (isVerified(executorResult)) ObservationStatus.CHANGED else ObservationStatus.OBSERVED

    private fun isExecutionSuccessful(executorResult: UnifiedExecutionResult): Boolean =
        executorResult.status == UnifiedExecutionStatus.COMPLETED ||
            executorResult.status == UnifiedExecutionStatus.VERIFIED ||
            executorResult.status.name == "EXECUTOR_COMPLETED" ||
            executorResult.status.name == "OBSERVED"

    private fun isVerified(executorResult: UnifiedExecutionResult): Boolean =
        executorResult.status == UnifiedExecutionStatus.VERIFIED ||
            executorResult.verificationStatus == UnifiedVerificationStatus.VERIFIED ||
            executorResult.status.name == "OBSERVED"

    private fun normalizeCapabilityId(capabilityId: String): String =
        capabilityId.trim().lowercase(Locale.ROOT)

    private fun result(
        request: ObservationRequest,
        status: ObservationStatus,
        observedState: String,
        evidence: String,
        confidence: Double
    ): ObservationResult = ObservationResult(
        taskId = request.taskId,
        actionId = request.actionId,
        capabilityId = request.capabilityId,
        status = status,
        observedState = observedState,
        evidence = evidence,
        source = ENGINE_SOURCE,
        confidence = confidence.coerceIn(0.0, 1.0)
    )

    private data class FileSnapshot(
        val exists: Boolean,
        val isDirectory: Boolean,
        val sizeBytes: Long?,
        val lastModified: Long?
    ) {
        fun describe(): String = when {
            !exists -> "File not found"
            isDirectory -> "Directory exists"
            else -> "File exists (size: ${sizeBytes ?: 0L} bytes, lastModified: ${lastModified ?: 0L})"
        }
    }

    private companion object {
        const val ENGINE_SOURCE = "WastiObservationEngine"
        const val EVIDENCE_PREVIEW_LENGTH = 160
        const val DEFAULT_DEVICE_OBSERVATION_DELAY_MS = 200L
        const val DEFAULT_MAX_CONCURRENT_OBSERVATIONS = 4

        val FILESYSTEM_CAPABILITIES = setOf(
            "files", "read_file", "write_file", "create_file", "delete_file", "list_files"
        )
        val MEMORY_CAPABILITIES = setOf("memory_search", "memory")
    }
}
