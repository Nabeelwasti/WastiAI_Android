package com.example.data.agent.runtime

import java.util.UUID

enum class ObservationStrategy {
    SCREEN_SCRAPE,
    ACCESSIBILITY_EVENT,
    DIRECT_QUERY,
    TIMED_SNAPSHOT,
    NONE
}

enum class ObservationStatus {
    OBSERVED,
    NOT_OBSERVED,
    CHANGED,
    UNCHANGED,
    UNKNOWN,
    TIMEOUT,
    UNAVAILABLE
}

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

enum class ActionVerificationStatus {
    VERIFIED,
    FAILED,
    UNKNOWN,
    NOT_VERIFIABLE,
    VERIFICATION_UNAVAILABLE
}

/**
 * [P0-02] Canonical Capability-Specific Independent Evidence Schema.
 * Every field required by independent verification policy must be strictly validated.
 */
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
    val capabilitySpecificEvidence: CapabilitySpecificEvidence? = null
)

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

enum class TargetMatchRank {
    EXACT_RESOURCE_ID,
    EXACT_NORMALIZED_TEXT,
    EXACT_CONTENT_DESCRIPTION,
    NORMALIZED_EXACT_MATCH,
    PARTIAL_MATCH,
    COORDINATE_MATCH,
    NO_MATCH
}

enum class TargetSelectionStatus {
    MATCHED,
    AMBIGUOUS,
    NOT_FOUND
}

data class TargetSelectionResult(
    val status: TargetSelectionStatus,
    val matchedRank: TargetMatchRank = TargetMatchRank.NO_MATCH,
    val candidateCount: Int = 0,
    val details: String = ""
)

enum class EvidenceSource {
    FILESYSTEM,
    DATABASE_QUERY,
    HTTP_CONTRACT,
    PROCESS_TELEMETRY,
    SYSTEM_SERVICE,
    UI_TREE,
    SENSOR_EVENT,
    LOCAL_MODEL_INFERENCE,
    RUNTIME_DIAGNOSTIC
}

typealias ObservationSource = EvidenceSource

enum class CapabilityVerificationDomain {
    GENERAL_COMPUTATION,
    FILESYSTEM,
    NETWORK,
    SYSTEM_DIAGNOSTIC
}

val VerificationResult.isVerified: Boolean
    get() = status == ActionVerificationStatus.VERIFIED

val VerificationResult.explanation: String
    get() = failureReason ?: evidence

data class VerifiedExecutionEvidence(
    val evidenceSource: EvidenceSource,
    val subject: String,
    val verifiedState: String,
    val checksumOrHash: String? = null,
    val observedAt: Long = System.currentTimeMillis(),
    val confidence: Double = 1.0
)
