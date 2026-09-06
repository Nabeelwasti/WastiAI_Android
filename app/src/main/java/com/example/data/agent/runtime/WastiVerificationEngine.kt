package com.example.data.agent.runtime

class WastiVerificationEngine {

    fun isSyntheticOrMock(text: String): Boolean {
        val lower = text.lowercase()
        return lower.contains("synthetic") ||
            lower.contains("mock_evidence") ||
            lower.contains("mock") ||
            lower.contains("fake_evidence") ||
            lower.contains("fake") ||
            lower.contains("dummy_evidence") ||
            lower.contains("dummy") ||
            lower.contains("unverified_stub") ||
            lower.contains("unverified")
    }

    fun verifyStructuredEvidence(
        taskId: String,
        actionId: String,
        capabilityId: String,
        evidence: VerifiedExecutionEvidence
    ): VerificationResult {
        val isTrulyVerified = evidence.confidence >= 0.85 &&
            evidence.subject.isNotBlank() &&
            evidence.verifiedState.isNotBlank() &&
            !isSyntheticOrMock(evidence.subject) &&
            !isSyntheticOrMock(evidence.verifiedState)

        return if (isTrulyVerified) {
            VerificationResult(
                taskId = taskId,
                actionId = actionId,
                capabilityId = capabilityId,
                status = ActionVerificationStatus.VERIFIED,
                evidence = "Verified: ${evidence.evidenceSource} [${evidence.subject} -> ${evidence.verifiedState}]",
                confidence = evidence.confidence,
                structuredEvidence = evidence
            )
        } else {
            VerificationResult(
                taskId = taskId,
                actionId = actionId,
                capabilityId = capabilityId,
                status = ActionVerificationStatus.FAILED,
                evidence = "Verification Failed: Structured evidence failed integrity or non-synthetic validation",
                confidence = 1.0,
                failureReason = "Structured evidence failed validation",
                structuredEvidence = evidence
            )
        }
    }

    fun verify(request: VerificationRequest): VerificationResult {
        val exec = request.executionResult
        val obs = request.observationResult

        // If structured evidence is attached, validate it
        if (request.structuredEvidence != null) {
            val structuredRes = verifyStructuredEvidence(
                taskId = request.taskId,
                actionId = request.actionId,
                capabilityId = request.capabilityId,
                evidence = request.structuredEvidence
            )
            if (structuredRes.status == ActionVerificationStatus.FAILED) {
                return structuredRes
            }
        }

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
                failureReason = exec.error ?: exec.output,
                structuredEvidence = request.structuredEvidence
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
                failureReason = exec.error ?: exec.output,
                structuredEvidence = request.structuredEvidence
            )
        }

        return when (obs.status) {
            ObservationStatus.OBSERVED, ObservationStatus.CHANGED -> {
                val trimmedEvidence = obs.evidence.trim()
                val isGenericClaim = trimmedEvidence.equals("unknown", ignoreCase = true) ||
                    trimmedEvidence.equals("true", ignoreCase = true) ||
                    trimmedEvidence.equals("success", ignoreCase = true) ||
                    trimmedEvidence.equals("ok", ignoreCase = true) ||
                    trimmedEvidence.equals("done", ignoreCase = true) ||
                    trimmedEvidence.equals("passed", ignoreCase = true) ||
                    trimmedEvidence.length < 5

                if (isSyntheticOrMock(trimmedEvidence)) {
                    VerificationResult(
                        taskId = request.taskId,
                        actionId = request.actionId,
                        capabilityId = request.capabilityId,
                        status = ActionVerificationStatus.FAILED,
                        evidence = "Verification Failed: Observation evidence was rejected as synthetic or mock: $trimmedEvidence",
                        confidence = 1.0,
                        failureReason = "Rejected synthetic or mock evidence",
                        structuredEvidence = request.structuredEvidence
                    )
                } else if (trimmedEvidence.isNotBlank() && !isGenericClaim) {
                    val verifiedConfidence = if (obs.confidence > 0.0) obs.confidence else 0.85
                    VerificationResult(
                        taskId = request.taskId,
                        actionId = request.actionId,
                        capabilityId = request.capabilityId,
                        status = ActionVerificationStatus.VERIFIED,
                        evidence = "Verified: $trimmedEvidence",
                        confidence = verifiedConfidence,
                        structuredEvidence = request.structuredEvidence
                    )
                } else {
                    VerificationResult(
                        taskId = request.taskId,
                        actionId = request.actionId,
                        capabilityId = request.capabilityId,
                        status = ActionVerificationStatus.FAILED,
                        evidence = "Verification Failed: Observation lacked independent structured verifiable evidence.",
                        confidence = 0.9,
                        failureReason = "Lacked independent structured verifiable evidence",
                        structuredEvidence = request.structuredEvidence
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
                    failureReason = obs.evidence,
                    structuredEvidence = request.structuredEvidence
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
                    failureReason = "Observation timeout",
                    structuredEvidence = request.structuredEvidence
                )
            }
            ObservationStatus.UNAVAILABLE, ObservationStatus.UNKNOWN -> {
                if (request.structuredEvidence != null) {
                    val structuredRes = verifyStructuredEvidence(
                        taskId = request.taskId,
                        actionId = request.actionId,
                        capabilityId = request.capabilityId,
                        evidence = request.structuredEvidence
                    )
                    if (structuredRes.status == ActionVerificationStatus.VERIFIED) {
                        return structuredRes
                    }
                }
                VerificationResult(
                    taskId = request.taskId,
                    actionId = request.actionId,
                    capabilityId = request.capabilityId,
                    status = ActionVerificationStatus.VERIFICATION_UNAVAILABLE,
                    evidence = "Verification Unavailable: State observation unavailable for capability ${request.capabilityId} (${obs.evidence})",
                    confidence = 0.0,
                    failureReason = "Observation unavailable",
                    structuredEvidence = request.structuredEvidence
                )
            }
        }
    }
}
