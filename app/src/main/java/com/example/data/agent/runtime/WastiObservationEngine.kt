package com.example.data.agent.runtime

import android.content.Context
import com.example.service.WastiAccessibilityService
import kotlinx.coroutines.delay

class WastiObservationEngine {

    suspend fun observe(
        request: ObservationRequest,
        context: Context?,
        executorResult: UnifiedExecutionResult
    ): ObservationResult {
        if (executorResult.status == UnifiedExecutionStatus.UNAVAILABLE) {
            return ObservationResult(
                taskId = request.taskId,
                actionId = request.actionId,
                capabilityId = request.capabilityId,
                status = ObservationStatus.UNAVAILABLE,
                observedState = executorResult.output,
                evidence = "Execution unavailable (${executorResult.status}): ${executorResult.error ?: executorResult.output}",
                source = "WastiObservationEngine",
                confidence = 0.0
            )
        }

        if (executorResult.status == UnifiedExecutionStatus.FAILED ||
            executorResult.status == UnifiedExecutionStatus.CANCELLED
        ) {
            return ObservationResult(
                taskId = request.taskId,
                actionId = request.actionId,
                capabilityId = request.capabilityId,
                status = ObservationStatus.UNCHANGED,
                observedState = executorResult.output,
                evidence = "Execution did not complete successfully (${executorResult.status}): ${executorResult.error ?: executorResult.output}",
                source = "WastiObservationEngine",
                confidence = 1.0
            )
        }

        return when (request.capabilityId) {
            "device_control" -> observeDeviceControl(request, executorResult)
            "memory_search" -> ObservationResult(
                taskId = request.taskId,
                actionId = request.actionId,
                capabilityId = request.capabilityId,
                status = ObservationStatus.OBSERVED,
                observedState = executorResult.output,
                evidence = "Memory query returned query results directly from store.",
                confidence = 1.0
            )
            "files" -> ObservationResult(
                taskId = request.taskId,
                actionId = request.actionId,
                capabilityId = request.capabilityId,
                status = ObservationStatus.OBSERVED,
                observedState = executorResult.output,
                evidence = "File operation result observed.",
                confidence = 1.0
            )
            "search_web" -> ObservationResult(
                taskId = request.taskId,
                actionId = request.actionId,
                capabilityId = request.capabilityId,
                status = ObservationStatus.OBSERVED,
                observedState = executorResult.output,
                evidence = "Web search result observed.",
                confidence = 1.0
            )
            else -> {
                if (executorResult.status == UnifiedExecutionStatus.COMPLETED) {
                    ObservationResult(
                        taskId = request.taskId,
                        actionId = request.actionId,
                        capabilityId = request.capabilityId,
                        status = ObservationStatus.UNAVAILABLE,
                        observedState = executorResult.output,
                        evidence = "External app capability (${request.capabilityId}) dispatched intent. Direct post-execution state inspection unavailable due to sandbox boundary.",
                        confidence = 0.0
                    )
                } else {
                    ObservationResult(
                        taskId = request.taskId,
                        actionId = request.actionId,
                        capabilityId = request.capabilityId,
                        status = ObservationStatus.UNKNOWN,
                        observedState = executorResult.output,
                        evidence = "Observation unavailable for capability ${request.capabilityId}",
                        confidence = 0.0
                    )
                }
            }
        }
    }

    private suspend fun observeDeviceControl(
        request: ObservationRequest,
        executorResult: UnifiedExecutionResult
    ): ObservationResult {
        val service = WastiAccessibilityService.instance
        if (service == null) {
            return ObservationResult(
                taskId = request.taskId,
                actionId = request.actionId,
                capabilityId = request.capabilityId,
                status = ObservationStatus.UNAVAILABLE,
                observedState = "Accessibility Service Inactive",
                evidence = "Accessibility Service inactive; physical device state observation unavailable in this environment.",
                confidence = 0.0
            )
        }

        val action = executorResult.details["action"] ?: ""
        val target = executorResult.details["target"] ?: ""

        return when (action) {
            "read_screen" -> {
                ObservationResult(
                    taskId = request.taskId,
                    actionId = request.actionId,
                    capabilityId = request.capabilityId,
                    status = ObservationStatus.OBSERVED,
                    observedState = executorResult.output,
                    evidence = "Live screen contents scraped and observed via Accessibility API.",
                    confidence = 1.0
                )
            }
            "open_app" -> {
                delay(200L)
                val activePackage = service.rootInActiveWindow?.packageName?.toString() ?: ""
                val activeObs = service.latestUiObservation
                if (activePackage.contains(target, ignoreCase = true) ||
                    (activeObs?.packageName?.contains(target, ignoreCase = true) == true)
                ) {
                    ObservationResult(
                        taskId = request.taskId,
                        actionId = request.actionId,
                        capabilityId = request.capabilityId,
                        status = ObservationStatus.CHANGED,
                        observedState = "App package '$target' is active in window ($activePackage)",
                        evidence = "Accessibility Service observed active window package matching target '$target'",
                        confidence = 0.95
                    )
                } else if (executorResult.output.contains("dispatched", ignoreCase = true) ||
                    executorResult.output.contains("launched", ignoreCase = true)
                ) {
                    ObservationResult(
                        taskId = request.taskId,
                        actionId = request.actionId,
                        capabilityId = request.capabilityId,
                        status = ObservationStatus.OBSERVED,
                        observedState = executorResult.output,
                        evidence = "App launch intent dispatched successfully.",
                        confidence = 0.8
                    )
                } else {
                    ObservationResult(
                        taskId = request.taskId,
                        actionId = request.actionId,
                        capabilityId = request.capabilityId,
                        status = ObservationStatus.NOT_OBSERVED,
                        observedState = "Target package '$target' not observed in active window (active: '$activePackage')",
                        evidence = "Active window package '$activePackage' does not match target '$target'",
                        confidence = 0.9
                    )
                }
            }
            "simulate_tap", "click_element" -> {
                val latestObs = service.latestUiObservation
                ObservationResult(
                    taskId = request.taskId,
                    actionId = request.actionId,
                    capabilityId = request.capabilityId,
                    status = ObservationStatus.OBSERVED,
                    observedState = latestObs?.text ?: executorResult.output,
                    evidence = "Tap executed via Accessibility API. Latest observed event: ${latestObs?.eventType}",
                    confidence = 0.85
                )
            }
            "send_whatsapp", "send_email", "send_sms" -> {
                ObservationResult(
                    taskId = request.taskId,
                    actionId = request.actionId,
                    capabilityId = request.capabilityId,
                    status = ObservationStatus.UNAVAILABLE,
                    observedState = executorResult.output,
                    evidence = "Messaging intent dispatched. Direct delivery verification in third-party app unavailable.",
                    confidence = 0.0
                )
            }
            else -> {
                ObservationResult(
                    taskId = request.taskId,
                    actionId = request.actionId,
                    capabilityId = request.capabilityId,
                    status = ObservationStatus.OBSERVED,
                    observedState = executorResult.output,
                    evidence = "Device control action '$action' observed.",
                    confidence = 0.8
                )
            }
        }
    }
}
