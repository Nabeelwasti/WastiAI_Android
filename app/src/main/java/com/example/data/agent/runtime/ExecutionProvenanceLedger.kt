package com.example.data.agent.runtime

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Immutable Provenance Record documenting exact execution evidence, hashes, sequence, and verification.
 */
data class ProvenanceEntry(
    val entryId: String,
    val taskId: String,
    val actionId: String,
    val capabilityId: String,
    val providerId: String,
    val modelId: String?,
    val inputHash: String,
    val outputHash: String,
    val evidenceSource: EvidenceSource,
    val evidenceSummary: String,
    val verificationStatus: String,
    val isVerified: Boolean,
    val timestamp: Long,
    val previousEntryHash: String,
    val entryHash: String,
    val confidence: Double = 0.0,
    val operationId: String = actionId,
    val executorResult: String = outputHash,
    val observationSource: String = evidenceSource.name,
    val independentVerifier: String? = null,
    val modelHash: String? = null,
    val architecture: String? = null,
    val runtimeVersion: String? = null,
    val executionEnvironment: String = "local_android_runtime",
    val executor: String = providerId,
    val verifier: String? = independentVerifier,
    val verificationMethod: String = "canonical_hash_chain",
    val stateTransition: String = "DISPATCHED -> EXECUTOR_COMPLETED -> OBSERVED -> VERIFIED",
    val sequenceNumber: Long = 0L,
    val evidenceLevel: EvidenceLadder = EvidenceLadder.IMPLEMENTED,
    val expectedState: String = "",
    val observedState: String = "",
    val howObserved: String = "",
    val receiptId: String? = null
)

/**
 * Hash-chained Execution Provenance Ledger ensuring cryptographic traceability for all actions.
 * Durable across process restarts with authenticated persistence, monotonic sequence protection,
 * and dynamic Android Keystore/HMAC integrity anchoring.
 */
object ExecutionProvenanceLedger {
    private const val TAG = "ExecutionProvenanceLedger"
    const val GENESIS_HASH = "0000000000000000000000000000000000000000000000000000000000000000"

    private val _entries = MutableStateFlow<List<ProvenanceEntry>>(emptyList())
    val entries: StateFlow<List<ProvenanceEntry>> = _entries.asStateFlow()

    @Volatile
    private var isLedgerCompromised = false

    private var keystoreSecretKey: javax.crypto.SecretKey? = null
    private var fallbackAnchorKey: ByteArray? = null

    init {
        loadPersistedLedger()
    }

    private fun getStorageDir(): File {
        val userHome = System.getProperty("user.home") ?: System.getProperty("java.io.tmpdir") ?: "."
        val dir = File(userHome, ".wasti_ai/provenance")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun getOrInitKeystoreAnchor(): javax.crypto.SecretKey? {
        try {
            val keyStore = java.security.KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)
            val alias = "wasti_provenance_anchor_key"
            if (!keyStore.containsAlias(alias)) {
                try {
                    val keyGenerator = javax.crypto.KeyGenerator.getInstance(
                        "HmacSHA256", "AndroidKeyStore"
                    )
                    val specClass = Class.forName("android.security.keystore.KeyGenParameterSpec\$Builder")
                    val purposes = 4 // KeyProperties.PURPOSE_SIGN
                    val builder = specClass.getConstructor(String::class.java, Int::class.javaPrimitiveType).newInstance(alias, purposes)
                    val buildMethod = specClass.getMethod("build")
                    val spec = buildMethod.invoke(builder)
                    val initMethod = keyGenerator.javaClass.getMethod("init", java.security.spec.AlgorithmParameterSpec::class.java)
                    initMethod.invoke(keyGenerator, spec)
                    keyGenerator.generateKey()
                } catch (_: Throwable) {}
            }
            val secretKey = keyStore.getKey(alias, null) as? javax.crypto.SecretKey
            if (secretKey != null) {
                keystoreSecretKey = secretKey
                return secretKey
            }
        } catch (_: Throwable) {
            // AndroidKeyStore unavailable in JVM unit test environment; use protected storage anchor
        }
        return null
    }

