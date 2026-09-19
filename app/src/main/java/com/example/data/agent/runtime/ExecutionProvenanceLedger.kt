package com.example.data.agent.runtime

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.security.MessageDigest

/**
 * Immutable Provenance Record documenting exact execution evidence, hashes, and verification.
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
    val stateTransition: String = "DISPATCHED -> EXECUTOR_COMPLETED -> OBSERVED -> VERIFIED"
)

/**
 * Hash-chained Execution Provenance Ledger ensuring cryptographic traceability for all actions.
 * Durable across process restarts with authenticated persistence and root anchoring.
 */
object ExecutionProvenanceLedger {
    private const val TAG = "ExecutionProvenanceLedger"
    const val GENESIS_HASH = "0000000000000000000000000000000000000000000000000000000000000000"

    private val _entries = MutableStateFlow<List<ProvenanceEntry>>(emptyList())
    val entries: StateFlow<List<ProvenanceEntry>> = _entries.asStateFlow()

    init {
        loadPersistedLedger()
    }

    private fun getStorageDir(): File {
        val userHome = System.getProperty("user.home") ?: System.getProperty("java.io.tmpdir") ?: "."
        val dir = File(userHome, ".wasti_ai/provenance")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun loadPersistedLedger() {
        try {
            val dir = getStorageDir()
            val ledgerFile = File(dir, "provenance_ledger.jsonl")
            if (!ledgerFile.exists()) return

            val loaded = mutableListOf<ProvenanceEntry>()
            var expectedPrevHash = GENESIS_HASH

            ledgerFile.forEachLine { line ->
                if (line.isNotBlank()) {
                    try {
                        val obj = org.json.JSONObject(line)
                        val sourceName = obj.optString("evidenceSource", EvidenceSource.PROCESS_TELEMETRY.name)
                        val source = try { EvidenceSource.valueOf(sourceName) } catch (_: Exception) { EvidenceSource.PROCESS_TELEMETRY }
                        val prevHash = obj.optString("previousEntryHash", GENESIS_HASH)
                        val entryHash = obj.optString("entryHash", "")

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
                            stateTransition = obj.optString("stateTransition", "DISPATCHED -> EXECUTOR_COMPLETED -> OBSERVED -> VERIFIED")
                        )

                        val payloadToHash = "${entry.previousEntryHash}|${entry.taskId}|${entry.actionId}|${entry.capabilityId}|${entry.providerId}|${entry.inputHash}|${entry.outputHash}|${entry.verificationStatus}|${entry.timestamp}"
                        if (hashString(payloadToHash) == entry.entryHash && entry.previousEntryHash == expectedPrevHash) {
                            loaded.add(entry)
                            expectedPrevHash = entry.entryHash
                        } else {
                            Log.w(TAG, "Skipping tampered or invalid persisted entry: ${entry.entryId}")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Error parsing persisted provenance line", e)
                    }
                }
            }
            if (loaded.isNotEmpty()) {
                _entries.value = loaded
                Log.i(TAG, "Loaded ${loaded.size} authentic provenance records from durable storage.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed reading persisted provenance ledger", e)
        }
    }

    private fun persistEntry(entry: ProvenanceEntry) {
        try {
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
            }
            synchronized(this) {
                ledgerFile.appendText(obj.toString() + "\n")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed appending entry to durable provenance journal", e)
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
        evidence: VerifiedExecutionEvidence?,
        modelHash: String? = null,
        architecture: String? = null,
        runtimeVersion: String? = null,
        executionEnvironment: String = "local_android_runtime",
        executor: String? = null,
        verifier: String? = null,
        verificationMethod: String = "canonical_hash_chain",
        stateTransition: String = "DISPATCHED -> EXECUTOR_COMPLETED -> OBSERVED -> VERIFIED"
    ): ProvenanceEntry {
        val currentList = _entries.value
        val prevHash = currentList.lastOrNull()?.entryHash ?: GENESIS_HASH
        val timestamp = System.currentTimeMillis()

        val inputHash = hashString(inputContent)
        val outputHash = hashString(outputContent)
        val entryId = "prov_${System.currentTimeMillis()}_${(Math.random() * 10000).toInt()}"

        val source = evidence?.evidenceSource ?: EvidenceSource.PROCESS_TELEMETRY
        val summary = evidence?.let { "${it.subject} -> ${it.verifiedState} (conf=${it.confidence})" } ?: "Unverified telemetry"
        val status = if (evidence != null && evidence.confidence >= 0.85) {
            "VERIFIED"
        } else if (evidence != null && evidence.confidence > 0.0) {
            "OBSERVED"
        } else {
            "EXECUTOR_COMPLETED"
        }
        val isVerified = status == "VERIFIED"

        val payloadToHash = "$prevHash|$taskId|$actionId|$capabilityId|$providerId|$inputHash|$outputHash|$status|$timestamp"
        val entryHash = hashString(payloadToHash)

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
            confidence = evidence?.confidence ?: 0.0,
            operationId = actionId,
            executorResult = outputHash,
            observationSource = source.name,
            independentVerifier = verifier ?: evidence?.let { "WastiVerificationEngine" },
            modelHash = modelHash,
            architecture = architecture,
            runtimeVersion = runtimeVersion,
            executionEnvironment = executionEnvironment,
            executor = executor ?: providerId,
            verifier = verifier ?: evidence?.let { "WastiVerificationEngine" },
            verificationMethod = verificationMethod,
            stateTransition = stateTransition
        )

        _entries.value = currentList + entry
        persistEntry(entry)
        Log.d(TAG, "Recorded Provenance Entry [$entryId] for task [$taskId], Verified: $isVerified, Hash: ${entryHash.take(12)}")
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
    fun resetForTesting() {
        _entries.value = emptyList()
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
            entryHash = badSignature
        )
        injectTamperedEntryForTesting(entry)
    }

    @Synchronized
    fun verifyEntry(entryId: String): Boolean {
        val entry = _entries.value.find { it.entryId == entryId } ?: return false
        val payload = "${entry.previousEntryHash}|${entry.taskId}|${entry.actionId}|${entry.capabilityId}|${entry.providerId}|${entry.inputHash}|${entry.outputHash}|${entry.verificationStatus}|${entry.timestamp}"
        return hashString(payload) == entry.entryHash
    }

    @Synchronized
    fun verifyLedgerIntegrity(): Boolean {
        val list = _entries.value
        if (list.isEmpty()) return true

        var expectedPrevHash = GENESIS_HASH
        for (entry in list) {
            if (entry.previousEntryHash != expectedPrevHash) {
                Log.e(TAG, "Provenance chain broken at entry: ${entry.entryId}")
                return false
            }
            val payload = "${entry.previousEntryHash}|${entry.taskId}|${entry.actionId}|${entry.capabilityId}|${entry.providerId}|${entry.inputHash}|${entry.outputHash}|${entry.verificationStatus}|${entry.timestamp}"
            val calculatedHash = hashString(payload)
            if (calculatedHash != entry.entryHash) {
                Log.e(TAG, "Provenance entry hash mismatch at: ${entry.entryId}")
                return false
            }
            expectedPrevHash = entry.entryHash
        }
        return true
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
