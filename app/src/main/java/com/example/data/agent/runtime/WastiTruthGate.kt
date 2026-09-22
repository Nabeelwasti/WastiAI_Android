package com.example.data.agent.runtime

/**
 * Public Truth Gate boundary for Wasti AI OS.
 * All verification requests and receipt validations across all engines MUST flow through this gate.
 */
object WastiTruthGate {

    /**
     * Evaluates capability-specific evidence through [WastiTruthAuthority].
     */
    fun verifyCapability(evidence: CapabilitySpecificEvidence): Pair<VerificationResult, WastiVerificationReceipt?> {
        return WastiTruthAuthority.evaluate(
            taskId = evidence.taskId,
            actionId = evidence.actionId,
            capabilityId = evidence.capabilityId,
            expectedState = evidence.expectedState,
            observedState = evidence.observedState,
            observationSource = evidence.observationSource,
            verifierIdentity = evidence.verifierIdentity,
            verificationMethod = evidence.verificationMethod,
            confidence = evidence.confidence,
            artifactRef = evidence.artifactOrStateReference,
            checksum = evidence.checksumOrHash
        )
    }

    /**
     * Evaluates structured execution evidence through [WastiTruthAuthority].
     */
    fun verifyStructured(
        taskId: String,
        actionId: String,
        capabilityId: String,
        evidence: VerifiedExecutionEvidence
    ): Pair<VerificationResult, WastiVerificationReceipt?> {
        return WastiTruthAuthority.evaluate(
            taskId = taskId,
            actionId = actionId,
            capabilityId = capabilityId,
            expectedState = evidence.expectedPostcondition ?: evidence.verifiedState,
            observedState = evidence.observedResult ?: evidence.verifiedState,
            observationSource = evidence.evidenceSource,
            verifierIdentity = evidence.declaredVerifier ?: "WastiVerificationEngine",
            verificationMethod = evidence.verificationMethod ?: "structured_evidence_probe",
            confidence = evidence.confidence,
            artifactRef = evidence.subject,
            checksum = evidence.checksumOrHash
        )
    }

    /**
     * Evaluates raw state evidence parameters through [WastiTruthAuthority].
     */
    fun evaluateRaw(
        taskId: String,
        actionId: String,
        capabilityId: String,
        expectedState: String,
        observedState: String,
        observationSource: EvidenceSource,
        verifierIdentity: String = "WastiVerificationEngine",
        verificationMethod: String = "state_probe",
        confidence: Double = 0.95
    ): Pair<VerificationResult, WastiVerificationReceipt?> {
        return WastiTruthAuthority.evaluate(
            taskId = taskId,
            actionId = actionId,
            capabilityId = capabilityId,
            expectedState = expectedState,
            observedState = observedState,
            observationSource = observationSource,
            verifierIdentity = verifierIdentity,
            verificationMethod = verificationMethod,
            confidence = confidence
        )
    }

    /**
     * Validates whether a given receipt is authentic and issued by [WastiTruthAuthority].
     */
    fun validateReceipt(receipt: WastiVerificationReceipt?): Boolean {
        return WastiTruthAuthority.validateReceipt(receipt)
    }
}
