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
            lower.contains("unverified") ||
            lower.contains("simulated") ||
            lower.contains("placeholder") ||
            lower.contains("stub") ||
            lower.contains("fabricated")
    }

    /**
     * [P0-02] Verifies canonical CapabilitySpecificEvidence against strict provenance,
     * timestamp freshness, independent executor separation, and domain rules.
     */
    fun verifyCapabilitySpecificEvidence(
        request: VerificationRequest,
        evidence: CapabilitySpecificEvidence
    ): VerificationResult {
        // 1. Provenance Integrity Checks
        if (evidence.taskId != request.taskId) {
            return VerificationResult(
                taskId = request.taskId,
                actionId = request.actionId,
                capabilityId = request.capabilityId,
                status = ActionVerificationStatus.FAILED,
                evidence = "Verification Failed: Task ID mismatch [evidence=${evidence.taskId}, request=${request.taskId}]",
                confidence = 1.0,
                failureReason = "Mismatched task ID provenance",
                capabilitySpecificEvidence = evidence
            )
        }

        if (evidence.actionId != request.actionId) {
            return VerificationResult(
                taskId = request.taskId,
                actionId = request.actionId,
                capabilityId = request.capabilityId,
                status = ActionVerificationStatus.FAILED,
                evidence = "Verification Failed: Action ID mismatch [evidence=${evidence.actionId}, request=${request.actionId}]",
                confidence = 1.0,
                failureReason = "Mismatched action ID provenance",
                capabilitySpecificEvidence = evidence
            )
        }

        val normEvidenceCap = normalizeCapability(evidence.capabilityId)
        val normRequestCap = normalizeCapability(request.capabilityId)
        if (normEvidenceCap != normRequestCap && !normEvidenceCap.contains(normRequestCap) && !normRequestCap.contains(normEvidenceCap)) {
            return VerificationResult(
                taskId = request.taskId,
                actionId = request.actionId,
                capabilityId = request.capabilityId,
                status = ActionVerificationStatus.FAILED,
                evidence = "Verification Failed: Capability ID mismatch [evidence=${evidence.capabilityId}, request=${request.capabilityId}]",
                confidence = 1.0,
                failureReason = "Mismatched capability provenance",
                capabilitySpecificEvidence = evidence
            )
        }

        // 2. Freshness / Timestamp Integrity (Rejects stale or forward-dated evidence)
        val now = System.currentTimeMillis()
        if (evidence.timestamp > now + 30000L) {
            return VerificationResult(
                taskId = request.taskId,
                actionId = request.actionId,
                capabilityId = request.capabilityId,
                status = ActionVerificationStatus.FAILED,
                evidence = "Verification Failed: Evidence timestamp is in the future (${evidence.timestamp} > $now)",
                confidence = 1.0,
                failureReason = "Forward-dated evidence rejected",
                capabilitySpecificEvidence = evidence
            )
        }
        if (now - evidence.timestamp > MAX_EVIDENCE_AGE_MS) {
            return VerificationResult(
                taskId = request.taskId,
                actionId = request.actionId,
                capabilityId = request.capabilityId,
                status = ActionVerificationStatus.FAILED,
                evidence = "Verification Failed: Evidence is stale (${now - evidence.timestamp}ms > ${MAX_EVIDENCE_AGE_MS}ms)",
                confidence = 1.0,
                failureReason = "Stale timestamp evidence",
                capabilitySpecificEvidence = evidence
            )
        }

        // 3. Synthetic & Mock Validation
        if (isSyntheticOrMock(evidence.artifactOrStateReference) ||
            isSyntheticOrMock(evidence.observedState) ||
            isSyntheticOrMock(evidence.verificationMethod) ||
            isSyntheticOrMock(evidence.executor)
        ) {
            return VerificationResult(
                taskId = request.taskId,
                actionId = request.actionId,
                capabilityId = request.capabilityId,
                status = ActionVerificationStatus.FAILED,
                evidence = "Verification Failed: Synthetic, mock, or placeholder evidence rejected",
                confidence = 1.0,
                failureReason = "Synthetic or mock evidence detected",
                capabilitySpecificEvidence = evidence
            )
        }

        // 4. Separation of Execution and Verification (Executor cannot verify itself without external probe)
        if (evidence.executor.isNotBlank() &&
            evidence.executor.equals(evidence.verifierIdentity, ignoreCase = true) &&
            evidence.observationSource == EvidenceSource.PROCESS_TELEMETRY
        ) {
            return VerificationResult(
                taskId = request.taskId,
                actionId = request.actionId,
                capabilityId = request.capabilityId,
                status = ActionVerificationStatus.FAILED,
                evidence = "Verification Failed: Executor '${evidence.executor}' cannot act as independent verifier without an external state probe",
                confidence = 1.0,
                failureReason = "Execution and verification must be independently separated",
                capabilitySpecificEvidence = evidence
            )
        }

        // 5. Confidence Threshold
        if (evidence.confidence < MIN_VERIFIED_CONFIDENCE) {
            return VerificationResult(
                taskId = request.taskId,
                actionId = request.actionId,
                capabilityId = request.capabilityId,
                status = ActionVerificationStatus.FAILED,
                evidence = "Verification Failed: Confidence ${evidence.confidence} below threshold $MIN_VERIFIED_CONFIDENCE",
                confidence = evidence.confidence,
                failureReason = "Confidence score below verification threshold",
                capabilitySpecificEvidence = evidence
            )
        }

        // 6. Capability Domain-Specific Rules
        val domainVerified = validateCapabilityDomainEvidence(evidence)
        if (!domainVerified.first) {
            return VerificationResult(
                taskId = request.taskId,
                actionId = request.actionId,
                capabilityId = request.capabilityId,
                status = ActionVerificationStatus.FAILED,
                evidence = "Verification Failed: Domain rule failed for capability '${evidence.capabilityId}': ${domainVerified.second}",
                confidence = 1.0,
                failureReason = domainVerified.second,
                capabilitySpecificEvidence = evidence
            )
        }

        return VerificationResult(
            taskId = request.taskId,
            actionId = request.actionId,
            capabilityId = request.capabilityId,
            status = ActionVerificationStatus.VERIFIED,
            evidence = "Verified independently by ${evidence.verifierIdentity} [${evidence.observationSource}: ${evidence.artifactOrStateReference} -> ${evidence.observedState}] (method=${evidence.verificationMethod})",
            confidence = evidence.confidence,
            capabilitySpecificEvidence = evidence
        )
    }

    fun verifyStructuredEvidence(
        taskId: String,
        actionId: String,
        capabilityId: String,
        evidence: VerifiedExecutionEvidence
    ): VerificationResult {
        val now = System.currentTimeMillis()
        if (evidence.observedAt > now + 30000L) {
            return VerificationResult(
                taskId = taskId,
                actionId = actionId,
                capabilityId = capabilityId,
                status = ActionVerificationStatus.FAILED,
                evidence = "Verification Failed: Evidence timestamp is in the future (${evidence.observedAt} > $now)",
                confidence = 1.0,
                failureReason = "Forward-dated evidence rejected",
                structuredEvidence = evidence
            )
        }
        if (now - evidence.observedAt > MAX_EVIDENCE_AGE_MS) {
            return VerificationResult(
                taskId = taskId,
                actionId = actionId,
                capabilityId = capabilityId,
                status = ActionVerificationStatus.FAILED,
                evidence = "Verification Failed: Evidence is stale (${now - evidence.observedAt}ms > ${MAX_EVIDENCE_AGE_MS}ms)",
                confidence = 1.0,
                failureReason = "Stale timestamp evidence",
                structuredEvidence = evidence
            )
        }

        val verifier = evidence.declaredVerifier?.trim().orEmpty()
        val method = evidence.verificationMethod?.trim().orEmpty()
        val expected = evidence.expectedPostcondition?.trim().orEmpty()
        val observed = evidence.observedResult?.trim().orEmpty()
        val subject = evidence.subject.trim()
        val verifiedState = evidence.verifiedState.trim()

        if (verifier.isBlank() || method.isBlank() || expected.isBlank() || observed.isBlank() || subject.isBlank() || verifiedState.isBlank()) {
            return VerificationResult(
                taskId = taskId,
                actionId = actionId,
                capabilityId = capabilityId,
                status = ActionVerificationStatus.FAILED,
                evidence = "Verification Failed: Missing mandatory verifier, method, or postcondition comparisons in structured evidence",
                confidence = 1.0,
                failureReason = "Incomplete objective postcondition metadata",
                structuredEvidence = evidence
            )
        }

        // Sole Verification Authority check
        val isAuthoritativeVerifier = verifier == "WastiVerificationEngine" || verifier.startsWith("WastiVerificationEngine")
        if (!isAuthoritativeVerifier) {
            return VerificationResult(
                taskId = taskId,
                actionId = actionId,
                capabilityId = capabilityId,
                status = ActionVerificationStatus.FAILED,
                evidence = "Verification Failed: Non-authoritative verifier '$verifier' cannot produce verified truth",
                confidence = 1.0,
                failureReason = "Unauthorized verifier identity",
                structuredEvidence = evidence
            )
        }

        // Synthetic / Mock detection
        if (isSyntheticOrMock(verifier) || isSyntheticOrMock(method) ||
            isSyntheticOrMock(expected) || isSyntheticOrMock(observed) ||
            isSyntheticOrMock(subject) || isSyntheticOrMock(verifiedState)
        ) {
            return VerificationResult(
                taskId = taskId,
                actionId = actionId,
                capabilityId = capabilityId,
                status = ActionVerificationStatus.FAILED,
                evidence = "Verification Failed: Synthetic or mock evidence rejected",
                confidence = 1.0,
                failureReason = "Synthetic or mock evidence detected",
                structuredEvidence = evidence
            )
        }

        // Process telemetry alone without an independent domain probe cannot certify postcondition verification
        if (evidence.evidenceSource == EvidenceSource.PROCESS_TELEMETRY) {
            return VerificationResult(
                taskId = taskId,
                actionId = actionId,
                capabilityId = capabilityId,
                status = ActionVerificationStatus.NOT_VERIFIABLE,
                evidence = "Verification Unavailable: Process telemetry alone without an independent external domain probe cannot certify postcondition verification",
                confidence = 0.0,
                failureReason = "Process telemetry unprobed",
                structuredEvidence = evidence
            )
        }

        // Generic claims rejected
        val lowerExpected = expected.lowercase()
        val lowerObserved = observed.lowercase()
        val genericPlaceholders = setOf("true", "success", "ok", "passed", "done", "http_200", "http 200", "200 ok", "process_exit_0", "process_exit_0_stdout_observed", "verified_state")
        if (genericPlaceholders.contains(lowerExpected) || genericPlaceholders.contains(lowerObserved)) {
            return VerificationResult(
                taskId = taskId,
                actionId = actionId,
                capabilityId = capabilityId,
                status = ActionVerificationStatus.FAILED,
                evidence = "Verification Failed: Generic placeholder postconditions cannot certify verification",
                confidence = 1.0,
                failureReason = "Generic placeholder postconditions rejected",
                structuredEvidence = evidence
            )
        }

        // Confidence check
        if (evidence.confidence < MIN_VERIFIED_CONFIDENCE) {
            return VerificationResult(
                taskId = taskId,
                actionId = actionId,
                capabilityId = capabilityId,
                status = ActionVerificationStatus.FAILED,
                evidence = "Verification Failed: Confidence ${evidence.confidence} below threshold $MIN_VERIFIED_CONFIDENCE",
                confidence = evidence.confidence,
                failureReason = "Confidence score below verification threshold",
                structuredEvidence = evidence
            )
        }

        // Real comparison of expected postcondition vs observed result
        val matches = expected == observed ||
            observed.equals(expected, ignoreCase = true) ||
            (observed.length >= 8 && expected.length >= 8 && (observed.contains(expected, ignoreCase = true) || expected.contains(observed, ignoreCase = true)))

        if (!matches) {
            return VerificationResult(
                taskId = taskId,
                actionId = actionId,
                capabilityId = capabilityId,
                status = ActionVerificationStatus.FAILED,
                evidence = "Verification Failed: Expected postcondition does not match observed result [expected=$expected, observed=$observed]",
                confidence = 1.0,
                failureReason = "Postcondition mismatch",
                structuredEvidence = evidence
            )
        }

        return VerificationResult(
            taskId = taskId,
            actionId = actionId,
            capabilityId = capabilityId,
            status = ActionVerificationStatus.VERIFIED,
            evidence = "Verified by WastiVerificationEngine: ${evidence.evidenceSource} [$subject -> $observed] via $method",
            confidence = evidence.confidence,
            structuredEvidence = evidence
        )
    }

    fun verify(
        evidence: CapabilitySpecificEvidence,
        domain: CapabilityVerificationDomain = CapabilityVerificationDomain.GENERAL_COMPUTATION,
        maxAllowedAgeMs: Long = 60_000L
    ): VerificationResult {
        val req = VerificationRequest(
            taskId = evidence.taskId,
            actionId = evidence.actionId,
            capabilityId = evidence.capabilityId,
            expectedOutcome = evidence.expectedState,
            executionResult = UnifiedExecutionResult(
                taskId = evidence.taskId,
                actionId = evidence.actionId,
                capabilityId = evidence.capabilityId,
                status = UnifiedExecutionStatus.COMPLETED,
                output = evidence.observedState,
                executor = evidence.executor,
                startedAt = evidence.timestamp,
                completedAt = evidence.timestamp,
                verificationStatus = UnifiedVerificationStatus.UNVERIFIED
            ),
            observationResult = ObservationResult(
                taskId = evidence.taskId,
                actionId = evidence.actionId,
                capabilityId = evidence.capabilityId,
                status = ObservationStatus.OBSERVED,
                observedState = evidence.observedState,
                evidence = evidence.observedState,
                timestamp = evidence.timestamp,
                source = evidence.observationSource.name
            ),
            capabilitySpecificEvidence = evidence
        )
        return verifyCapabilitySpecificEvidence(req, evidence)
    }

    fun verify(request: VerificationRequest): VerificationResult {
        // 1. If canonical CapabilitySpecificEvidence is supplied, verify it first
        if (request.capabilitySpecificEvidence != null) {
            val capRes = verifyCapabilitySpecificEvidence(request, request.capabilitySpecificEvidence)
            if (capRes.status == ActionVerificationStatus.FAILED || capRes.status == ActionVerificationStatus.VERIFIED) {
                return capRes
            }
        }

        // 2. If legacy structured evidence is attached, validate it
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
            if (structuredRes.status == ActionVerificationStatus.VERIFIED) {
                return structuredRes
            }
        }

        val exec = request.executionResult
        val obs = request.observationResult

        // 3. Underlying execution failure or cancellation
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
                structuredEvidence = request.structuredEvidence,
                capabilitySpecificEvidence = request.capabilitySpecificEvidence
            )
        }

        // 4. Underlying execution unavailable or not implemented
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
                structuredEvidence = request.structuredEvidence,
                capabilitySpecificEvidence = request.capabilitySpecificEvidence
            )
        }

        // 5. Observation Result Validation
        return when (obs.status) {
            ObservationStatus.OBSERVED, ObservationStatus.CHANGED -> {
                val trimmedEvidence = obs.evidence.trim()
                val isGenericClaim = trimmedEvidence.equals("unknown", ignoreCase = true) ||
                    trimmedEvidence.equals("true", ignoreCase = true) ||
                    trimmedEvidence.equals("success", ignoreCase = true) ||
                    trimmedEvidence.equals("ok", ignoreCase = true) ||
                    trimmedEvidence.equals("done", ignoreCase = true) ||
                    trimmedEvidence.equals("passed", ignoreCase = true) ||
                    trimmedEvidence.equals("http_200", ignoreCase = true) ||
                    trimmedEvidence.equals("http 200", ignoreCase = true) ||
                    trimmedEvidence.equals("200 ok", ignoreCase = true) ||
                    trimmedEvidence.equals("http_200 ok", ignoreCase = true) ||
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
                        structuredEvidence = request.structuredEvidence,
                        capabilitySpecificEvidence = request.capabilitySpecificEvidence
                    )
                } else if (isGenericClaim) {
                    VerificationResult(
                        taskId = request.taskId,
                        actionId = request.actionId,
                        capabilityId = request.capabilityId,
                        status = ActionVerificationStatus.FAILED,
                        evidence = "Verification Failed: Observation contained only generic claim without independent verifiable state: $trimmedEvidence",
                        confidence = 0.95,
                        failureReason = "Generic unanchored observation claim",
                        structuredEvidence = request.structuredEvidence,
                        capabilitySpecificEvidence = request.capabilitySpecificEvidence
                    )
                } else {
                    // Freeform observation text without authoritative CapabilitySpecificEvidence or StructuredEvidence cannot self-certify verification
                    VerificationResult(
                        taskId = request.taskId,
                        actionId = request.actionId,
                        capabilityId = request.capabilityId,
                        status = ActionVerificationStatus.NOT_VERIFIABLE,
                        evidence = "Verification Unavailable: Observation lacks independent authoritative postcondition verification proof ($trimmedEvidence). Requires structured probe or authoritative verification evidence.",
                        confidence = 0.0,
                        failureReason = null,
                        structuredEvidence = request.structuredEvidence,
                        capabilitySpecificEvidence = request.capabilitySpecificEvidence
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
                    structuredEvidence = request.structuredEvidence,
                    capabilitySpecificEvidence = request.capabilitySpecificEvidence
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
                    structuredEvidence = request.structuredEvidence,
                    capabilitySpecificEvidence = request.capabilitySpecificEvidence
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
                    failureReason = null,
                    structuredEvidence = request.structuredEvidence,
                    capabilitySpecificEvidence = request.capabilitySpecificEvidence
                )
            }
        }
    }

    private fun validateCapabilityDomainEvidence(evidence: CapabilitySpecificEvidence): Pair<Boolean, String> {
        val cap = normalizeCapability(evidence.capabilityId)
        return when {
            cap.contains("file") || cap.contains("workspace") -> {
                if (evidence.observationSource != EvidenceSource.FILESYSTEM) {
                    false to "Filesystem capability requires FILESYSTEM observation source"
                } else if (evidence.artifactOrStateReference.isBlank()) {
                    false to "Filesystem evidence must specify target file/dir path"
                } else {
                    true to "OK"
                }
            }
            cap.contains("database") || cap.contains("memory") -> {
                if (evidence.observationSource != EvidenceSource.DATABASE_QUERY && evidence.observationSource != EvidenceSource.PROCESS_TELEMETRY) {
                    false to "Database capability requires DATABASE_QUERY or PROCESS_TELEMETRY observation source"
                } else if (evidence.artifactOrStateReference.isBlank()) {
                    false to "Database evidence must specify entity or query target"
                } else {
                    true to "OK"
                }
            }
            cap.contains("neural") || cap.contains("llama") || cap.contains("model") -> {
                if (evidence.observationSource != EvidenceSource.LOCAL_MODEL_INFERENCE) {
                    false to "Local model capability requires LOCAL_MODEL_INFERENCE observation source"
                } else if (evidence.artifactOrStateReference.isBlank()) {
                    false to "Local model evidence must specify modelId and artifact reference"
                } else if (evidence.expectedState.isBlank() || evidence.observedState.isBlank()) {
                    false to "Local model evidence requires non-blank expected and observed state specifications"
                } else if (evidence.executor.isNotBlank() && evidence.verifierIdentity.isNotBlank() && evidence.executor.equals(evidence.verifierIdentity, ignoreCase = true)) {
                    false to "Executor and verifier cannot be identical for neural model verification (executor/verifier separation required)"
                } else if (evidence.observedState.contains("HEURISTIC", ignoreCase = true) || evidence.observedState.contains("UNVERIFIED", ignoreCase = true)) {
                    false to "Heuristic or unverified state cannot satisfy neural model verification contract"
                } else {
                    true to "OK"
                }
            }
            cap.contains("network") || cap.contains("http") || cap.contains("cloud") -> {
                if (evidence.observationSource != EvidenceSource.HTTP_CONTRACT) {
                    false to "Network capability requires HTTP_CONTRACT observation source"
                } else if (evidence.observedState.trim().equals("HTTP_200", ignoreCase = true) ||
                    evidence.observedState.trim().equals("HTTP 200", ignoreCase = true) ||
                    evidence.observedState.trim().equals("200 OK", ignoreCase = true) ||
                    evidence.observedState.trim().equals("HTTP_200 OK", ignoreCase = true)
                ) {
                    false to "Plain HTTP_200 alone does not verify real-world side-effects or state mutation"
                } else {
                    true to "OK"
                }
            }
            else -> true to "OK"
        }
    }

    private fun normalizeCapability(id: String): String =
        id.lowercase().replace("-", "_").trim()

    companion object {
        const val MIN_VERIFIED_CONFIDENCE = 0.85
        const val MAX_EVIDENCE_AGE_MS = 300000L // 5 minutes max evidence age
    }
}
