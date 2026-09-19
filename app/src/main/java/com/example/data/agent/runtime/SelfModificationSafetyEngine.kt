package com.example.data.agent.runtime

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * [P0-40] Self-Modification Safety Engine
 *
 * Enforces strict bounded policy, protected paths, staged verification,
 * rollback, loop detection, and explicit human/admin authorization for
 * autonomous code generation and self-repair.
 *
 * Invariant: Never allow autonomous modification of security core,
 * release signing, or protected configuration without explicit admin authorization.
 */

enum class ModificationDecision {
    ALLOWED,
    BLOCKED_PROTECTED_PATH,
    BLOCKED_LOOP_DETECTED,
    BLOCKED_EMERGENCY_STOP,
    BLOCKED_WORKSPACE_ESCAPE,
    BLOCKED_SIZE_LIMIT_EXCEEDED,
    REQUIRES_ADMIN_AUTHORIZATION
}

enum class ModificationOutcomeStatus {
    APPLIED_VERIFIED,
    APPLIED_UNVERIFIED,
    ROLLED_BACK,
    BLOCKED_POLICY,
    FAILED_IO
}

data class RollbackSnapshot(
    val snapshotId: String = UUID.randomUUID().toString(),
    val filePath: String,
    val originalContent: String,
    val timestamp: Long = System.currentTimeMillis(),
    val contentHash: String,
    val targetExisted: Boolean,
    val generation: Int = 1
)

enum class ProposalAuditAction {
    PROPOSED,
    REJECTED,
    AUTHORIZED,
    APPLIED_VERIFIED,
    APPLIED_UNVERIFIED,
    ROLLED_BACK
}

data class ProposalAuditEntry(
    val id: String = UUID.randomUUID().toString(),
    val proposalId: String,
    val filePath: String,
    val action: ProposalAuditAction,
    val reason: String,
    val contentHash: String,
    val authorizingEntity: String,
    val timestamp: Long = System.currentTimeMillis(),
    val details: String = ""
)

data class ModificationOutcome(
    val status: ModificationOutcomeStatus,
    val filePath: String,
    val decision: ModificationDecision,
    val rollbackPerformed: Boolean = false,
    val errorDetails: String? = null,
    val snapshotId: String? = null
)

data class ProposedModification(
    val id: String = UUID.randomUUID().toString(),
    val filePath: String,
    val originalContent: String,
    val newContent: String,
    val reason: String = "Autonomous repair or code improvement",
    val timestamp: Long = System.currentTimeMillis(),
    val isProtected: Boolean = false,
    val contentHash: String = ""
)

enum class DiffLineType {
    UNCHANGED,
    ADDED,
    DELETED
}

data class DiffLine(
    val type: DiffLineType,
    val text: String,
    val oldLineNumber: Int? = null,
    val newLineNumber: Int? = null
)

object SelfModificationSafetyEngine {

    private const val TAG = "SelfModSafetyEngine"

    private val _pendingProposals = MutableStateFlow<List<ProposedModification>>(emptyList())
    val pendingProposals: StateFlow<List<ProposedModification>> = _pendingProposals.asStateFlow()

    private val _proposalAuditLog = MutableStateFlow<List<ProposalAuditEntry>>(emptyList())
    val proposalAuditLog: StateFlow<List<ProposalAuditEntry>> = _proposalAuditLog.asStateFlow()

    init {
        loadPersistedAuditLog()
    }

    private fun loadPersistedAuditLog() {
        try {
            val jDir = getJournalDir()
            val auditFile = File(jDir, "proposal_audit_log.jsonl")
            if (!auditFile.exists()) return

            val loaded = mutableListOf<ProposalAuditEntry>()
            auditFile.forEachLine { line ->
                if (line.isNotBlank()) {
                    try {
                        val obj = org.json.JSONObject(line)
                        val actionName = obj.getString("action")
                        val action = try { ProposalAuditAction.valueOf(actionName) } catch (_: Exception) { ProposalAuditAction.PROPOSED }
                        loaded.add(
                            ProposalAuditEntry(
                                id = obj.optString("id", UUID.randomUUID().toString()),
                                proposalId = obj.getString("proposalId"),
                                filePath = obj.getString("filePath"),
                                action = action,
                                reason = obj.optString("reason", ""),
                                contentHash = obj.optString("contentHash", ""),
                                authorizingEntity = obj.optString("authorizingEntity", "UNKNOWN"),
                                timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                                details = obj.optString("details", "")
                            )
                        )
                    } catch (_: Exception) {}
                }
            }
            if (loaded.isNotEmpty()) {
                _proposalAuditLog.value = loaded.takeLast(100).reversed()
            }
        } catch (_: Exception) {}
    }