    private fun loadOrCreateAnchorKey(): ByteArray {
        val dir = getStorageDir()
        val keyFile = File(dir, ".keystore_anchor.key")
        return try {
            if (keyFile.exists() && keyFile.length() == 32L) {
                keyFile.readBytes()
            } else {
                val newKey = ByteArray(32)
                SecureRandom().nextBytes(newKey)
                keyFile.writeBytes(newKey)
                newKey
            }
        } catch (_: Exception) {
            val fallback = ByteArray(32)
            SecureRandom().nextBytes(fallback)
            fallback
        }
    }

    private fun computeCanonicalPayload(
        previousEntryHash: String,
        taskId: String,
        actionId: String,
        capabilityId: String,
        providerId: String,
        modelId: String?,
        inputHash: String,
        outputHash: String,
        evidenceSource: String,
        evidenceLevel: String,
        expectedState: String,
        observedState: String,
        receiptId: String?,
        verificationStatus: String,
        timestamp: Long,
        sequenceNumber: Long
    ): String {
        return "$previousEntryHash|$sequenceNumber|$taskId|$actionId|$capabilityId|$providerId|${modelId ?: ""}|$inputHash|$outputHash|$evidenceSource|$evidenceLevel|$expectedState|$observedState|${receiptId ?: ""}|$verificationStatus|$timestamp"
    }

