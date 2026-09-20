package com.example.data.agent.runtime

import java.util.UUID

enum class ObservationStrategy { SCREEN_SCRAPE, ACCESSIBILITY_EVENT, DIRECT_QUERY, TIMED_SNAPSHOT, NONE }
enum class ObservationStatus { OBSERVED, NOT_OBSERVED, CHANGED, UNCHANGED, UNKNOWN, TIMEOUT, UNAVAILABLE }

data class ObservationRequest(
    val taskId: String = UUID.randomUUID().toString(),
    val actionId: String = UUID.randomUUID().toString(),
    val capabilityId: String,
    val parameters: Map<String, Any?> = emptyMap(),
    val expectedOutcome: String = "",
    val observationStrategy: ObservationStrategy = ObservationStrategy.SCREEN_SCRAPE,
    val timeoutMs: Long = 5000L,
    val correlationId: String? = null
)

data class ObservationResult(
    val taskId: String,
    val actionId: String,
    val capabilityId: String,
    val status: ObservationStatus,
    val observedState: String,
    val evidence: String,
    val timestamp: Long = System.currentTimeMillis(),
    val source: String = "WastiObservationEngine",
    val confidence: Double = 0.0,
    val error: String? = null
)

enum class ActionVerificationStatus { VERIFIED, FAILED, UNKNOWN, NOT_VERIFIABLE, VERIFICATION_UNAVAILABLE }

/** Canonical capability-specific independent evidence schema. */
data class CapabilitySpecificEvidence(
    val taskId: String,
    val actionId: String,
    val capabilityId: String,
    val executor: String,
    val observationSource: EvidenceSource,
    val timestamp: Long = System.currentTimeMillis(),
    val artifactOrStateReference: String,
    val checksumOrHash: String? = null,
    val expectedState: String,
    val observedState: String,
    val verifierIdentity: String = "WastiVerificationEngine",
    val verificationMethod: String,
    val confidence: Double = 1.0
)

data class VerificationRequest(
    val taskId: String,
    val actionId: String,
    val capabilityId: String,
    val expectedOutcome: String = "",
    val executionResult: UnifiedExecutionResult,
    val observationResult: ObservationResult,
    val structuredEvidence: VerifiedExecutionEvidence? = null,
    val capabilitySpecificEvidence: CapabilitySpecificEvidence? = null
)

data class VerificationResult(
    val taskId: String,
    val actionId: String,
    val capabilityId: String,
    val status: ActionVerificationStatus,
    val evidence: String,
    val confidence: Double = 0.0,
    val failureReason: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val structuredEvidence: VerifiedExecutionEvidence? = null,
    val capabilitySpecificEvidence: CapabilitySpecificEvidence? = null,
    val evidenceLevel: EvidenceLadder = if (status == ActionVerificationStatus.VERIFIED) EvidenceLadder.RUNTIME_VERIFIED else EvidenceLadder.IMPLEMENTED
) {
    val isVerified: Boolean get() = status == ActionVerificationStatus.VERIFIED
    val explanation: String get() = failureReason ?: evidence
}

data class StructuredUiObservation(
    val packageName: String? = null,
    val className: String? = null,
    val text: String? = null,
    val contentDescription: String? = null,
    val resourceId: String? = null,
    val clickable: Boolean = false,
    val enabled: Boolean = true,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val bounds: String? = null,
    val nodeCount: Int = 0,
    val eventType: Int = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val correlationId: String? = null
)

enum class TargetMatchRank { EXACT_RESOURCE_ID, EXACT_NORMALIZED_TEXT, EXACT_CONTENT_DESCRIPTION, NORMALIZED_EXACT_MATCH, PARTIAL_MATCH, COORDINATE_MATCH, NO_MATCH }
enum class TargetSelectionStatus { MATCHED, AMBIGUOUS, NOT_FOUND }
data class TargetSelectionResult(val status: TargetSelectionStatus, val matchedRank: TargetMatchRank = TargetMatchRank.NO_MATCH, val candidateCount: Int = 0, val details: String = "")

