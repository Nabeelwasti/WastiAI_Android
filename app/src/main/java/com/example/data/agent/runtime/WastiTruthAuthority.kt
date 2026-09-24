package com.example.data.agent.runtime

import java.security.MessageDigest
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

    @Volatile
    private var testSecretKey: javax.crypto.SecretKey? = null

    @org.jetbrains.annotations.TestOnly
    fun setTestAuthorityKeyForTesting(key: javax.crypto.SecretKey? = null) {
        if (key != null) {
            testSecretKey = key
        } else {
            val testSeed = "WastiTruthAuthorityTestSeed_Deterministic_2026".toByteArray(Charsets.UTF_8)
            val digest = MessageDigest.getInstance("SHA-256").digest(testSeed)
            testSecretKey = javax.crypto.spec.SecretKeySpec(digest, "HmacSHA256")
        }
    }

    private val productionSecretKey: javax.crypto.SecretKey? by lazy {
        try {
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
            ks.getKey("WastiTruthAuthorityKey", null) as? javax.crypto.SecretKey
        } catch (_: Throwable) {
            null
        }
    }

    private fun isTestEnvironment(): Boolean {
        return System.getProperty("WASTI_TEST_MODE") == "true" ||
            System.getProperty("ENVIRONMENT") == "test" ||
            System.getenv("WASTI_TEST_MODE") == "true" ||
            System.getenv("ENVIRONMENT") == "test" ||
            try {
                Class.forName("org.junit.Test")
                true
            } catch (_: ClassNotFoundException) {
                false
            }
    }

    val AUTHORITY_SECRET_KEY: javax.crypto.SecretKey
        get() {
            testSecretKey?.let { return it }
            productionSecretKey?.let { return it }
            if (isTestEnvironment()) {
                setTestAuthorityKeyForTesting()
                return testSecretKey!!
            }
            throw IllegalStateException("WastiTruthAuthority production AndroidKeyStore authority key is unavailable and no test authority key was registered.")
        }

    private const val MIN_VERIFIED_CONFIDENCE = 0.90
    private const val ALLOWED_CLOCK_SKEW_MS = 60_000L // 1 minute future timestamp tolerance

    /**
     * Evaluates execution postconditions and issues a canonical [WastiVerificationReceipt]
     * if and only if the postcondition evidence strictly satisfies objective truth criteria.
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

        // 3. Reject external caller forging WastiTruthAuthority identity
        if (verifierIdentity == "WastiTruthAuthority" && executorIdentity != null && executorIdentity != "WastiTruthAuthority") {
            val res = VerificationResult(
                taskId = taskId,
                actionId = actionId,
                capabilityId = capabilityId,
                status = ActionVerificationStatus.NOT_VERIFIABLE,
                confidence = 0.0,
                evidence = "External caller cannot forge WastiTruthAuthority identity",
                failureReason = "FORGED_VERIFIER_IDENTITY"
            )
            return res to null
        }

        // 4. Reject sandbox evidence from claiming runtime/production VERIFIED
        if (verificationMethod.contains("SANDBOX", ignoreCase = true) ||
            expectedState.contains("SANDBOX", ignoreCase = true) ||
            observedState.contains("SANDBOX", ignoreCase = true)
        ) {
            val res = VerificationResult(
                taskId = taskId,
                actionId = actionId,
                capabilityId = capabilityId,
                status = ActionVerificationStatus.NOT_VERIFIABLE,
                confidence = confidence,
                evidence = "Sandbox test execution cannot issue canonical runtime VERIFIED receipt",
                failureReason = "SANDBOX_EVIDENCE_NOT_CANONICAL"
            )
            return res to null
        }

        // 5. Reject unprobed process telemetry
        if (observationSource == EvidenceSource.PROCESS_TELEMETRY ||
            verificationMethod.contains("process_telemetry", ignoreCase = true) ||
            verificationMethod.contains("exit_code", ignoreCase = true)
        ) {
            val res = VerificationResult(
                taskId = taskId,
                actionId = actionId,
                capabilityId = capabilityId,
                status = ActionVerificationStatus.NOT_VERIFIABLE,
                confidence = confidence,
                evidence = "Unprobed process telemetry or exit code cannot establish VERIFIED status",
                failureReason = "UNPROBED_PROCESS_TELEMETRY"
            )
            return res to null
        }

        // 6. Reject generic or circular assertions without independent artifact/probe
        val trivialClaims = setOf("ok", "success", "completed", "executor_completed", "verified", "true", "done", "process_completed_success")
        if (trivialClaims.contains(expectedState.trim().lowercase()) && checksum.isNullOrBlank() && artifactRef.isNullOrBlank()) {
            val res = VerificationResult(
                taskId = taskId,
                actionId = actionId,
                capabilityId = capabilityId,
                status = ActionVerificationStatus.NOT_VERIFIABLE,
                confidence = 0.0,
                evidence = "Generic assertion without independent artifact or checksum probe cannot establish VERIFIED receipt",
                failureReason = "CIRCULAR_OR_ASSERTION_ONLY_EVIDENCE"
            )
            return res to null
        }

        // 7. Require an authorized objective probe for canonical VERIFIED
        var effectiveObservedState = observedState
        val isFilesystemProbe = observationSource == EvidenceSource.FILESYSTEM || observationSource == EvidenceSource.FILESYSTEM_AUDIT ||
            (!artifactRef.isNullOrBlank() && (artifactRef.startsWith("/") || artifactRef.startsWith(".")))

        val isAuthorizedDbOrSystemProbe = (observationSource == EvidenceSource.DATABASE_QUERY || observationSource == EvidenceSource.SYSTEM_SERVICE) &&
            (verifierIdentity.contains("ObjectiveProbe", ignoreCase = true) || verifierIdentity.contains("Probe", ignoreCase = true) || verifierIdentity == "WastiTruthAuthority") &&
            (verificationMethod.contains("objective_", ignoreCase = true) || verificationMethod.contains("probe", ignoreCase = true))

        if (!isFilesystemProbe && !isAuthorizedDbOrSystemProbe) {
            val res = VerificationResult(
                taskId = taskId,
                actionId = actionId,
                capabilityId = capabilityId,
                status = ActionVerificationStatus.NOT_VERIFIABLE,
                confidence = 0.0,
                evidence = "Canonical VERIFIED status requires an authorized objective postcondition probe (e.g. filesystem artifact hash or database/system probe). Caller assertions are evidence, not proof.",
                failureReason = "OBJECTIVE_PROBE_REQUIRED"
            )
            return res to null
        }

        if (isFilesystemProbe) {
            if (artifactRef.isNullOrBlank()) {
                val res = VerificationResult(
                    taskId = taskId,
                    actionId = actionId,
                    capabilityId = capabilityId,
                    status = ActionVerificationStatus.NOT_VERIFIABLE,
                    confidence = 0.0,
                    evidence = "Filesystem observation requires artifactRef pointing to target file",
                    failureReason = "MISSING_ARTIFACT_REF"
                )
                return res to null
            }
            val file = java.io.File(artifactRef)
            if (!file.exists()) {
                val res = VerificationResult(
                    taskId = taskId,
                    actionId = actionId,
                    capabilityId = capabilityId,
                    status = ActionVerificationStatus.FAILED,
                    confidence = 0.0,
                    evidence = "Target file does not exist on disk: $artifactRef",
                    failureReason = "FILE_NOT_FOUND"
                )
                return res to null
            }
            val actualDiskContent = try { file.readText(Charsets.UTF_8) } catch (_: Exception) { null }
            if (actualDiskContent == null) {
                val res = VerificationResult(
                    taskId = taskId,
                    actionId = actionId,
                    capabilityId = capabilityId,
                    status = ActionVerificationStatus.VERIFICATION_UNAVAILABLE,
                    confidence = 0.0,
                    evidence = "Trusted filesystem probe could not read target file: $artifactRef",
                    failureReason = "PROBE_UNAVAILABLE"
                )
                return res to null
            }
            val actualDiskHash = hashString(actualDiskContent)
            
            // Check against pre-authorized expected state / checksum
            if (expectedState.isNotBlank() && actualDiskHash != expectedState) {
                val res = VerificationResult(
                    taskId = taskId,
                    actionId = actionId,
                    capabilityId = capabilityId,
                    status = ActionVerificationStatus.FAILED,
                    confidence = 0.0,
                    evidence = "Disk content hash mismatch: disk=$actualDiskHash, expected=$expectedState",
                    failureReason = "DISK_HASH_MISMATCH"
                )
                return res to null
            }
            if (!checksum.isNullOrBlank() && actualDiskHash != checksum) {
                val res = VerificationResult(
                    taskId = taskId,
                    actionId = actionId,
                    capabilityId = capabilityId,
                    status = ActionVerificationStatus.FAILED,
                    confidence = 0.0,
                    evidence = "Disk content hash mismatch: disk=$actualDiskHash, expectedChecksum=$checksum",
                    failureReason = "DISK_HASH_MISMATCH"
                )
                return res to null
            }
            if (observedState.isNotBlank() && observedState != actualDiskHash) {
                val res = VerificationResult(
                    taskId = taskId,
                    actionId = actionId,
                    capabilityId = capabilityId,
                    status = ActionVerificationStatus.FAILED,
                    confidence = 0.0,
                    evidence = "Caller provided observedState '$observedState' does not match trusted probe disk hash '$actualDiskHash'",
                    failureReason = "PRODUCER_OBSERVATION_DISCREPANCY"
                )
                return res to null
            }
            
            // Probe independently establishes effective observed state
            effectiveObservedState = actualDiskHash
        }

        // 8. Postcondition match check
        if (expectedState.isBlank() || effectiveObservedState.isBlank() || expectedState != effectiveObservedState) {
            val res = VerificationResult(
                taskId = taskId,
                actionId = actionId,
                capabilityId = capabilityId,
                status = ActionVerificationStatus.FAILED,
                confidence = confidence,
                evidence = "Observed state '$effectiveObservedState' does not match expected postcondition '$expectedState'",
                failureReason = "POSTCONDITION_MISMATCH"
            )
            return res to null
        }

        // 9. Confidence threshold check
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

        // 10. Compute deterministic postcondition and execution binding hashes
        val postconditionRaw = "$taskId|$actionId|$capabilityId|$expectedState|$effectiveObservedState|${checksum ?: ""}|${artifactRef ?: ""}"
        val postconditionHash = hashString(postconditionRaw)
        val bindingRaw = "$taskId|$actionId|$capabilityId|${inputHash ?: ""}|${outputHash ?: ""}|$postconditionHash"
        val executionBindingHash = hashString(bindingRaw)

        // 11. Issue signed verification receipt
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
     * and has not been tampered with, modified, or set in the future.
     */
    fun validateReceipt(receipt: WastiVerificationReceipt?): Boolean {
        if (receipt == null) return false
        if (receipt.verifierIdentity != "WastiTruthAuthority") return false
        val now = System.currentTimeMillis()
        if (receipt.timestamp > now + ALLOWED_CLOCK_SKEW_MS) return false
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
        postconditionHash: String? = null,
        maxAgeMs: Long? = null
    ): Boolean {
        if (!validateReceipt(receipt)) return false
        val r = receipt!!
        val now = System.currentTimeMillis()
        if (r.timestamp > now + ALLOWED_CLOCK_SKEW_MS) return false
        val age = now - r.timestamp
        if (age < -ALLOWED_CLOCK_SKEW_MS) return false
        if (maxAgeMs != null && age > maxAgeMs) return false

        if (r.executionBindingHash.isBlank()) return false
        if (r.taskId != taskId || r.actionId != actionId || r.capabilityId != capabilityId) return false
        if (postconditionHash != null && r.postconditionHash.isNotBlank() && r.postconditionHash != postconditionHash) return false

        val expectedBindingRaw = "$taskId|$actionId|$capabilityId|${inputHash ?: ""}|${outputHash ?: ""}|${r.postconditionHash}"
        val expectedBindingHash = hashString(expectedBindingRaw)
        if (r.executionBindingHash != expectedBindingHash) return false

        return true
    }

    fun computePostconditionHash(
        taskId: String,
        actionId: String,
        capabilityId: String,
        expectedState: String,
        observedState: String,
        checksum: String? = null,
        artifactRef: String? = null
    ): String {
        val postconditionRaw = "$taskId|$actionId|$capabilityId|$expectedState|$observedState|${checksum ?: ""}|${artifactRef ?: ""}"
        return hashString(postconditionRaw)
    }

    fun computeExecutionBindingHash(
        taskId: String,
        actionId: String,
        capabilityId: String,
        inputHash: String? = null,
        outputHash: String? = null,
        postconditionHash: String
    ): String {
        val bindingRaw = "$taskId|$actionId|$capabilityId|${inputHash ?: ""}|${outputHash ?: ""}|$postconditionHash"
        return hashString(bindingRaw)
    }

    fun hashString(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