    private fun persistAuditEntry(entry: ProposalAuditEntry) {
        try {
            val jDir = getJournalDir()
            val auditFile = File(jDir, "proposal_audit_log.jsonl")
            val obj = org.json.JSONObject().apply {
                put("id", entry.id)
                put("proposalId", entry.proposalId)
                put("filePath", entry.filePath)
                put("action", entry.action.name)
                put("reason", entry.reason)
                put("contentHash", entry.contentHash)
                put("authorizingEntity", entry.authorizingEntity)
                put("timestamp", entry.timestamp)
                put("details", entry.details)
            }
            synchronized(this) {
                auditFile.appendText(obj.toString() + "\n")
            }
        } catch (_: Exception) {}
    }

    fun recordAudit(
        proposalId: String,
        filePath: String,
        action: ProposalAuditAction,
        reason: String,
        contentHash: String,
        authorizingEntity: String,
        details: String = ""
    ): ProposalAuditEntry {
        val entry = ProposalAuditEntry(
            proposalId = proposalId,
            filePath = filePath,
            action = action,
            reason = reason,
            contentHash = contentHash,
            authorizingEntity = authorizingEntity,
            details = details
        )
        val current = _proposalAuditLog.value.toMutableList()
        current.add(0, entry)
        if (current.size > 100) {
            _proposalAuditLog.value = current.take(100)
        } else {
            _proposalAuditLog.value = current
        }
        persistAuditEntry(entry)

        try {
            ExecutionProvenanceLedger.recordExecution(
                taskId = "audit_${entry.id.take(8)}",
                actionId = "proposal_${action.name.lowercase()}",
                capabilityId = "SELF_MODIFICATION_AUDIT",
                providerId = "SelfModificationSafetyEngine",
                inputContent = "proposalId:$proposalId,path:$filePath,entity:$authorizingEntity",
                outputContent = "action:${action.name},hash:$contentHash,reason:$reason",
                evidence = VerifiedExecutionEvidence(
                    subject = "SelfModificationSafetyEngine",
                    verifiedState = action.name,
                    confidence = 0.95,
                    evidenceSource = EvidenceSource.PROCESS_TELEMETRY
                ),
                executionEnvironment = "local_android_runtime",
                executor = "SelfModificationSafetyEngine",
                verifier = authorizingEntity,
                verificationMethod = "cryptographic_proposal_audit",
                stateTransition = "DISPATCHED -> CONTROLLED_PATCH -> AUDIT_${action.name}"
            )
        } catch (_: Throwable) {}

        return entry
    }

    fun proposeModification(
        filePath: String,
        newContent: String,
        reason: String = "Autonomous code modification"
    ): ProposedModification {
        val file = File(filePath)
        val orig = if (file.exists()) file.readText() else ""
        val isProt = isProtectedPath(filePath)
        val prop = ProposedModification(
            filePath = filePath,
            originalContent = orig,
            newContent = newContent,
            reason = reason,
            isProtected = isProt,
            contentHash = computeHash(newContent)
        )
        val current = _pendingProposals.value.toMutableList()
        current.removeAll { it.filePath == filePath }
        current.add(0, prop)
        _pendingProposals.value = current

        recordAudit(
            proposalId = prop.id,
            filePath = filePath,
            action = ProposalAuditAction.PROPOSED,
            reason = reason,
            contentHash = prop.contentHash,
            authorizingEntity = "AUTONOMOUS_ENGINE"
        )
        return prop
    }

    fun rejectProposal(proposalId: String, reason: String = "User rejected"): Boolean {
        val current = _pendingProposals.value.toMutableList()
        val proposal = current.find { it.id == proposalId }
        val removed = current.removeIf { it.id == proposalId }
        if (removed) {
            _pendingProposals.value = current
            recordAudit(
                proposalId = proposalId,
                filePath = proposal?.filePath ?: "unknown",
                action = ProposalAuditAction.REJECTED,
                reason = reason,
                contentHash = proposal?.contentHash ?: "",
                authorizingEntity = "HUMAN_OPERATOR"
            )
            Log.i(TAG, "Proposal $proposalId rejected: $reason")
        }
        return removed
    }