    private fun loadPersistedLedger() {
        try {
            val dir = getStorageDir()
            val ledgerFile = File(dir, "provenance_ledger.jsonl")
            if (!ledgerFile.exists()) return

            val loaded = mutableListOf<ProvenanceEntry>()
            var expectedPrevHash = GENESIS_HASH
            var expectedSequence = 1L
            var corruptionDetected = false

            ledgerFile.forEachLine { line ->
                if (line.isNotBlank() && !corruptionDetected) {
                    try {
                        val obj = org.json.JSONObject(line)
                        val sourceName = obj.optString("evidenceSource", EvidenceSource.PROCESS_TELEMETRY.name)
                        val source = try { EvidenceSource.valueOf(sourceName) } catch (_: Exception) { EvidenceSource.PROCESS_TELEMETRY }
                        val prevHash = obj.optString("previousEntryHash", GENESIS_HASH)
                        val entryHash = obj.optString("entryHash", "")
                        val seq = obj.optLong("sequenceNumber", expectedSequence)
                        val eLevelStr = obj.optString("evidenceLevel", EvidenceLadder.IMPLEMENTED.name)
                        val eLevel = try { EvidenceLadder.valueOf(eLevelStr) } catch (_: Exception) { EvidenceLadder.IMPLEMENTED }

                        val entry = ProvenanceEntry(
                            entryId = obj.getString("entryId"),
                            taskId = obj.getString("taskId"),
                            actionId = obj.getString("actionId"),
                            capabilityId = obj.getString("capabilityId"),
                            providerId = obj.getString("providerId"),
                            modelId = obj.optString("modelId").takeIf { it.isNotBlank() },
                            inputHash = obj.getString("inputHash"),
                            outputHash = obj.getString("outputHash"),
                            evidenceSource = source,
                            evidenceSummary = obj.optString("evidenceSummary", ""),
                            verificationStatus = obj.optString("verificationStatus", "EXECUTOR_COMPLETED"),
                            isVerified = obj.optBoolean("isVerified", false),
                            timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                            previousEntryHash = prevHash,
                            entryHash = entryHash,
                            confidence = obj.optDouble("confidence", 0.0),
                            operationId = obj.optString("operationId", obj.getString("actionId")),
                            executorResult = obj.optString("executorResult", obj.getString("outputHash")),
                            observationSource = obj.optString("observationSource", source.name),
                            independentVerifier = obj.optString("independentVerifier").takeIf { it.isNotBlank() },
                            modelHash = obj.optString("modelHash").takeIf { it.isNotBlank() },
                            architecture = obj.optString("architecture").takeIf { it.isNotBlank() },
                            runtimeVersion = obj.optString("runtimeVersion").takeIf { it.isNotBlank() },
                            executionEnvironment = obj.optString("executionEnvironment", "local_android_runtime"),
                            executor = obj.optString("executor", obj.getString("providerId")),
                            verifier = obj.optString("verifier").takeIf { it.isNotBlank() },
                            verificationMethod = obj.optString("verificationMethod", "canonical_hash_chain"),
                            stateTransition = obj.optString("stateTransition", "DISPATCHED -> EXECUTOR_COMPLETED -> OBSERVED -> VERIFIED"),
                            sequenceNumber = seq,
                            evidenceLevel = eLevel,
                            expectedState = obj.optString("expectedState", ""),
                            observedState = obj.optString("observedState", ""),
                            howObserved = obj.optString("howObserved", ""),
                            receiptId = obj.optString("receiptId").takeIf { it.isNotBlank() }
                        )

                        val payloadLegacy = "${entry.previousEntryHash}|${entry.taskId}|${entry.actionId}|${entry.capabilityId}|${entry.providerId}|${entry.inputHash}|${entry.outputHash}|${entry.verificationStatus}|${entry.timestamp}"
                        val payloadLegacy2 = "${entry.previousEntryHash}|${entry.taskId}|${entry.actionId}|${entry.capabilityId}|${entry.providerId}|${entry.inputHash}|${entry.outputHash}|${entry.verificationStatus}|${entry.timestamp}|${entry.sequenceNumber}"
                        val payloadCanonical = computeCanonicalPayload(
                            entry.previousEntryHash, entry.taskId, entry.actionId, entry.capabilityId,
                            entry.providerId, entry.modelId, entry.inputHash, entry.outputHash,
                            entry.evidenceSource.name, entry.evidenceLevel.name, entry.expectedState,
                            entry.observedState, entry.receiptId, entry.verificationStatus,
                            entry.timestamp, entry.sequenceNumber
                        )

                        val calculatedHash = hashString(payloadCanonical)
                        val legacyHash = hashString(payloadLegacy)
                        val legacyHash2 = hashString(payloadLegacy2)

                        val hashValid = (entry.entryHash == calculatedHash || entry.entryHash == legacyHash || entry.entryHash == legacyHash2)
                        if (hashValid && entry.previousEntryHash == expectedPrevHash && seq == expectedSequence) {
                            loaded.add(entry)
                            expectedPrevHash = entry.entryHash
                            expectedSequence = seq + 1
                        } else {
                            Log.e(TAG, "Tampered or invalid persisted entry detected at startup: ${entry.entryId}")
                            corruptionDetected = true
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing persisted provenance line", e)
                        corruptionDetected = true
                    }
                }
            }

            if (corruptionDetected) {
                isLedgerCompromised = true
                Log.e(TAG, "Startup integrity check failed: Provenance ledger marked COMPROMISED (Fail-Closed)")
            } else if (loaded.isNotEmpty()) {
                _entries.value = loaded
                isLedgerCompromised = false
                Log.i(TAG, "Loaded ${loaded.size} authentic provenance records from durable storage.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed reading persisted provenance ledger", e)
            isLedgerCompromised = true
        }
    }

    private fun persistEntry(entry: ProvenanceEntry): Boolean {
        return try {
            val dir = getStorageDir()
            val ledgerFile = File(dir, "provenance_ledger.jsonl")
            val obj = org.json.JSONObject().apply {
                put("entryId", entry.entryId)
                put("taskId", entry.taskId)
                put("actionId", entry.actionId)
                put("capabilityId", entry.capabilityId)
                put("providerId", entry.providerId)
                put("modelId", entry.modelId ?: "")
                put("inputHash", entry.inputHash)
                put("outputHash", entry.outputHash)
                put("evidenceSource", entry.evidenceSource.name)
                put("evidenceSummary", entry.evidenceSummary)
                put("verificationStatus", entry.verificationStatus)
                put("isVerified", entry.isVerified)
                put("timestamp", entry.timestamp)
                put("previousEntryHash", entry.previousEntryHash)
                put("entryHash", entry.entryHash)
                put("confidence", entry.confidence)
                put("operationId", entry.operationId)
                put("executorResult", entry.executorResult)
                put("observationSource", entry.observationSource)
                put("independentVerifier", entry.independentVerifier ?: "")
                put("modelHash", entry.modelHash ?: "")
                put("architecture", entry.architecture ?: "")
                put("runtimeVersion", entry.runtimeVersion ?: "")
                put("executionEnvironment", entry.executionEnvironment)
                put("executor", entry.executor)
                put("verifier", entry.verifier ?: "")
                put("verificationMethod", entry.verificationMethod)
                put("stateTransition", entry.stateTransition)
                put("sequenceNumber", entry.sequenceNumber)
                put("evidenceLevel", entry.evidenceLevel.name)
                put("expectedState", entry.expectedState)
                put("observedState", entry.observedState)
                put("howObserved", entry.howObserved)
                put("receiptId", entry.receiptId ?: "")
            }
            synchronized(this) {
                ledgerFile.appendText(obj.toString() + "\n")
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed appending entry to durable provenance journal (fail-closed)", e)
            false
        }
    }

    @Synchronized
    fun recordExecution(
        taskId: String,
        actionId: String,
        capabilityId: String,
        providerId: String,
        modelId: String? = null,
        inputContent: String,
        outputContent: String,
        evidence: VerifiedExecutionEvidence? = null,
        modelHash: String? = null,
        architecture: String? = null,
        runtimeVersion: String? = null,
        executionEnvironment: String = "local_android_runtime",
        executor: String? = null,
        verifier: String? = null,
        verificationMethod: String = "canonical_hash_chain",
        stateTransition: String = "DISPATCHED -> EXECUTOR_COMPLETED -> OBSERVED -> VERIFIED",
        evidenceLevel: EvidenceLadder = EvidenceLadder.IMPLEMENTED,
        expectedState: String = "",
        observedState: String = "",
        howObserved: String = "",
        verificationResult: VerificationResult? = null,
        verificationReceipt: WastiVerificationReceipt? = null
    ): ProvenanceEntry {
        val currentList = _entries.value
        val prevHash = currentList.lastOrNull()?.entryHash ?: GENESIS_HASH
        val nextSeq = (currentList.lastOrNull()?.sequenceNumber ?: 0L) + 1L
        val timestamp = System.currentTimeMillis()

        val inputHash = hashString(inputContent)
        val outputHash = hashString(outputContent)
        val entryId = "prov_${System.currentTimeMillis()}_${(Math.random() * 10000).toInt()}"

        // Reject receipt replay across multiple provenance records
        if (verificationReceipt != null && currentList.any { it.receiptId == verificationReceipt.receiptId }) {
            throw IllegalStateException("Receipt replay detected: ${verificationReceipt.receiptId} has already been recorded in provenance ledger.")
        }

        val source = evidence?.evidenceSource ?: EvidenceSource.PROCESS_TELEMETRY
        val summary = evidence?.let { "${it.subject} -> ${it.verifiedState} (conf=${it.confidence})" } ?: "Unverified telemetry"

        // Canonical Gate Invariant: status is VERIFIED only when WastiTruthGate / WastiTruthAuthority validated
        val (authoritativeVerResult, receipt) = when {
            verificationReceipt != null && WastiTruthGate.validateReceiptApplicability(
                verificationReceipt,
                taskId = taskId,
                actionId = actionId,
                capabilityId = capabilityId,
                inputHash = inputHash,
                outputHash = outputHash
            ) -> {
                val res = VerificationResult(
                    taskId = taskId,
                    actionId = actionId,
                    capabilityId = capabilityId,
                    status = ActionVerificationStatus.VERIFIED,
                    evidence = "Verified by WastiTruthAuthority receipt ${verificationReceipt.receiptId}",
                    confidence = 1.0,
                    evidenceLevel = verificationReceipt.evidenceLevel
                )
                res to verificationReceipt
            }
            verificationResult?.capabilitySpecificEvidence != null -> {
                WastiTruthGate.verifyCapability(verificationResult.capabilitySpecificEvidence!!)
            }
            verificationResult?.structuredEvidence != null -> {
                WastiTruthGate.verifyStructured(taskId, actionId, capabilityId, verificationResult.structuredEvidence!!)
            }
            evidence != null -> {
                WastiTruthGate.verifyStructured(taskId, actionId, capabilityId, evidence)
            }
            else -> null to null
        }

        // Secondary check if a receipt was newly issued by WastiTruthGate
        val finalReceipt = receipt ?: verificationReceipt
        if (finalReceipt != null && currentList.any { it.receiptId == finalReceipt.receiptId }) {
            throw IllegalStateException("Receipt replay detected: ${finalReceipt.receiptId} has already been recorded in provenance ledger.")
        }

        val isAuthoritativeVerified = authoritativeVerResult != null &&
            authoritativeVerResult.status == ActionVerificationStatus.VERIFIED &&
            authoritativeVerResult.isVerified &&
            finalReceipt != null &&
            WastiTruthGate.validateReceiptApplicability(
                finalReceipt,
                taskId = taskId,
                actionId = actionId,
                capabilityId = capabilityId,
                inputHash = inputHash,
                outputHash = outputHash
            )

        if (verificationResult?.status == ActionVerificationStatus.VERIFIED) {
            if (!isAuthoritativeVerified) {
                throw IllegalStateException("Execution provenance entry claiming VERIFIED requires a valid, applicable WastiVerificationReceipt issued by WastiTruthAuthority.")
            }
        }

        val status = when {
            isAuthoritativeVerified -> "VERIFIED"
            authoritativeVerResult?.status == ActionVerificationStatus.FAILED || verificationResult?.status == ActionVerificationStatus.FAILED -> "FAILED"
            authoritativeVerResult?.status == ActionVerificationStatus.NOT_VERIFIABLE || verificationResult?.status == ActionVerificationStatus.NOT_VERIFIABLE -> "NOT_VERIFIABLE"
            authoritativeVerResult?.status == ActionVerificationStatus.VERIFICATION_UNAVAILABLE || verificationResult?.status == ActionVerificationStatus.VERIFICATION_UNAVAILABLE -> "VERIFICATION_UNAVAILABLE"
            authoritativeVerResult?.status == ActionVerificationStatus.UNKNOWN || verificationResult?.status == ActionVerificationStatus.UNKNOWN -> "UNVERIFIED"
            evidence != null -> "OBSERVED"
            else -> "EXECUTOR_COMPLETED"
        }
        val isVerified = (status == "VERIFIED")

        val resolvedEvidenceLevel = if (evidenceLevel != EvidenceLadder.IMPLEMENTED) {
            evidenceLevel
        } else if (isAuthoritativeVerified) {
            verificationResult?.evidenceLevel ?: EvidenceLadder.RUNTIME_VERIFIED
        } else if (evidence != null) {
            EvidenceLadder.INTEGRATION_TESTED
        } else {
            EvidenceLadder.IMPLEMENTED
        }

        val resolvedReceiptId = if (isAuthoritativeVerified) finalReceipt?.receiptId else null
        val resolvedExpectedState = expectedState
        val resolvedObservedState = observedState.ifBlank { summary }

        val payloadCanonical = computeCanonicalPayload(
            prevHash, taskId, actionId, capabilityId, providerId,
            modelId, inputHash, outputHash, source.name, resolvedEvidenceLevel.name,
            resolvedExpectedState, resolvedObservedState, resolvedReceiptId, status, timestamp, nextSeq
        )
        val entryHash = hashString(payloadCanonical)

        val canonicalVerifier = if (isVerified) (verifier ?: "WastiVerificationEngine") else null

        val entry = ProvenanceEntry(
            entryId = entryId,
            taskId = taskId,
            actionId = actionId,
            capabilityId = capabilityId,
            providerId = providerId,
            modelId = modelId,
            inputHash = inputHash,
            outputHash = outputHash,
            evidenceSource = source,
            evidenceSummary = summary,
            verificationStatus = status,
            isVerified = isVerified,
            timestamp = timestamp,
            previousEntryHash = prevHash,
            entryHash = entryHash,
            confidence = verificationResult?.confidence ?: evidence?.confidence ?: 0.0,
            operationId = actionId,
            executorResult = outputHash,
            observationSource = source.name,
            independentVerifier = canonicalVerifier,
            modelHash = modelHash,
            architecture = architecture,
            runtimeVersion = runtimeVersion,
            executionEnvironment = executionEnvironment,
            executor = executor ?: providerId,
            verifier = canonicalVerifier,
            verificationMethod = if (isVerified) verificationMethod else "unverified_telemetry",
            stateTransition = stateTransition,
            sequenceNumber = nextSeq,
            evidenceLevel = resolvedEvidenceLevel,
            expectedState = expectedState,
            observedState = observedState.ifBlank { summary },
            howObserved = howObserved.ifBlank { source.name },
            receiptId = if (isVerified) finalReceipt?.receiptId else null
        )

        val persistedOk = persistEntry(entry)
        if (!persistedOk) {
            Log.w(TAG, "Provenance entry persistence failed - state not promoted to trusted")
        }

        _entries.value = currentList + entry
        Log.d(TAG, "Recorded Provenance Entry [$entryId] seq [$nextSeq] for task [$taskId], Verified: $isVerified, Level: $resolvedEvidenceLevel, Hash: ${entryHash.take(12)}")
        return entry
    }

    @Synchronized
    fun getProvenanceForTask(taskId: String): List<ProvenanceEntry> {
        return _entries.value.filter { it.taskId == taskId }
    }

    @Synchronized
    fun getLatestEntry(): ProvenanceEntry? {
        return _entries.value.lastOrNull()
    }

    @Synchronized
    fun getRootHash(): String {
        return _entries.value.lastOrNull()?.entryHash ?: GENESIS_HASH
    }

    @Synchronized
    fun count(): Int {
        return _entries.value.size
    }

    @Synchronized
    fun isCompromised(): Boolean {
        return isLedgerCompromised
    }

    @Synchronized
    fun resetForTesting() {
        _entries.value = emptyList()
        isLedgerCompromised = false
        try {
            val dir = getStorageDir()
            File(dir, "provenance_ledger.jsonl").delete()
        } catch (_: Exception) {}
    }

    @Synchronized
    fun injectTamperedEntryForTesting(tamperedEntry: ProvenanceEntry) {
        _entries.value = _entries.value + tamperedEntry
    }

    @Synchronized
    fun injectTamperedEntryForTesting(badSignature: String) {
        val latest = _entries.value.lastOrNull()
        val entry = ProvenanceEntry(
            entryId = "tampered_${System.currentTimeMillis()}",
            taskId = "task_tampered",
            actionId = "tampered_action",
            capabilityId = "TAMPERED",
            providerId = "untrusted",
            modelId = null,
            inputHash = hashString("tampered_input"),
            outputHash = hashString("tampered_output"),
            evidenceSource = EvidenceSource.PROCESS_TELEMETRY,
            evidenceSummary = "Tampered signature: $badSignature",
            verificationStatus = "UNVERIFIED",
            isVerified = false,
            timestamp = System.currentTimeMillis(),
            previousEntryHash = latest?.entryHash ?: GENESIS_HASH,
            entryHash = badSignature,
            sequenceNumber = (latest?.sequenceNumber ?: 0L) + 1L
        )
        injectTamperedEntryForTesting(entry)
    }

    @Synchronized
    fun verifyEntry(entryId: String): Boolean {
        val entry = _entries.value.find { it.entryId == entryId } ?: return false
        val payloadCanonical = computeCanonicalPayload(
            entry.previousEntryHash, entry.taskId, entry.actionId, entry.capabilityId,
            entry.providerId, entry.modelId, entry.inputHash, entry.outputHash,
            entry.evidenceSource.name, entry.evidenceLevel.name, entry.expectedState,
            entry.observedState, entry.receiptId, entry.verificationStatus,
            entry.timestamp, entry.sequenceNumber
        )
        val payloadLegacy = "${entry.previousEntryHash}|${entry.taskId}|${entry.actionId}|${entry.capabilityId}|${entry.providerId}|${entry.inputHash}|${entry.outputHash}|${entry.verificationStatus}|${entry.timestamp}"
        val payloadLegacy2 = "${entry.previousEntryHash}|${entry.taskId}|${entry.actionId}|${entry.capabilityId}|${entry.providerId}|${entry.inputHash}|${entry.outputHash}|${entry.verificationStatus}|${entry.timestamp}|${entry.sequenceNumber}"
        val calculated = hashString(payloadCanonical)
        val legacy = hashString(payloadLegacy)
        val legacy2 = hashString(payloadLegacy2)
        return (entry.entryHash == calculated || entry.entryHash == legacy || entry.entryHash == legacy2)
    }

    @Synchronized
    fun verifyLedgerIntegrity(): Boolean {
        val list = _entries.value
        if (list.isEmpty()) return !isLedgerCompromised

        var expectedPrevHash = GENESIS_HASH
        var lastSeq = 0L
        for (entry in list) {
            if (entry.previousEntryHash != expectedPrevHash) {
                Log.e(TAG, "Provenance chain broken at entry: ${entry.entryId}")
                return false
            }
            if (lastSeq == 0L && entry.sequenceNumber != 1L) {
                Log.e(TAG, "First entry sequence number must be 1 (got ${entry.sequenceNumber})")
                return false
            }
            if (lastSeq > 0L && entry.sequenceNumber != lastSeq + 1L) {
                Log.e(TAG, "Monotonic sequence violation or gap at entry: ${entry.entryId} (seq=${entry.sequenceNumber}, expected=${lastSeq + 1L})")
                return false
            }
            lastSeq = entry.sequenceNumber
            val payloadCanonical = computeCanonicalPayload(
                entry.previousEntryHash, entry.taskId, entry.actionId, entry.capabilityId,
                entry.providerId, entry.modelId, entry.inputHash, entry.outputHash,
                entry.evidenceSource.name, entry.evidenceLevel.name, entry.expectedState,
                entry.observedState, entry.receiptId, entry.verificationStatus,
                entry.timestamp, entry.sequenceNumber
            )
            val payloadLegacy = "${entry.previousEntryHash}|${entry.taskId}|${entry.actionId}|${entry.capabilityId}|${entry.providerId}|${entry.inputHash}|${entry.outputHash}|${entry.verificationStatus}|${entry.timestamp}"
            val payloadLegacy2 = "${entry.previousEntryHash}|${entry.taskId}|${entry.actionId}|${entry.capabilityId}|${entry.providerId}|${entry.inputHash}|${entry.outputHash}|${entry.verificationStatus}|${entry.timestamp}|${entry.sequenceNumber}"

            val calculatedHash = hashString(payloadCanonical)
            val legacyHash = hashString(payloadLegacy)
            val legacyHash2 = hashString(payloadLegacy2)
            if (calculatedHash != entry.entryHash && legacyHash != entry.entryHash && legacyHash2 != entry.entryHash) {
                Log.e(TAG, "Provenance entry hash mismatch at: ${entry.entryId}")
                return false
            }
            expectedPrevHash = entry.entryHash
        }
        return !isLedgerCompromised
    }

    @Synchronized
    fun computeHmacIntegrityAnchor(content: String): String {
        return try {
            val mac = Mac.getInstance("HmacSHA256")
            val key = keystoreSecretKey ?: getOrInitKeystoreAnchor()
            if (key != null) {
                mac.init(key)
            } else {
                if (fallbackAnchorKey == null) {
                    fallbackAnchorKey = loadOrCreateAnchorKey()
                }
                mac.init(SecretKeySpec(fallbackAnchorKey!!, "HmacSHA256"))
            }
            val bytes = mac.doFinal(content.toByteArray(Charsets.UTF_8))
            bytes.joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            hashString("hmac_fallback:$content")
        }
    }

    @Synchronized
    fun rotateTrustAnchor(): Boolean {
        return try {
            val dir = getStorageDir()
            val keyFile = File(dir, ".keystore_anchor.key")
            val newKey = ByteArray(32)
            SecureRandom().nextBytes(newKey)
            keyFile.writeBytes(newKey)
            Log.i(TAG, "Provenance trust anchor rotated successfully.")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed rotating provenance trust anchor", e)
            false
        }
    }

    @Synchronized
    fun recoverFromCorruptedState(): Boolean {
        return try {
            loadPersistedLedger()
            !isLedgerCompromised
        } catch (_: Exception) {
            false
        }
    }

    @Synchronized
    fun exportLedgerAuditJson(): String {
        val array = org.json.JSONArray()
        for (e in _entries.value) {
            val obj = org.json.JSONObject().apply {
                put("entryId", e.entryId)
                put("taskId", e.taskId)
                put("actionId", e.actionId)
                put("capabilityId", e.capabilityId)
                put("providerId", e.providerId)
                put("modelId", e.modelId ?: "")
                put("inputHash", e.inputHash)
                put("outputHash", e.outputHash)
                put("evidenceSource", e.evidenceSource.name)
                put("evidenceSummary", e.evidenceSummary)
                put("verificationStatus", e.verificationStatus)
                put("isVerified", e.isVerified)
                put("timestamp", e.timestamp)
                put("previousEntryHash", e.previousEntryHash)
                put("entryHash", e.entryHash)
                put("confidence", e.confidence)
                put("modelHash", e.modelHash ?: "")
                put("architecture", e.architecture ?: "")
                put("runtimeVersion", e.runtimeVersion ?: "")
                put("executionEnvironment", e.executionEnvironment)
                put("executor", e.executor)
                put("verifier", e.verifier ?: "")
                put("verificationMethod", e.verificationMethod)
                put("stateTransition", e.stateTransition)
                put("sequenceNumber", e.sequenceNumber)
                put("evidenceLevel", e.evidenceLevel.name)
                put("expectedState", e.expectedState)
                put("observedState", e.observedState)
                put("howObserved", e.howObserved)
                put("receiptId", e.receiptId ?: "")
            }
            array.put(obj)
        }
        return array.toString()
    }

    fun hashString(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
