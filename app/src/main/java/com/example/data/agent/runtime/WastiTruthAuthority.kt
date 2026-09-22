package com.example.data.agent.runtime

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

/**
 * Single Canonical Truth Authority for Wasti AI OS.
 *
 * Implements the core architecture:
 * "One Truth Authority, Many Observers, Zero Independent Truth Authorities."
 *
 * Holds the private root HMAC authority key that never leaves this class.
 */
object WastiTruthAuthority {

    private val AUTHORITY_SECRET_KEY: ByteArray = try {
        val ks = java.security.KeyStore.getInstance("AndroidKeyStore")
        ks.load(null)
        if (!ks.containsAlias("WastiTruthAuthorityKey")) {
            val kgen = javax.crypto.KeyGenerator.getInstance("HmacSHA256", "AndroidKeyStore")
            kgen.init(
                android.security.keystore.KeyGenParameterSpec.Builder(
                    "WastiTruthAuthorityKey",
                    android.security.keystore.KeyProperties.PURPOSE_SIGN or android.security.keystore.KeyProperties.PURPOSE_VERIFY
                ).build()
            )
            kgen.generateKey()
        }
        val secretKey = ks.getKey("WastiTruthAuthorityKey", null) as? javax.crypto.SecretKey
        secretKey?.encoded ?: ByteArray(32).apply { SecureRandom().nextBytes(this) }
    } catch (e: Throwable) {
        ByteArray(32).apply { SecureRandom().nextBytes(this) }
    }

    private const val MIN_VERIFIED_CONFIDENCE = 0.90

    /**
     * Evaluates execution postconditions and issues a canonical [WastiVerificationReceipt]
     * if and only if the postcondition evidence strictly satisfies truth criteria.
     */
    fun evaluate(
        taskId: String,
        actionId: String,
        capabilityId: String,
        expectedState: String,
        observedState: String,
        observationSource: EvidenceSource,
        verifierIdentity: String,
        verificationMethod: String,
        confidence: Double,
        artifactRef: String? = null,
        checksum: String? = null,
        inputHash: String? = null,
        outputHash: String? = null,
        executorIdentity: String? = null
    ): Pair<VerificationResult, WastiVerificationReceipt?> {
        // 1. Strict input validation
        if (taskId.isBlank() || actionId.isBlank() || capabilityId.isBlank()) {
            val res = VerificationResult(
                taskId = taskId,
                actionId = actionId,
                capabilityId = capabilityId,
                status = ActionVerificationStatus.FAILED,
                confidence = 0.0,
                evidence = "Invalid task/action/capability identity",
                failureReason = "BLANK_IDENTITY"
            )
            return res to null
        }

        // 2. Reject self-certification by executor
        if (executorIdentity != null && verifierIdentity.equals(executorIdentity, ignoreCase = true) && verifierIdentity != "WastiTruthAuthority") {
            val res = VerificationResult(
                taskId = taskId,
                actionId = actionId,
                capabilityId = capabilityId,
                status = ActionVerificationStatus.NOT_VERIFIABLE,
                confidence = confidence,
                evidence = "Executor cannot self-certify postcondition without independent verification",
                failureReason = "EXECUTOR_SELF_CERTIFICATION"
            )
            return res to null
        }

        // 3. Reject unprobed process telemetry
        if (observationSource == EvidenceSource.PROCESS_TELEMETRY) {
            val res = VerificationResult(
                taskId = taskId,
                actionId = actionId,
                capabilityId = capabilityId,
                status = ActionVerificationStatus.NOT_VERIFIABLE,
                confidence = confidence,
                evidence = "Unprobed process telemetry cannot establish VERIFIED status",
                failureReason = "UNPROBED_PROCESS_TELEMETRY"
            )
            return res to null
        }

        // 4. Postcondition match check
        if (expectedState.isBlank() || observedState.isBlank() || expectedState != observedState) {
            val res = VerificationResult(
                taskId = taskId,
                actionId = actionId,
                capabilityId = capabilityId,
                status = ActionVerificationStatus.FAILED,
                confidence = confidence,
                evidence = "Observed state '$observedState' does not match expected postcondition '$expectedState'",
                failureReason = "POSTCONDITION_MISMATCH"
            )
            return res to null
        }

        // 5. Confidence threshold check
        if (confidence < MIN_VERIFIED_CONFIDENCE) {
            val res = VerificationResult(
                taskId = taskId,
                actionId = actionId,
                capabilityId = capabilityId,
                status = ActionVerificationStatus.NOT_VERIFIABLE,
                confidence = confidence,
                evidence = "Confidence $confidence is below strict threshold $MIN_VERIFIED_CONFIDENCE",
                failureReason = "LOW_CONFIDENCE"
            )
            return res to null
        }

        // 6. Compute deterministic postcondition and execution binding hashes
        val postconditionRaw = "$taskId|$actionId|$capabilityId|$expectedState|$observedState|${checksum ?: ""}|${artifactRef ?: ""}"
        val postconditionHash = hashString(postconditionRaw)
        val bindingRaw = "$taskId|$actionId|$capabilityId|${inputHash ?: ""}|${outputHash ?: ""}|$postconditionHash"
        val executionBindingHash = hashString(bindingRaw)

        // 7. Issue signed verification receipt
        val receiptId = "rcpt_" + UUID.randomUUID().toString()
        val timestamp = System.currentTimeMillis()
        val evidenceLevel = when (observationSource) {
            EvidenceSource.FILESYSTEM_AUDIT, EvidenceSource.SYSTEM_SERVICE -> EvidenceLadder.DEVICE_VERIFIED
            EvidenceSource.FILESYSTEM, EvidenceSource.DATABASE_QUERY, EvidenceSource.RUNTIME_DIAGNOSTIC -> EvidenceLadder.RUNTIME_VERIFIED
            else -> EvidenceLadder.INTEGRATION_TESTED
        }

        val canonicalPayload = "$receiptId|$taskId|$actionId|$capabilityId|WastiTruthAuthority|$timestamp|${evidenceLevel.name}|$postconditionHash|$executionBindingHash"
        val signature = WastiVerificationReceipt.computeSignature(canonicalPayload, AUTHORITY_SECRET_KEY)

        val receipt = WastiVerificationReceipt(
            receiptId = receiptId,
            taskId = taskId,
            actionId = actionId,
            capabilityId = capabilityId,
            verifierIdentity = "WastiTruthAuthority",
            timestamp = timestamp,
            evidenceLevel = evidenceLevel,
            postconditionHash = postconditionHash,
            executionBindingHash = executionBindingHash,
            signatureToken = signature
        )

        val result = VerificationResult(
            taskId = taskId,
            actionId = actionId,
            capabilityId = capabilityId,
            status = ActionVerificationStatus.VERIFIED,
            confidence = confidence,
            evidence = "Verified by WastiTruthAuthority (receipt=${receipt.receiptId}, level=${evidenceLevel.name})",
            evidenceLevel = evidenceLevel
        )

        return result to receipt
    }