    fun authorizeAndApply(
        proposalId: String,
        adminToken: String? = null,
        stagedValidator: ((File) -> Boolean)? = null
    ): ModificationOutcome {
        val proposal = _pendingProposals.value.find { it.id == proposalId }
            ?: return ModificationOutcome(
                status = ModificationOutcomeStatus.BLOCKED_POLICY,
                filePath = "",
                decision = ModificationDecision.REQUIRES_ADMIN_AUTHORIZATION,
                errorDetails = "Proposal $proposalId not found"
            )

        val targetFile = File(proposal.filePath)
        val outcome = executeModificationWithStagedRollback(
            targetFile = targetFile,
            newContent = proposal.newContent,
            isAutonomous = false,
            adminAuthToken = adminToken,
            stagedValidator = stagedValidator
        )

        if (outcome.status == ModificationOutcomeStatus.APPLIED_VERIFIED ||
            outcome.status == ModificationOutcomeStatus.APPLIED_UNVERIFIED
        ) {
            val current = _pendingProposals.value.toMutableList()
            current.removeAll { it.id == proposalId }
            _pendingProposals.value = current
        }

        val auditAction = when (outcome.status) {
            ModificationOutcomeStatus.APPLIED_VERIFIED -> ProposalAuditAction.APPLIED_VERIFIED
            ModificationOutcomeStatus.APPLIED_UNVERIFIED -> ProposalAuditAction.APPLIED_UNVERIFIED
            ModificationOutcomeStatus.ROLLED_BACK -> ProposalAuditAction.ROLLED_BACK
            else -> ProposalAuditAction.REJECTED
        }

        recordAudit(
            proposalId = proposalId,
            filePath = proposal.filePath,
            action = auditAction,
            reason = proposal.reason,
            contentHash = proposal.contentHash,
            authorizingEntity = if (adminToken != null) "ADMIN_TOKEN_HOLDER" else "HUMAN_OPERATOR",
            details = "Outcome: ${outcome.status.name} (${outcome.errorDetails ?: "OK"})"
        )

        return outcome
    }

    fun computeDiff(original: String, modified: String): List<DiffLine> {
        val origLines = if (original.isEmpty()) emptyList() else original.lines()
        val modLines = if (modified.isEmpty()) emptyList() else modified.lines()
        val n = origLines.size
        val m = modLines.size

        if (n == 0 && m == 0) return emptyList()
        if (n == 0) return modLines.mapIndexed { idx, line -> DiffLine(DiffLineType.ADDED, line, null, idx + 1) }
        if (m == 0) return origLines.mapIndexed { idx, line -> DiffLine(DiffLineType.DELETED, line, idx + 1, null) }

        // LCS matrix
        val lcs = Array(n + 1) { IntArray(m + 1) }
        for (i in 1..n) {
            for (j in 1..m) {
                if (origLines[i - 1] == modLines[j - 1]) {
                    lcs[i][j] = lcs[i - 1][j - 1] + 1
                } else {
                    lcs[i][j] = maxOf(lcs[i - 1][j], lcs[i][j - 1])
                }
            }
        }

        // Backtrack to build ordered DiffLine sequence
        var i = n
        var j = m
        val reverseDiff = mutableListOf<DiffLine>()
        while (i > 0 || j > 0) {
            if (i > 0 && j > 0 && origLines[i - 1] == modLines[j - 1]) {
                reverseDiff.add(DiffLine(DiffLineType.UNCHANGED, origLines[i - 1], i, j))
                i--
                j--
            } else if (j > 0 && (i == 0 || lcs[i][j - 1] >= lcs[i - 1][j])) {
                reverseDiff.add(DiffLine(DiffLineType.ADDED, modLines[j - 1], null, j))
                j--
            } else if (i > 0 && (j == 0 || lcs[i][j - 1] < lcs[i - 1][j])) {
                reverseDiff.add(DiffLine(DiffLineType.DELETED, origLines[i - 1], i, null))
                i--
            }
        }
        reverseDiff.reverse()
        return reverseDiff
    }

    // Maximum mutations permitted on a single file within the sliding window
    private const val MAX_MUTATIONS_PER_WINDOW = 3
    private const val MUTATION_WINDOW_MS = 30 * 60 * 1000L // 30 minutes
    private const val MAX_FILE_SIZE_BYTES = 500 * 1024L // 500 KB limit for self-modification

