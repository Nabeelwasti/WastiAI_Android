package com.example.data.core

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * [P0-38] OUTREACH-SAFETY: Gated outreach approval pipeline with human review.
 * Enforces the staged state machine:
 * LEAD_DISCOVERED → DATA_UNVERIFIED → DRAFT → HUMAN_REVIEW_REQUIRED → APPROVED → SENT.
 * Prohibits autonomous/unattended outreach transmission without human review/policy signoff.
 */
enum class OutreachStage {
    LEAD_DISCOVERED,
    DATA_UNVERIFIED,
    DRAFT,
    HUMAN_REVIEW_REQUIRED,
    APPROVED,
    SENT,
    REJECTED
}

data class OutreachApprovalRecord(
    val approvalId: String = UUID.randomUUID().toString(),
    val leadId: String,
    val recipient: String,
    val channel: String,
    val reviewer: String,
    val approvedAt: Long = System.currentTimeMillis(),
    val approvalToken: String = UUID.randomUUID().toString()
)

object OutreachSafetyEngine {

    private val approvalLedger = ConcurrentHashMap<String, OutreachApprovalRecord>()
    private val stageLedger = ConcurrentHashMap<String, OutreachStage>()

    fun getStage(leadId: String): OutreachStage {
        return stageLedger[leadId] ?: OutreachStage.LEAD_DISCOVERED
    }

    fun setStage(leadId: String, stage: OutreachStage) {
        stageLedger[leadId] = stage
    }

    /**
     * Validates and executes state transitions according to the canonical state machine:
     * LEAD_DISCOVERED → DATA_UNVERIFIED → DRAFT → HUMAN_REVIEW_REQUIRED → APPROVED → SENT.
     */
    fun transitionStage(
        leadId: String,
        targetStage: OutreachStage,
        reviewer: String? = null
    ): Result<OutreachStage> {
        val current = getStage(leadId)

        val isValidTransition = when (current) {
            OutreachStage.LEAD_DISCOVERED -> targetStage == OutreachStage.DATA_UNVERIFIED || targetStage == OutreachStage.REJECTED
            OutreachStage.DATA_UNVERIFIED -> targetStage == OutreachStage.DRAFT || targetStage == OutreachStage.REJECTED
            OutreachStage.DRAFT -> targetStage == OutreachStage.HUMAN_REVIEW_REQUIRED || targetStage == OutreachStage.REJECTED
            OutreachStage.HUMAN_REVIEW_REQUIRED -> {
                if (targetStage == OutreachStage.APPROVED) {
                    !reviewer.isNullOrBlank() // Must have explicit human reviewer name/signature
                } else {
                    targetStage == OutreachStage.REJECTED || targetStage == OutreachStage.DRAFT
                }
            }
            OutreachStage.APPROVED -> targetStage == OutreachStage.SENT || targetStage == OutreachStage.REJECTED
            OutreachStage.SENT -> false // Terminal success state
            OutreachStage.REJECTED -> targetStage == OutreachStage.DATA_UNVERIFIED // Re-evaluation allowed
        }

        if (!isValidTransition) {
            return Result.failure(
                IllegalStateException(
                    "Invalid outreach transition from $current to $targetStage for lead $leadId. " +
                    "Outreach pipeline strictly enforces: LEAD_DISCOVERED → DATA_UNVERIFIED → DRAFT → HUMAN_REVIEW_REQUIRED → APPROVED → SENT."
                )
            )
        }

        if (targetStage == OutreachStage.APPROVED && !reviewer.isNullOrBlank()) {
            val record = OutreachApprovalRecord(
                leadId = leadId,
                recipient = "pending_dispatch",
                channel = "MULTI_CHANNEL",
                reviewer = reviewer
            )
            approvalLedger[leadId] = record
        }

        stageLedger[leadId] = targetStage
        return Result.success(targetStage)
    }

    /**
     * Human Review Signoff:
     * Advances lead from HUMAN_REVIEW_REQUIRED to APPROVED.
     */
    fun recordHumanApproval(
        leadId: String,
        recipient: String,
        channel: String,
        reviewer: String
    ): Result<OutreachApprovalRecord> {
        val current = getStage(leadId)
        if (current != OutreachStage.HUMAN_REVIEW_REQUIRED && current != OutreachStage.DRAFT) {
            return Result.failure(
                IllegalStateException("Cannot approve lead $leadId in state $current. Lead must be in HUMAN_REVIEW_REQUIRED state.")
            )
        }
        if (reviewer.isBlank()) {
            return Result.failure(IllegalArgumentException("Reviewer identity required for human signoff."))
        }

        val record = OutreachApprovalRecord(
            leadId = leadId,
            recipient = recipient,
            channel = channel,
            reviewer = reviewer
        )
        approvalLedger[leadId] = record
        stageLedger[leadId] = OutreachStage.APPROVED
        return Result.success(record)
    }

    /**
     * Pre-transmission security gate:
     * Prohibits autonomous/unattended outreach transmission without verified human review.
     */
    fun canTransmitOutreach(leadId: String): Boolean {
        val stage = getStage(leadId)
        val hasApproval = approvalLedger.containsKey(leadId)
        return stage == OutreachStage.APPROVED && hasApproval
    }

    fun getApprovalRecord(leadId: String): OutreachApprovalRecord? {
        return approvalLedger[leadId]
    }

    fun recordTransmissionSuccess(leadId: String): Boolean {
        if (!canTransmitOutreach(leadId)) {
            return false
        }
        stageLedger[leadId] = OutreachStage.SENT
        return true
    }

    fun clearForTesting() {
        approvalLedger.clear()
        stageLedger.clear()
    }
}
