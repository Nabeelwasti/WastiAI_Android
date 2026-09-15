package com.example.data.ai.learning

import android.content.Context
import android.util.Log
import com.example.data.auth.WastiIdentityManager
import com.example.data.security.ExecutionProvenanceLedger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest
import java.util.UUID

/**
 * Multi-Tenant Wasti Cloud Learning & Canonical Knowledge Architecture.
 * 
 * Doctrine:
 * 1. Useful verified skills, workflows, models, and capability improvements can be promoted
 *    from individual Wasti instances into a canonical shared knowledge layer.
 * 2. Privacy Absolute: Private user data, credentials, conversations, and personal memory
 *    are strictly isolated, scrubbed, and forbidden from global promotion.
 * 3. Requires:
 *    - Provenance & execution verification evidence
 *    - Explicit user consent
 *    - Automated policy validation (zero leakage / PII scrubbing)
 *    - Server-backed owner entitlement approval
 */
enum class PromotionCandidateType {
    VERIFIED_SKILL,
    TOOL_CAPABILITY,
    AUTONOMOUS_WORKFLOW,
    ANONYMIZED_KNOWLEDGE,
    MODEL_ADAPTER
}

enum class PromotionLifecycleState {
    LOCAL_DISCOVERED,
    CONSENT_GRANTED,
    POLICY_VERIFIED,
    SUBMITTED_TO_CLOUD,
    PROMOTED_GLOBAL,
    REJECTED
}

data class PromotionProposal(
    val id: String = UUID.randomUUID().toString(),
    val type: PromotionCandidateType,
    val title: String,
    val description: String,
    val anonymizedPayload: String,
    val provenanceHash: String,
    val executionEvidenceJson: String,
    val authorInstanceId: String,
    val timestampEpochMs: Long = System.currentTimeMillis(),
    val state: PromotionLifecycleState = PromotionLifecycleState.LOCAL_DISCOVERED,
    val userConsentGranted: Boolean = false,
    val ownerApprovalSignature: String? = null
)

object WastiCloudLearningManager {
    private const val TAG = "CloudLearningManager"

    private val _pendingProposals = MutableStateFlow<List<PromotionProposal>>(emptyList())
    val pendingProposals: StateFlow<List<PromotionProposal>> = _pendingProposals.asStateFlow()

    private val _canonicalGlobalSkills = MutableStateFlow<List<PromotionProposal>>(emptyList())
    val canonicalGlobalSkills: StateFlow<List<PromotionProposal>> = _canonicalGlobalSkills.asStateFlow()

    private val PII_PATTERNS = listOf(
        Regex("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,6}"), // Email
        Regex("(?:\\+?\\d{1,3}[- ]?)?\\(?\\d{3}\\)?[- ]?\\d{3}[- ]?\\d{4}"), // Phone
        Regex("(?:sk-[a-zA-Z0-9]{20,}|ghp_[a-zA-Z0-9]{20,}|AIza[0-9A-Za-z-_]{35})"), // API Keys
        Regex("(?i)(password|secret|token|bearer|authorization)\\s*[:=]\\s*['\"][^'\"]+['\"]") // Credentials
    )

    /**
     * Proposes a local skill or verified capability for promotion after sanitization.
     */
    fun proposeLocalCapabilityForPromotion(
        type: PromotionCandidateType,
        title: String,
        description: String,
        rawPayload: String,
        evidenceJson: String
    ): PromotionProposal? {
        val sanitized = scrubPrivateData(rawPayload)
        if (sanitized.contains("[REDACTED_SECRET]")) {
            Log.w(TAG, "Promotion candidate rejected: contained raw secrets")
            return null
        }

        val md = MessageDigest.getInstance("SHA-256")
        val hash = md.digest(sanitized.toByteArray()).fold("") { s, b -> s + "%02x".format(b) }

        val proposal = PromotionProposal(
            type = type,
            title = title.take(80),
            description = description.take(240),
            anonymizedPayload = sanitized,
            provenanceHash = hash,
            executionEvidenceJson = evidenceJson,
            authorInstanceId = WastiIdentityManager.currentProfile.value?.userId ?: "anonymous"
        )

        _pendingProposals.value = _pendingProposals.value + proposal
        Log.i(TAG, "Created promotion proposal '${proposal.title}' (Hash: ${hash.take(8)})")
        return proposal
    }

    /**
     * Grants explicit user consent for a specific proposal.
     */
    fun grantUserConsent(proposalId: String): Boolean {
        val list = _pendingProposals.value.toMutableList()
        val idx = list.indexOfFirst { it.id == proposalId }
        if (idx == -1) return false

        val item = list[idx]
        val updated = item.copy(
            userConsentGranted = true,
            state = PromotionLifecycleState.CONSENT_GRANTED
        )
        list[idx] = updated
        _pendingProposals.value = list
        Log.i(TAG, "User granted consent for promotion: $proposalId")
        return true
    }

    /**
     * Performs automated policy verification (scrubbing, safety check).
     */
    fun verifyPolicy(proposalId: String): Boolean {
        val list = _pendingProposals.value.toMutableList()
        val idx = list.indexOfFirst { it.id == proposalId }
        if (idx == -1) return false

        val item = list[idx]
        if (!item.userConsentGranted) return false

        val hasPII = PII_PATTERNS.any { it.containsMatchIn(item.anonymizedPayload) }
        if (hasPII) {
            list[idx] = item.copy(state = PromotionLifecycleState.REJECTED)
            _pendingProposals.value = list
            Log.w(TAG, "Policy check failed: PII detected in proposal $proposalId")
            return false
        }

        list[idx] = item.copy(state = PromotionLifecycleState.POLICY_VERIFIED)
        _pendingProposals.value = list
        Log.i(TAG, "Policy check passed for proposal $proposalId")
        return true
    }

    /**
     * Promotes the verified proposal to the canonical global knowledge layer (requires Verified Owner).
     */
    fun approveAndPromoteGlobally(proposalId: String): Boolean {
        val profile = WastiIdentityManager.currentProfile.value
        if (profile?.canApproveGlobalLearningPromotion != true) {
            Log.w(TAG, "Global promotion denied: caller is not a verified Wasti Owner")
            return false
        }

        val list = _pendingProposals.value.toMutableList()
        val idx = list.indexOfFirst { it.id == proposalId }
        if (idx == -1) return false

        val item = list[idx]
        if (item.state != PromotionLifecycleState.POLICY_VERIFIED) {
            Log.w(TAG, "Cannot promote: proposal is not policy-verified")
            return false
        }

        val sig = WastiIdentityManager.signPayload(item.provenanceHash.toByteArray())
        val promoted = item.copy(
            state = PromotionLifecycleState.PROMOTED_GLOBAL,
            ownerApprovalSignature = sig
        )
        list.removeAt(idx)
        _pendingProposals.value = list
        _canonicalGlobalSkills.value = _canonicalGlobalSkills.value + promoted

        Log.i(TAG, "SUCCESS: Globally promoted canonical capability '${promoted.title}'")
        return true
    }

    private fun scrubPrivateData(text: String): String {
        var clean = text
        for (p in PII_PATTERNS) {
            clean = p.replace(clean, "[REDACTED]")
        }
        return clean
    }
}
