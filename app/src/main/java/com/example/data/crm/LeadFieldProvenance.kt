package com.example.data.crm

import java.util.concurrent.ConcurrentHashMap

/**
 * [P0-39] Field Provenance Source
 * Delineates between user-entered, authoritative API, web-scraped, and AI-inferred CRM fields.
 * Invariant: Web-scraped and AI-inferred data must NEVER masquerade as authoritative customer data.
 */
enum class FieldProvenanceSource {
    USER_ENTERED,
    AUTHORITATIVE_API,
    WEB_SCRAPED,
    AI_INFERRED;

    val isAuthoritative: Boolean
        get() = this == USER_ENTERED || this == AUTHORITATIVE_API

    val requiresVerification: Boolean
        get() = this == WEB_SCRAPED || this == AI_INFERRED
}

/**
 * Strongly-typed wrapper tracking the provenance, source detail, and confidence score of a CRM field.
 */
data class ProvenanceTrackedField<T>(
    val value: T,
    val source: FieldProvenanceSource,
    val confidence: Float = if (source.isAuthoritative) 1.0f else 0.5f,
    val sourceDetail: String = "",
    val verifiedAtTimestamp: Long? = if (source.isAuthoritative) System.currentTimeMillis() else null,
    val verifiedBy: String? = if (source == FieldProvenanceSource.USER_ENTERED) "USER" else null
) {
    val isAuthoritative: Boolean
        get() = source.isAuthoritative || verifiedBy != null

    fun verify(verifier: String, newConfidence: Float = 1.0f): ProvenanceTrackedField<T> {
        return copy(
            confidence = newConfidence,
            verifiedAtTimestamp = System.currentTimeMillis(),
            verifiedBy = verifier
        )
    }

    fun overrideWithUserInput(newValue: T, userIdentifier: String = "USER"): ProvenanceTrackedField<T> {
        return ProvenanceTrackedField(
            value = newValue,
            source = FieldProvenanceSource.USER_ENTERED,
            confidence = 1.0f,
            sourceDetail = "Manually entered/edited by $userIdentifier",
            verifiedAtTimestamp = System.currentTimeMillis(),
            verifiedBy = userIdentifier
        )
    }

    fun promoteToAuthoritativeApi(sourceApi: String): ProvenanceTrackedField<T> {
        return ProvenanceTrackedField(
            value = value,
            source = FieldProvenanceSource.AUTHORITATIVE_API,
            confidence = 1.0f,
            sourceDetail = "Verified by authoritative API: $sourceApi",
            verifiedAtTimestamp = System.currentTimeMillis(),
            verifiedBy = sourceApi
        )
    }
}

/**
 * Lead provenance profile holding provenance tracking across all critical CRM attributes.
 */