    /**
     * Validates that a [WastiVerificationReceipt] was issued by this exact [WastiTruthAuthority] instance
     * and has not been tampered with or modified.
     */
    fun validateReceipt(receipt: WastiVerificationReceipt?): Boolean {
        if (receipt == null) return false
        if (receipt.verifierIdentity != "WastiTruthAuthority") return false
        val canonicalPayload = receipt.computeCanonicalPayload()
        val expectedSignature = WastiVerificationReceipt.computeSignature(canonicalPayload, AUTHORITY_SECRET_KEY)
        return receipt.signatureToken == expectedSignature
    }

    /**
     * Validates both cryptographic signature authenticity and exact execution context binding.
     * Prevents receipt replay attacks across different tasks, actions, capabilities, or outputs.
     */
    fun validateReceiptApplicability(
        receipt: WastiVerificationReceipt?,
        taskId: String,
        actionId: String,
        capabilityId: String,
        inputHash: String? = null,
        outputHash: String? = null,
        postconditionHash: String? = null
    ): Boolean {
        if (!validateReceipt(receipt)) return false
        val r = receipt!!
        if (r.taskId != taskId || r.actionId != actionId || r.capabilityId != capabilityId) return false
        if (postconditionHash != null && r.postconditionHash.isNotBlank() && r.postconditionHash != postconditionHash) return false
        if (r.executionBindingHash.isNotBlank()) {
            val expectedBindingRaw = "$taskId|$actionId|$capabilityId|${inputHash ?: ""}|${outputHash ?: ""}|${r.postconditionHash}"
            val expectedBindingHash = hashString(expectedBindingRaw)
            if (r.executionBindingHash != expectedBindingHash) return false
        }
        return true
    }

    private fun hashString(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
