package com.example.data.agent.runtime

import java.util.UUID
import javax.crypto.Mac

/**
 * Unforgeable, immutable verification receipt issued strictly by [WastiTruthAuthority]
 * upon successful independent postcondition evaluation by the authority engine.
 */
data class WastiVerificationReceipt internal constructor(
    val receiptId: String = "rcpt_" + UUID.randomUUID().toString(),
    val taskId: String,
    val actionId: String,
    val capabilityId: String,
    val verifierIdentity: String = "WastiTruthAuthority",
    val timestamp: Long = System.currentTimeMillis(),
    val evidenceLevel: EvidenceLadder = EvidenceLadder.RUNTIME_VERIFIED,
    val postconditionHash: String,
    val executionBindingHash: String = "",
    val signatureToken: String
) {
    /**
     * Compute the exact canonical payload bound to this receipt for signature verification.
     */
    fun computeCanonicalPayload(): String {
        return "$receiptId|$taskId|$actionId|$capabilityId|$verifierIdentity|$timestamp|${evidenceLevel.name}|$postconditionHash|$executionBindingHash"
    }

    companion object {
        internal fun computeSignature(payload: String, secretKey: javax.crypto.SecretKey): String {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(secretKey)
            val hmacBytes = mac.doFinal(payload.toByteArray(Charsets.UTF_8))
            return hmacBytes.joinToString("") { "%02x".format(it) }
        }
    }
}