data class LeadProvenanceProfile(
    val leadId: String,
    val clientName: ProvenanceTrackedField<String>,
    val email: ProvenanceTrackedField<String>,
    val phone: ProvenanceTrackedField<String>,
    val companyName: ProvenanceTrackedField<String>,
    val websiteUrl: ProvenanceTrackedField<String>,
    val budgetOrPayment: ProvenanceTrackedField<String>,
    val opportunityNature: ProvenanceTrackedField<String>,
    val decisionMakerInferred: ProvenanceTrackedField<Boolean>,
    val lastUpdatedTimestamp: Long = System.currentTimeMillis()
) {
    /**
     * Checks whether all critical contact fields are authoritative.
     */
    val hasAuthoritativeContactInfo: Boolean
        get() = (email.value.isNotBlank() && email.isAuthoritative) ||
                (phone.value.isNotBlank() && phone.isAuthoritative)

    /**
     * Identifies any fields currently tagged as WEB_SCRAPED or AI_INFERRED that have not been verified.
     */
    fun getUnverifiedFields(): List<Pair<String, ProvenanceTrackedField<*>>> {
        val list = mutableListOf<Pair<String, ProvenanceTrackedField<*>>>()
        if (clientName.source.requiresVerification && clientName.verifiedBy == null) list.add("clientName" to clientName)
        if (email.source.requiresVerification && email.verifiedBy == null) list.add("email" to email)
        if (phone.source.requiresVerification && phone.verifiedBy == null) list.add("phone" to phone)
        if (companyName.source.requiresVerification && companyName.verifiedBy == null) list.add("companyName" to companyName)
        if (websiteUrl.source.requiresVerification && websiteUrl.verifiedBy == null) list.add("websiteUrl" to websiteUrl)
        if (budgetOrPayment.source.requiresVerification && budgetOrPayment.verifiedBy == null) list.add("budgetOrPayment" to budgetOrPayment)
        if (opportunityNature.source.requiresVerification && opportunityNature.verifiedBy == null) list.add("opportunityNature" to opportunityNature)
        if (decisionMakerInferred.source.requiresVerification && decisionMakerInferred.verifiedBy == null) list.add("decisionMakerInferred" to decisionMakerInferred)
        return list
    }

    /**
     * Asserts that unverified or inferred fields are not masquerading as authoritative.
     * Throws IllegalStateException if invariant is violated.
     */
    fun assertNotMasqueradingAsAuthoritative(): Boolean {
        if (email.source == FieldProvenanceSource.AI_INFERRED && email.isAuthoritative && email.verifiedBy == null) {
            throw IllegalStateException("Field 'email' is AI_INFERRED but marked authoritative without human verification")
        }
        if (phone.source == FieldProvenanceSource.AI_INFERRED && phone.isAuthoritative && phone.verifiedBy == null) {
            throw IllegalStateException("Field 'phone' is AI_INFERRED but marked authoritative without human verification")
        }
        if (clientName.source == FieldProvenanceSource.AI_INFERRED && clientName.isAuthoritative && clientName.verifiedBy == null) {
            throw IllegalStateException("Field 'clientName' is AI_INFERRED but marked authoritative without human verification")
        }
        return true
    }

    /**
     * Verifies a specific field by name and human reviewer.
     */
    fun verifyField(fieldName: String, reviewer: String): LeadProvenanceProfile {
        return when (fieldName) {
            "clientName" -> copy(clientName = clientName.verify(reviewer))
            "email" -> copy(email = email.verify(reviewer))
            "phone" -> copy(phone = phone.verify(reviewer))
            "companyName" -> copy(companyName = companyName.verify(reviewer))
            "websiteUrl" -> copy(websiteUrl = websiteUrl.verify(reviewer))
            "budgetOrPayment" -> copy(budgetOrPayment = budgetOrPayment.verify(reviewer))
            "opportunityNature" -> copy(opportunityNature = opportunityNature.verify(reviewer))
            "decisionMakerInferred" -> copy(decisionMakerInferred = decisionMakerInferred.verify(reviewer))
            else -> this
        }
    }
}

/**
 * Thread-safe provenance tracker for all active leads in Wasti AI OS.
 */
object LeadProvenanceTracker {
    private val profiles = ConcurrentHashMap<String, LeadProvenanceProfile>()

    fun recordProfile(profile: LeadProvenanceProfile) {
        profile.assertNotMasqueradingAsAuthoritative()
        profiles[profile.leadId] = profile
    }

    fun getProfile(leadId: String): LeadProvenanceProfile? = profiles[leadId]

    fun getAllProfiles(): Map<String, LeadProvenanceProfile> = profiles.toMap()

    fun removeProfile(leadId: String) {
        profiles.remove(leadId)
    }

    fun clear() {
        profiles.clear()
    }

    /**
     * Constructs a provenance profile for a web-scraped lead with AI enrichment.
     */
    fun createScrapedWithAiEnrichment(
        leadId: String,
        clientName: String,
        email: String,
        phone: String,
        companyName: String,
        websiteUrl: String,
        budgetOrPayment: String,
        opportunityNature: String,
        isAiInferredContact: Boolean,
        scraperSource: String
    ): LeadProvenanceProfile {
        val contactSource = if (isAiInferredContact) FieldProvenanceSource.AI_INFERRED else FieldProvenanceSource.WEB_SCRAPED
        val contactConfidence = if (isAiInferredContact) 0.4f else 0.7f

        val profile = LeadProvenanceProfile(
            leadId = leadId,
            clientName = ProvenanceTrackedField(
                value = clientName,
                source = FieldProvenanceSource.AI_INFERRED,
                confidence = 0.6f,
                sourceDetail = "Extracted by AI reasoning from title/description"
            ),
            email = ProvenanceTrackedField(
                value = email,
                source = contactSource,
                confidence = contactConfidence,
                sourceDetail = if (isAiInferredContact) "Inferred by AI from text patterns" else "Scraped via regex from $scraperSource"
            ),
            phone = ProvenanceTrackedField(
                value = phone,
                source = contactSource,
                confidence = contactConfidence,
                sourceDetail = if (isAiInferredContact) "Inferred by AI from text patterns" else "Scraped via regex from $scraperSource"
            ),
            companyName = ProvenanceTrackedField(
                value = companyName,
                source = FieldProvenanceSource.WEB_SCRAPED,
                confidence = 0.65f,
                sourceDetail = "Scraped from title/description via $scraperSource"
            ),
            websiteUrl = ProvenanceTrackedField(
                value = websiteUrl,
                source = FieldProvenanceSource.WEB_SCRAPED,
                confidence = 0.85f,
                sourceDetail = "Extracted from source listing url"
            ),
            budgetOrPayment = ProvenanceTrackedField(
                value = budgetOrPayment,
                source = FieldProvenanceSource.AI_INFERRED,
                confidence = 0.5f,
                sourceDetail = "Extracted/Inferred from job specification"
            ),
            opportunityNature = ProvenanceTrackedField(
                value = opportunityNature,
                source = FieldProvenanceSource.AI_INFERRED,
                confidence = 0.8f,
                sourceDetail = "Classified by AI domain classification"
            ),
            decisionMakerInferred = ProvenanceTrackedField(
                value = false,
                source = FieldProvenanceSource.AI_INFERRED,
                confidence = 0.3f,
                sourceDetail = "Pending verification of decision maker authority"
            )
        )
        recordProfile(profile)
        return profile
    }

