package com.example.data.agent.runtime

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    val entryHash: String
)

/**
 * Hash-chained Execution Provenance Ledger ensuring cryptographic traceability for all actions.
 */
object ExecutionProvenanceLedger {
    private const val TAG = "ExecutionProvenanceLedger"
    private const val GENESIS_HASH = "0000000000000000000000000000000000000000000000000000000000000000"

    private val _entries = MutableStateFlow<List<ProvenanceEntry>>(emptyList())
    val entries: StateFlow<List<ProvenanceEntry>> = _entries.asStateFlow()

    @Synchronized
    fun recordExecution(
        taskId: String,
        actionId: String,
        capabilityId: String,
        providerId: String,
        modelId: String? = null,
        inputContent: String,
        outputContent: String,
        evidence: VerifiedExecutionEvidence?
    ): ProvenanceEntry {
        val currentList = _entries.value
        val prevHash = currentList.lastOrNull()?.entryHash ?: GENESIS_HASH
        val timestamp = System.currentTimeMillis()

        val inputHash = hashString(inputContent)
        val outputHash = hashString(outputContent)
        val entryId = "prov_${System.currentTimeMillis()}_${(Math.random() * 10000).toInt()}"

        val source = evidence?.evidenceSource ?: EvidenceSource.PROCESS_TELEMETRY
        val summary = evidence?.let { "${it.subject} -> ${it.verifiedState} (conf=${it.confidence})" } ?: "Unverified telemetry"
        val status = if (evidence != null && evidence.confidence >= 0.85) "VERIFIED" else "OBSERVED"
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
            entryHash = entryHash
        )

        _entries.value = currentList + entry
        Log.d(TAG, "Recorded Provenance Entry [$entryId] for task [$taskId], Verified: $isVerified, Hash: ${entryHash.take(12)}")
        return entry
    }

    fun getProvenanceForTask(taskId: String): List<ProvenanceEntry> {
        return _entries.value.filter { it.taskId == taskId }
    }

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

    private fun hashString(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