enum class EvidenceSource { FILESYSTEM, FILESYSTEM_AUDIT, DATABASE_QUERY, HTTP_CONTRACT, PROCESS_TELEMETRY, SYSTEM_SERVICE, UI_TREE, SENSOR_EVENT, LOCAL_MODEL_INFERENCE, RUNTIME_DIAGNOSTIC }
typealias ObservationSource = EvidenceSource
typealias VerificationEvidence = VerifiedExecutionEvidence
enum class CapabilityVerificationDomain { GENERAL_COMPUTATION, FILESYSTEM, NETWORK, SYSTEM_DIAGNOSTIC }

/**
 * Evidence container. A missing checksum is intentionally NOT converted into a hash of the
 * claim itself: integrity of a claim is not proof that the claimed state was true.
 */
data class VerifiedExecutionEvidence(
    val evidenceSource: EvidenceSource,
    val subject: String,
    val verifiedState: String,
    val checksumOrHash: String? = null,
    val observedAt: Long = System.currentTimeMillis(),
    val confidence: Double = 1.0
) {
    fun getEffectiveChecksum(): String = checksumOrHash?.trim().orEmpty()
    fun isVerifiedState(): Boolean =
        verifiedState.contains("VERIFIED", ignoreCase = true) ||
        verifiedState.contains("SUCCESS", ignoreCase = true) ||
        verifiedState.contains("MATCH", ignoreCase = true) ||
        verifiedState.contains("GENUINE_NEURAL_TENSOR", ignoreCase = true)
}

/** Execution simulation result, permanently marked SIMULATION_ONLY / TEST_ONLY. */
data class SimulationResult(
    val simulationId: String = UUID.randomUUID().toString(),
    val targetCapabilityId: String,
    val simulatedOutput: String,
    val simulatedState: String,
    val executionTier: String = "SIMULATION_ONLY",
    val isSimulationOnly: Boolean = true,
    val timestamp: Long = System.currentTimeMillis()
)

/** Test-only evidence cannot satisfy physical-device readiness gates. */
data class TestVerifiedEvidence(
    val testId: String = UUID.randomUUID().toString(),
    val targetSubject: String,
    val testScope: String,
    val testEvidence: String,
    val isTestOnly: Boolean = true,
    val timestamp: Long = System.currentTimeMillis()
)

/** Observed physical or runtime execution fact collected by an observation engine. */
data class ObservedExecutionFact(
    val factId: String = UUID.randomUUID().toString(),
    val actionId: String,
    val capabilityId: String,
    val observedState: String,
    val evidenceSource: EvidenceSource,
    val environmentBound: Boolean = true,
    val observedAtEpochMs: Long = System.currentTimeMillis()
)

/** Capability verified through canonical Reality/Verification authority. */
data class RealityVerifiedCapability(
    val capabilityId: String,
    val canonicalVerifier: String = "WastiVerificationEngine",
    val verificationEvidence: VerifiedExecutionEvidence,
    val verifiedAtEpochMs: Long = System.currentTimeMillis(),
)

/** Provenance-bound, consensus-backed trusted knowledge. */
data class TrustedKnowledge(
    val knowledgeId: String = UUID.randomUUID().toString(),
    val topic: String,
    val distilledContent: String,
    val provenanceEvidenceHash: String,
    val consensusScore: Float,
    val isFactuallyVerified: Boolean = false,
    val registeredAtEpochMs: Long = System.currentTimeMillis()
)

/**
 * Canonical Wasti Evidence Ladder:
 * IMPLEMENTED -> STATICALLY_VALIDATED -> UNIT_TESTED -> INTEGRATION_TESTED -> RUNTIME_VERIFIED -> DEVICE_VERIFIED -> REFERENCE_VERIFIED -> PRODUCTION_VERIFIED
 */
enum class EvidenceLadder {
    IMPLEMENTED,
    STATICALLY_VALIDATED,
    UNIT_TESTED,
    INTEGRATION_TESTED,
    RUNTIME_VERIFIED,
    DEVICE_VERIFIED,
    REFERENCE_VERIFIED,
    PRODUCTION_VERIFIED;

    fun satisfies(required: EvidenceLadder): Boolean = this.ordinal >= required.ordinal
}

/**
 * Explicit Polyglot Lifecycle States for WRE / Multi-Language Runtimes:
 * CONFIGURED -> AVAILABLE -> EXECUTED -> OBSERVED -> INDEPENDENTLY_VERIFIED
 */
enum class PolyglotLifecycleState {
    CONFIGURED,
    AVAILABLE,
    EXECUTED,
    OBSERVED,
    INDEPENDENTLY_VERIFIED
}