    /**
     * Constructs a provenance profile for an explicitly user-entered lead.
     */
    fun createUserEntered(
        leadId: String,
        clientName: String,
        email: String,
        phone: String,
        companyName: String,
        websiteUrl: String,
        budgetOrPayment: String,
        opportunityNature: String,
        userIdentifier: String = "USER"
    ): LeadProvenanceProfile {
        val profile = LeadProvenanceProfile(
            leadId = leadId,
            clientName = ProvenanceTrackedField(
                value = clientName,
                source = FieldProvenanceSource.USER_ENTERED,
                confidence = 1.0f,
                sourceDetail = "Manually entered by $userIdentifier",
                verifiedAtTimestamp = System.currentTimeMillis(),
                verifiedBy = userIdentifier
            ),
            email = ProvenanceTrackedField(
                value = email,
                source = FieldProvenanceSource.USER_ENTERED,
                confidence = 1.0f,
                sourceDetail = "Manually entered by $userIdentifier",
                verifiedAtTimestamp = System.currentTimeMillis(),
                verifiedBy = userIdentifier
            ),
            phone = ProvenanceTrackedField(
                value = phone,
                source = FieldProvenanceSource.USER_ENTERED,
                confidence = 1.0f,
                sourceDetail = "Manually entered by $userIdentifier",
                verifiedAtTimestamp = System.currentTimeMillis(),
                verifiedBy = userIdentifier
            ),
            companyName = ProvenanceTrackedField(
                value = companyName,
                source = FieldProvenanceSource.USER_ENTERED,
                confidence = 1.0f,
                sourceDetail = "Manually entered by $userIdentifier",
                verifiedAtTimestamp = System.currentTimeMillis(),
                verifiedBy = userIdentifier
            ),
            websiteUrl = ProvenanceTrackedField(
                value = websiteUrl,
                source = FieldProvenanceSource.USER_ENTERED,
                confidence = 1.0f,
                sourceDetail = "Manually entered by $userIdentifier",
                verifiedAtTimestamp = System.currentTimeMillis(),
                verifiedBy = userIdentifier
            ),
            budgetOrPayment = ProvenanceTrackedField(
                value = budgetOrPayment,
                source = FieldProvenanceSource.USER_ENTERED,
                confidence = 1.0f,
                sourceDetail = "Manually entered by $userIdentifier",
                verifiedAtTimestamp = System.currentTimeMillis(),
                verifiedBy = userIdentifier
            ),
            opportunityNature = ProvenanceTrackedField(
                value = opportunityNature,
                source = FieldProvenanceSource.USER_ENTERED,
                confidence = 1.0f,
                sourceDetail = "Manually entered by $userIdentifier",
                verifiedAtTimestamp = System.currentTimeMillis(),
                verifiedBy = userIdentifier
            ),
            decisionMakerInferred = ProvenanceTrackedField(
                value = true,
                source = FieldProvenanceSource.USER_ENTERED,
                confidence = 1.0f,
                sourceDetail = "Directly provided by user",
                verifiedAtTimestamp = System.currentTimeMillis(),
                verifiedBy = userIdentifier
            )
        )
        recordProfile(profile)
        return profile
    }

    /**
     * Validates whether a lead has verified contact provenance before outreach dispatch.
     * Prevents autonomous sending to AI-inferred contact addresses that have not been human-reviewed.
     */
    fun validateContactProvenanceForOutreach(leadId: String): Pair<Boolean, String?> {
        val profile = getProfile(leadId)
            ?: return Pair(false, "No provenance profile registered for lead $leadId (unverified provenance)")

        val unverifiedContact = (profile.email.source.requiresVerification && profile.email.verifiedBy == null) &&
                (profile.phone.source.requiresVerification && profile.phone.verifiedBy == null)

        if (unverifiedContact) {
            return Pair(
                false,
                "Outreach blocked: Contact info (${profile.email.source.name}) has not been human verified or user-entered"
            )
        }

        return Pair(true, null)
    }
}
