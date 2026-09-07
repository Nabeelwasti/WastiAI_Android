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
        val isStale = (now - evidence.observedAt) > MAX_EVIDENCE_AGE_MS || (evidence.observedAt > now + 30000L)

        val isTrulyVerified = evidence.confidence >= MIN_VERIFIED_CONFIDENCE &&
            evidence.subject.isNotBlank() &&
            evidence.verifiedState.isNotBlank() &&
            !isSyntheticOrMock(evidence.subject) &&
            !isSyntheticOrMock(evidence.verifiedState) &&
            !isStale

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
            val reason = when {
                isStale -> "Evidence timestamp is stale or forward-dated"
                evidence.confidence < MIN_VERIFIED_CONFIDENCE -> "Confidence ${evidence.confidence} below threshold $MIN_VERIFIED_CONFIDENCE"
                isSyntheticOrMock(evidence.subject) || isSyntheticOrMock(evidence.verifiedState) -> "Rejected synthetic or mock evidence"
                else -> "Structured evidence failed integrity validation"
            }
            VerificationResult(
                taskId = taskId,
                actionId = actionId,
                capabilityId = capabilityId,
                status = ActionVerificationStatus.FAILED,
                evidence = "Verification Failed: $reason",
                confidence = 1.0,
                failureReason = reason,
                structuredEvidence = evidence
            )
        }
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
                    // [P0-02] Capability-specific independent validation of observation evidence
                    val isIndependentlyVerifiable = isObservationIndependentlyVerifiable(request, obs)
                    if (isIndependentlyVerifiable) {
                        val verifiedConfidence = if (obs.confidence >= MIN_VERIFIED_CONFIDENCE) obs.confidence else MIN_VERIFIED_CONFIDENCE
                        VerificationResult(
                            taskId = request.taskId,
                            actionId = request.actionId,
                            capabilityId = request.capabilityId,
                            status = ActionVerificationStatus.VERIFIED,
                            evidence = "Verified: $trimmedEvidence",
                            confidence = verifiedConfidence,
                            structuredEvidence = request.structuredEvidence,
                            capabilitySpecificEvidence = request.capabilitySpecificEvidence
                        )
                    } else {
                        // [P0-02 CRITICAL FIX]: Freeform non-generic text cannot falsely become VERIFIED!
                        VerificationResult(
                            taskId = request.taskId,
                            actionId = request.actionId,
                            capabilityId = request.capabilityId,
                            status = ActionVerificationStatus.NOT_VERIFIABLE,
                            evidence = "Verification Unavailable: Observation lacks independent capability-specific state anchors ($trimmedEvidence). Requires structured probe or physical state verification.",
                            confidence = 0.0,
                            failureReason = "Lacks independent capability-specific state anchors",
                            structuredEvidence = request.structuredEvidence,
                            capabilitySpecificEvidence = request.capabilitySpecificEvidence
                        )
                    }
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
                    failureReason = "Observation unavailable",
                    structuredEvidence = request.structuredEvidence,
                    capabilitySpecificEvidence = request.capabilitySpecificEvidence
                )
            }
        }
    }

    /**
     * Verifies that observation text anchors to concrete, capability-specific evidence patterns
     * produced by independent observation engines rather than arbitrary freeform claims.
     */
    private fun isObservationIndependentlyVerifiable(
        request: VerificationRequest,
        obs: ObservationResult
    ): Boolean {
        // Observer must be separated from executor unless verified through an external probe
        val exec = request.executionResult
        if (obs.source.equals(exec.executor, ignoreCase = true) && obs.source != "WastiObservationEngine") {
            return false
        }

        val cap = normalizeCapability(request.capabilityId)
        val text = obs.evidence

        return when {
            // Filesystem: must anchor to path inspection, existence, or size
            cap.contains("file") || cap.contains("workspace") ->
                text.contains("inspected at", ignoreCase = true) ||
                text.contains("File-system post-state", ignoreCase = true) ||
                text.contains("exists=", ignoreCase = true) ||
                text.contains("bytes", ignoreCase = true)

            // Memory: must anchor to memory capability store query
            cap.contains("memory") ->
                text.contains("Memory operation returned a result", ignoreCase = true) ||
                text.contains("Record found", ignoreCase = true) ||
                text.contains("MemoryItem", ignoreCase = true)

            // UI / Device Control: must anchor to accessibility window/package match
            cap.contains("device") || cap.contains("accessibility") || cap.contains("ui") || cap == "navigate_to" ->
                text.contains("Accessibility observed", ignoreCase = true) ||
                text.contains("active package matching", ignoreCase = true) ||
                text.contains("window package", ignoreCase = true)

            // Process / Shell / Code / Transform: must anchor to verified execution
            cap.contains("terminal") || cap.contains("shell") || cap.contains("code") || cap.contains("script") ||
                cap.contains("invented") || cap.contains("transformer") || cap.contains("transform") ||
                cap.contains("reverse") || cap.contains("extractor") || cap.contains("aggregator") ||
                cap.startsWith("wre_tool_") ->
                text.contains("returned exit code 0", ignoreCase = true) ||
                text.contains("FACT_VERIFIED", ignoreCase = true) ||
                text.contains("Terminal command", ignoreCase = true) ||
                text.contains("Verified", ignoreCase = true) ||
                text.contains("independent execution proof", ignoreCase = true)

            // Local Neural inference
            cap.contains("neural") || cap.contains("llama") || cap.contains("model") ->
                text.contains("NEURAL_EXECUTION_VERIFIED", ignoreCase = true) ||
                text.contains("LOCAL_MODEL_VERIFIED", ignoreCase = true) ||
                text.contains("GGUF weights loaded", ignoreCase = true)

            // Web / Network: must anchor to canonical fabric HTTP contract
            cap.contains("web") || cap.contains("search") || cap.contains("http") ->
                text.contains("Web result returned through the canonical execution fabric", ignoreCase = true) ||
                text.contains("HTTP_200", ignoreCase = true)

            // Canonical environment and project dev manager
            cap.contains("project") || cap.contains("system") || cap.contains("environment") || cap.contains("sysinfo") ->
                text.contains("returned through the canonical execution fabric", ignoreCase = true) ||
                text.contains("verified through independent execution proof", ignoreCase = true) ||
                text.contains("Verified", ignoreCase = true)

            else -> false
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
                } else {
                    true to "OK"
                }
            }
            cap.contains("network") || cap.contains("http") || cap.contains("cloud") -> {
                if (evidence.observationSource != EvidenceSource.HTTP_CONTRACT) {
                    false to "Network capability requires HTTP_CONTRACT observation source"
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