    // Protected paths and capabilities that can NEVER be modified autonomously without explicit admin authorization
    private val PROTECTED_SUBSTRINGS = listOf(
        "security/",
        "credential/",
        "ProductionReadinessGate",
        "PermissionManager",
        "WastiEmergencyStopController",
        "ZeroTrustSentinelEngine",
        "SelfModificationSafetyEngine",
        "terminal",
        "root_shell",
        "accessibility_service",
        "system_settings",
        "build.gradle",
        "settings.gradle",
        "androidmanifest.xml",
        "proguard-rules.pro",
        ".github/workflows",
        ".env",
        "keystore",
        ".jks",
        ".pem",
        ".key"
    )

    // Modification history: filePath -> list of (timestamp, hash)
    private val mutationHistory = ConcurrentHashMap<String, MutableList<Pair<Long, String>>>()

    // Rollback storage: snapshotId -> RollbackSnapshot
    private val rollbackVault = ConcurrentHashMap<String, RollbackSnapshot>()

    // Multi-generation rollback storage: filePath -> List<RollbackSnapshot> (bounded at 3 generations)
    private val multiGenerationVault = ConcurrentHashMap<String, MutableList<RollbackSnapshot>>()

    fun getSnapshotsForFile(filePath: String): List<RollbackSnapshot> {
        val list = multiGenerationVault[filePath] ?: return emptyList()
        synchronized(list) {
            return list.toList()
        }
    }

    // Admin authorization tokens: valid active tokens. No implicit/default authority is ever created.
    private val activeAdminTokens = ConcurrentHashMap.newKeySet<String>()

    fun registerAdminToken(token: String) {
        if (token.isNotBlank()) {
            activeAdminTokens.add(token)
        }
    }

    fun revokeAdminToken(token: String) {
        activeAdminTokens.remove(token)
    }

    fun isValidAdminToken(token: String?): Boolean {
        if (token.isNullOrBlank()) return false
        return activeAdminTokens.contains(token)
    }

    /**
     * Checks whether a target file path is protected under the safety policy.
     */
    fun isProtectedPath(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        val normalized = path.replace("\\", "/").lowercase()
        return PROTECTED_SUBSTRINGS.any { normalized.contains(it.lowercase()) }
    }

