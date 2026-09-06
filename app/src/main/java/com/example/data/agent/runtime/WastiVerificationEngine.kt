package com.example.data.agent.runtime

class WastiVerificationEngine {

    fun verify(request: VerificationRequest): VerificationResult {
        val exec = request.executionResult
        val obs = request.observationResult

        if (exec.status == UnifiedExecutionStatus.FAILED ||
            exec.status == UnifiedExecutionStatus.CANCELLED
        ) {
            return VerificationResult(
                taskId = request.taskId,
                actionId = request.actionId,
                capabilityId = request.capabilityId,
                status = ActionVerificationStatus.FAILED,
                evidence = "Verification Failed: Underlying execution failed with status ${exec.status} (${exec.error ?: exec.output})",
                confidence = 1.0,
                failureReason = exec.error ?: exec.output
            )
        }

        if (exec.status == UnifiedExecutionStatus.UNAVAILABLE ||
            exec.status == UnifiedExecutionStatus.AUTHENTICATION_REQUIRED ||
            exec.status == UnifiedExecutionStatus.NOT_IMPLEMENTED
        ) {
            return VerificationResult(
                taskId = request.taskId,
                actionId = request.actionId,
                capabilityId = request.capabilityId,
                status = ActionVerificationStatus.VERIFICATION_UNAVAILABLE,
                evidence = "Verification Unavailable: Underlying execution is ${exec.status} (${exec.error ?: exec.output})",
                confidence = 0.0,
                failureReason = exec.error ?: exec.output
            )
        }

        return when (obs.status) {
            ObservationStatus.OBSERVED, ObservationStatus.CHANGED -> {
                // Enforce Independent Evidence Contract: Evidence must be non-empty and substantiate change
                val trimmedEvidence = obs.evidence.trim()
                val isGenericClaim = trimmedEvidence.equals("unknown", ignoreCase = true) ||
                    trimmedEvidence.equals("true", ignoreCase = true) ||
                    trimmedEvidence.equals("success", ignoreCase = true) ||
                    trimmedEvidence.equals("ok", ignoreCase = true) ||
                    trimmedEvidence.equals("done", ignoreCase = true) ||
                    trimmedEvidence.equals("passed", ignoreCase = true) ||
                    trimmedEvidence.length < 5

                if (trimmedEvidence.isNotBlank() && !isGenericClaim) {
                    val verifiedConfidence = if (obs.confidence > 0.0) obs.confidence else 0.85
                    VerificationResult(
                        taskId = request.taskId,
                        actionId = request.actionId,
                        capabilityId = request.capabilityId,
                        status = ActionVerificationStatus.VERIFIED,
                        evidence = "Verified: $trimmedEvidence",
                        confidence = verifiedConfidence
                    )
                } else {
                    VerificationResult(
                        taskId = request.taskId,
                        actionId = request.actionId,
                        capabilityId = request.capabilityId,
                        status = ActionVerificationStatus.FAILED,
                        evidence = "Verification Failed: Observation lacked independent structured verifiable evidence.",
                        confidence = 0.9,
                        failureReason = "Lacked independent structured verifiable evidence"
                    )
                }
            }
            ObservationStatus.NOT_OBSERVED, ObservationStatus.UNCHANGED -> {
                VerificationResult(
                    taskId = request.taskId,
                    actionId = request.actionId,
                    capabilityId = request.capabilityId,
                    status = ActionVerificationStatus.FAILED,
                    evidence = "Verification Failed: Expected state change not observed (${obs.evidence})",
                    confidence = 0.9,
                    failureReason = obs.evidence
                )
            }
            ObservationStatus.TIMEOUT -> {
                VerificationResult(
                    taskId = request.taskId,
                    actionId = request.actionId,
                    capabilityId = request.capabilityId,
                    status = ActionVerificationStatus.VERIFICATION_UNAVAILABLE,
                    evidence = "Verification Timeout: Observation timed out after threshold (${obs.evidence})",
                    confidence = 0.0,
                    failureReason = "Observation timeout"
                )
            }
            ObservationStatus.UNAVAILABLE, ObservationStatus.UNKNOWN -> {
                VerificationResult(
                    taskId = request.taskId,
                    actionId = request.actionId,
                    capabilityId = request.capabilityId,
                    status = ActionVerificationStatus.VERIFICATION_UNAVAILABLE,
                    evidence = "Verification Unavailable: State observation unavailable for capability ${request.capabilityId} (${obs.evidence})",
                    confidence = 0.0,
                    failureReason = "Observation unavailable"
                )
            }
        }
    }
}