    /**
     * Calculates deterministic SHA-256 for mutation tracking.
     */
    fun computeHash(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(content.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Detects oscillating or infinite self-repair loops on a specific target file.
     */
    fun isMutationLoopDetected(filePath: String, newContentHash: String): Boolean {
        val history = mutationHistory[filePath] ?: return false
        synchronized(history) {
            val now = System.currentTimeMillis()

            // Prune entries older than sliding window
            history.removeAll { now - it.first > MUTATION_WINDOW_MS }

            // Rule 1: Exceeded maximum attempts in sliding window
            if (history.size >= MAX_MUTATIONS_PER_WINDOW) {
                Log.w(TAG, "Mutation loop detected on $filePath: Exceeded $MAX_MUTATIONS_PER_WINDOW mutations in 30min")
                return true
            }

            // Rule 2: Oscillation detection (re-attempting exact content previously tried)
            val identicalPastAttempts = history.count { it.second == newContentHash }
            if (identicalPastAttempts >= 2) {
                Log.w(TAG, "Oscillation loop detected on $filePath: Proposing identical hash $newContentHash repeatedly")
                return true
            }

            return false
        }
    }

    /**
     * Evaluates whether a proposed modification is authorized under policy.
     */
    fun evaluateModification(
        filePath: String,
        newContent: String,
        isAutonomous: Boolean = true,
        adminAuthToken: String? = null
    ): ModificationDecision {
        // 1. Emergency stop check
        if (WastiEmergencyStopController.isEmergencyStopped) {
            return ModificationDecision.BLOCKED_EMERGENCY_STOP
        }

        // 2. Size limit check
        if (newContent.toByteArray(Charsets.UTF_8).size > MAX_FILE_SIZE_BYTES) {
            return ModificationDecision.BLOCKED_SIZE_LIMIT_EXCEEDED
        }

        // 3. Protected path enforcement
        if (isProtectedPath(filePath)) {
            if (isAutonomous && !isValidAdminToken(adminAuthToken)) {
                Log.w(TAG, "Autonomous modification of protected path '$filePath' BLOCKED without admin authorization token")
                return ModificationDecision.BLOCKED_PROTECTED_PATH
            }
        }

        // 4. Mutation loop detection
        val newHash = computeHash(newContent)
        if (isAutonomous && isMutationLoopDetected(filePath, newHash)) {
            return ModificationDecision.BLOCKED_LOOP_DETECTED
        }

        return ModificationDecision.ALLOWED
    }

    private fun getJournalDir(): File {
        val userHome = System.getProperty("user.home") ?: System.getProperty("java.io.tmpdir") ?: "."
        val dir = File(userHome, ".wasti_ai/snapshots")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Creates a rollback snapshot of a file prior to modification with durable disk persistence.
     * Maintains a rolling 3-generation snapshot ring buffer per file.
     */
    fun createRollbackSnapshot(targetFile: File): RollbackSnapshot {
        val existed = targetFile.exists()
        val originalText = if (existed) targetFile.readText() else ""
        val snapshot = RollbackSnapshot(
            filePath = targetFile.absolutePath,
            originalContent = originalText,
            contentHash = computeHash(originalText),
            targetExisted = existed,
            generation = 1
        )
        rollbackVault[snapshot.snapshotId] = snapshot

        val list = multiGenerationVault.computeIfAbsent(targetFile.absolutePath) { mutableListOf() }
        synchronized(list) {
            val updated = mutableListOf<RollbackSnapshot>()
            updated.add(snapshot)
            for (idx in 0 until minOf(2, list.size)) {
                val older = list[idx].copy(generation = idx + 2)
                updated.add(older)
            }
            list.clear()
            list.addAll(updated)
        }

        // Durable snapshot journal persistence using atomic writes
        try {
            val jDir = getJournalDir()
            val metaFile = File(jDir, "${snapshot.snapshotId}.meta")
            val contentFile = File(jDir, "${snapshot.snapshotId}.content")
            val tempMeta = File(jDir, "${snapshot.snapshotId}.meta.tmp_${System.currentTimeMillis()}")
            val tempContent = File(jDir, "${snapshot.snapshotId}.content.tmp_${System.currentTimeMillis()}")
            tempMeta.writeText("${snapshot.snapshotId}\n${snapshot.filePath}\n${snapshot.timestamp}\n${snapshot.contentHash}\n${snapshot.targetExisted}\n${snapshot.generation}")
            tempContent.writeText(originalText)
            if (!tempMeta.renameTo(metaFile)) {
                tempMeta.copyTo(metaFile, overwrite = true)
                tempMeta.delete()
            }
            if (!tempContent.renameTo(contentFile)) {
                tempContent.copyTo(contentFile, overwrite = true)
                tempContent.delete()
            }
        } catch (_: Exception) {
            // Non-blocking durable journal write
        }

        return snapshot
    }

    /**
     * Reverts a file from an existing rollback snapshot.
     * Recovers from in-memory vault or durable disk journal.
     * Protected targets require the same explicit admin authority boundary as mutation.
     * Snapshot integrity is checked before any filesystem write/delete occurs.
     */
    fun rollback(snapshotId: String, adminAuthToken: String? = null): Boolean {
        var snapshot = rollbackVault[snapshotId]
        if (snapshot == null) {
            // Attempt recovery from durable disk journal
            try {
                val jDir = getJournalDir()
                val metaFile = File(jDir, "$snapshotId.meta")
                val contentFile = File(jDir, "$snapshotId.content")
                if (metaFile.exists() && contentFile.exists()) {
                    val lines = metaFile.readLines()
                    if (lines.size >= 5) {
                        val content = contentFile.readText()
                        snapshot = RollbackSnapshot(
                            snapshotId = lines[0],
                            filePath = lines[1],
                            timestamp = lines[2].toLongOrNull() ?: System.currentTimeMillis(),
                            contentHash = lines[3],
                            targetExisted = lines[4].toBoolean(),
                            originalContent = content,
                            generation = lines.getOrNull(5)?.toIntOrNull() ?: 1
                        )
                        rollbackVault[snapshotId] = snapshot
                    }
                }
            } catch (_: Exception) {
                // Ignore recovery failure
            }
        }

        if (snapshot == null) return false

        if (isProtectedPath(snapshot.filePath) && !isValidAdminToken(adminAuthToken)) {
            Log.w(TAG, "Rollback of protected path '${snapshot.filePath}' BLOCKED without admin authorization token")
            return false
        }

        if (computeHash(snapshot.originalContent) != snapshot.contentHash) {
            Log.e(TAG, "Rollback snapshot integrity check failed for $snapshotId")
            return false
        }

        return try {
            val file = File(snapshot.filePath)
            if (snapshot.targetExisted) {
                file.parentFile?.mkdirs()
                val tempFile = File(file.parentFile, "${file.name}.tmp_rb_${System.currentTimeMillis()}")
                tempFile.writeText(snapshot.originalContent)
                if (!tempFile.renameTo(file)) {
                    tempFile.copyTo(file, overwrite = true)
                    tempFile.delete()
                }
            } else if (file.exists()) {
                if (!file.delete()) {
                    Log.e(TAG, "Failed deleting newly-created file during rollback: ${snapshot.filePath}")
                    return false
                }
            }
            Log.i(TAG, "Successfully rolled back '${snapshot.filePath}' to snapshot $snapshotId (generation ${snapshot.generation})")

            recordAudit(
                proposalId = snapshot.snapshotId,
                filePath = snapshot.filePath,
                action = ProposalAuditAction.ROLLED_BACK,
                reason = "Rolled back to snapshot $snapshotId (generation ${snapshot.generation})",
                contentHash = snapshot.contentHash,
                authorizingEntity = if (adminAuthToken != null) "ADMIN_TOKEN_HOLDER" else "HUMAN_OPERATOR",
                details = "Restored previous state successfully"
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed rolling back '${snapshot.filePath}' to snapshot $snapshotId", e)
            false
        }
    }

    /**
     * Rolls back a target file to a specific generation checkpoint (1, 2, or 3).
     * Generation 1 is the immediate prior state, 2 is the state before that, and 3 is the earliest state.
     * Safeguards: validates generation bounds, protected paths, and snapshot content integrity.
     */
    fun rollbackToGeneration(filePath: String, generation: Int = 1, adminAuthToken: String? = null): Boolean {
        require(generation in 1..3) { "Generation must be between 1 and 3 (requested: $generation)" }
        val list = multiGenerationVault[filePath] ?: return false
        val targetSnapshot = synchronized(list) {
            list.find { it.generation == generation }
        } ?: return false

        return rollback(targetSnapshot.snapshotId, adminAuthToken)
    }

    /**
     * Executes a staged self-modification with policy check, snapshotting, and automatic rollback on verification failure.
     */
    fun executeModificationWithStagedRollback(
        targetFile: File,
        newContent: String,
        isAutonomous: Boolean = true,
        adminAuthToken: String? = null,
        stagedValidator: ((File) -> Boolean)? = null
    ): ModificationOutcome {
        val decision = evaluateModification(
            filePath = targetFile.absolutePath,
            newContent = newContent,
            isAutonomous = isAutonomous,
            adminAuthToken = adminAuthToken
        )

        if (decision != ModificationDecision.ALLOWED) {
            return ModificationOutcome(
                status = ModificationOutcomeStatus.BLOCKED_POLICY,
                filePath = targetFile.absolutePath,
                decision = decision,
                errorDetails = "Modification blocked by policy: $decision"
            )
        }

        // 1. Create rollback snapshot
        val snapshot = createRollbackSnapshot(targetFile)

        // 2. Stage write using atomic temporary file
        try {
            targetFile.parentFile?.mkdirs()
            val tempFile = File(targetFile.parentFile, "${targetFile.name}.tmp_stage_${System.currentTimeMillis()}")
            tempFile.writeText(newContent)
            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }
        } catch (e: Exception) {
            return ModificationOutcome(
                status = ModificationOutcomeStatus.FAILED_IO,
                filePath = targetFile.absolutePath,
                decision = decision,
                errorDetails = "Failed writing file: ${e.message}",
                snapshotId = snapshot.snapshotId
            )
        }

        // 3. Staged Verification
        var validatorVerified = false
        if (stagedValidator != null) {
            var isValid = false
            try {
                isValid = stagedValidator(targetFile)
            } catch (e: Exception) {
                Log.w(TAG, "Staged validation threw exception for ${targetFile.name}: ${e.message}")
                isValid = false
            }

            if (!isValid) {
                // Validation failed -> execute immediate automatic rollback with the same authority context
                val rolledBack = rollback(snapshot.snapshotId, adminAuthToken)
                try {
                    ExecutionProvenanceLedger.recordExecution(
                        taskId = "task_selfmod_${snapshot.snapshotId.take(8)}",
                        actionId = "self_modify_${targetFile.name}",
                        capabilityId = "SELF_MODIFICATION_SAFETY",
                        providerId = "SelfModificationSafetyEngine",
                        inputContent = "modify:${targetFile.absolutePath}",
                        outputContent = "status:ROLLED_BACK:snapshot:${snapshot.snapshotId}",
                        evidence = VerifiedExecutionEvidence(
                            subject = "SelfModificationSafetyEngine",
                            verifiedState = "ROLLED_BACK",
                            confidence = 0.90,
                            evidenceSource = EvidenceSource.PROCESS_TELEMETRY
                        ),
                        executionEnvironment = "local_android_runtime",
                        executor = "SelfModificationSafetyEngine",
                        verifier = "stagedValidator",
                        verificationMethod = "staged_atomic_validation_and_rollback",
                        stateTransition = "DISPATCHED -> CONTROLLED_PATCH -> VALIDATION_FAILED -> ROLLED_BACK"
                    )
                } catch (_: Exception) {}

                return ModificationOutcome(
                    status = ModificationOutcomeStatus.ROLLED_BACK,
                    filePath = targetFile.absolutePath,
                    decision = decision,
                    rollbackPerformed = rolledBack,
                    errorDetails = if (rolledBack) {
                        "Staged verification failed. Automatically rolled back to pre-mutation snapshot."
                    } else {
                        "Staged verification failed and rollback was blocked or failed. Manual recovery required."
                    },
                    snapshotId = snapshot.snapshotId
                )
            }
            validatorVerified = true
        }

        // 4. Record successful mutation into history for loop detection
        val list = mutationHistory.getOrPut(targetFile.absolutePath) { mutableListOf() }
        synchronized(list) {
            list.add(Pair(System.currentTimeMillis(), computeHash(newContent)))
        }

        val contentIntegrityOk = targetFile.exists() && computeHash(targetFile.readText()) == computeHash(newContent)
        val outcomeStatus = if (validatorVerified && contentIntegrityOk) {
            ModificationOutcomeStatus.APPLIED_VERIFIED
        } else {
            ModificationOutcomeStatus.APPLIED_UNVERIFIED
        }

        try {
            val evidence = if (outcomeStatus == ModificationOutcomeStatus.APPLIED_VERIFIED) {
                VerifiedExecutionEvidence(
                    subject = "SelfModificationSafetyEngine",
                    verifiedState = "APPLIED_VERIFIED",
                    confidence = 0.95,
                    evidenceSource = EvidenceSource.FILESYSTEM_AUDIT
                )
            } else {
                VerifiedExecutionEvidence(
                    subject = "SelfModificationSafetyEngine",
                    verifiedState = "APPLIED_UNVERIFIED",
                    confidence = 0.50,
                    evidenceSource = EvidenceSource.PROCESS_TELEMETRY
                )
            }
            ExecutionProvenanceLedger.recordExecution(
                taskId = "task_selfmod_${snapshot.snapshotId.take(8)}",
                actionId = "self_modify_${targetFile.name}",
                capabilityId = "SELF_MODIFICATION_SAFETY",
                providerId = "SelfModificationSafetyEngine",
                inputContent = "modify:${targetFile.absolutePath}",
                outputContent = "status:${outcomeStatus.name}:snapshot:${snapshot.snapshotId}",
                evidence = evidence,
                executionEnvironment = "local_android_runtime",
                executor = "SelfModificationSafetyEngine",
                verifier = if (validatorVerified) "stagedValidator" else null,
                verificationMethod = "staged_atomic_validation_and_rollback",
                stateTransition = "DISPATCHED -> CONTROLLED_PATCH -> ${if (validatorVerified) "VERIFIED" else "UNVERIFIED"}"
            )
        } catch (_: Exception) {}

        return ModificationOutcome(
            status = outcomeStatus,
            filePath = targetFile.absolutePath,
            decision = decision,
            rollbackPerformed = false,
            snapshotId = snapshot.snapshotId
        )
    }

    /**
     * Clears history and snapshots for clean testing isolation.
     */
    fun resetForTesting() {
        mutationHistory.clear()
        rollbackVault.clear()
        multiGenerationVault.clear()
        _pendingProposals.value = emptyList()
        _proposalAuditLog.value = emptyList()
        activeAdminTokens.clear()
        try {
            val jDir = getJournalDir()
            jDir.listFiles()?.forEach { it.delete() }
        } catch (_: Exception) {}
    }
}
