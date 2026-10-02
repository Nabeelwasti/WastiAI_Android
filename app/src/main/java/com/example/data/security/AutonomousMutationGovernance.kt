package com.example.data.security

import android.util.Log
import com.example.data.agent.runtime.ExecutionProvenanceLedger
import com.example.data.agent.runtime.ModificationDecision
import com.example.data.agent.runtime.SelfModificationSafetyEngine
import com.example.data.agent.runtime.WastiEmergencyStopController
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * [Audit Governance] Autonomous Mutation Authority & Risk-Tier Governance
 *
 * Enforces production-grade governance over all autonomous code generation,
 * self-modification, configuration changes, credential access, native code,
 * CI/CD workflows, signing, release, and emergency-stop operations.
 *
 * Invariant Rules:
 * 1. LOW_REVERSIBLE: Autonomous changes permitted strictly within workspace boundaries
 *    with automatic 3-generation rollback snapshots.
 * 2. MEDIUM_IMPACT: Bounded application code changes permitted with strict size limits,
 *    sliding-window loop detection, and post-execution verification.
 * 3. HIGH_GOVERNANCE: System permissions, external provider integrations, database wipes,
 *    superuser/root operations, and outreach require explicit Human Owner approval.
 * 4. CRITICAL_SECURITY_IMMUTABLE: Keystores, signing keys, .env secrets, security sentinels,
 *    CI/CD workflows, Android manifests, and native code are strictly forbidden from
 *    autonomous AI modification without cryptographically valid admin authorization.
 * 5. Emergency-Stop Authority: AI cannot reset emergency-stop under any circumstance.
 *    Only verified Human Operators or Admin Token holders can reset the stop latch.
 */

enum class MutationRiskTier {
    LOW_REVERSIBLE,
    MEDIUM_IMPACT,
    HIGH_GOVERNANCE,
    CRITICAL_SECURITY_IMMUTABLE
}

enum class MutationApprovalState {
    PENDING_APPROVAL,
    APPROVED,
    DENIED,
    EXECUTED,
    FAILED,
    ROLLED_BACK,
    VERIFIED
}

data class MutationEvaluation(
    val riskTier: MutationRiskTier,
    val decision: ModificationDecision,
    val requiresHumanApproval: Boolean,
    val reason: String,
    val riskFactors: List<String> = emptyList()
)

data class MutationProvenanceRecord(
    val id: String = UUID.randomUUID().toString(),
    val proposalId: String,
    val requester: String,
    val reason: String,
    val proposedMutationHash: String,
    val affectedFiles: List<String>,
    val riskTier: MutationRiskTier,
    val approvalIdentity: String,
    val approvalState: MutationApprovalState,
    val timestamp: Long = System.currentTimeMillis(),
    val executionResult: String,
    val rollbackSnapshotId: String? = null,
    val rollbackGeneration: Int = 1,
    val rollbackRestorationStatus: String? = null,
    val verificationEvidence: String? = null,
    val previousRecordHash: String = ExecutionProvenanceLedger.GENESIS_HASH,
    val recordHash: String = ""
)

object AutonomousMutationGovernance {

    private const val TAG = "MutationGovernance"

    // Critical security immutable targets - AI CAN NEVER MUTATE AUTONOMOUSLY
    private val CRITICAL_SECURITY_PATTERNS = listOf(
        // Signing & Secrets
        "keystore", ".jks", ".pem", ".key", ".pk8", ".p12",
        ".env", "secrets.", "id_rsa", "id_ed25519",
        // CI/CD & Build Manifests
        ".github/workflows", ".gitlab-ci.yml", ".circleci",
        "androidmanifest.xml", "build.gradle", "settings.gradle", "proguard-rules.pro",
        // Native code & binaries
        ".so", ".cpp", ".c", ".h", ".hpp", "cmakelists.txt", "wasti_ai_native",
        // Security Sentinel Engines
        "ZeroTrustSentinelEngine", "ProductionReadinessGate", "SelfModificationSafetyEngine",
        "WastiEmergencyStopController", "WastiSecurityPolicyEngine", "WastiSecurityManager",
        "AutonomousMutationGovernance", "EvidenceIntegrityValidator", "WastiIdentityManager"
    )

    // High governance targets - REQUIRES EXPLICIT HUMAN OWNER APPROVAL
    private val HIGH_GOVERNANCE_PATTERNS = listOf(
        "permissionmanager", "accessibility_service", "system_settings",
        "wastidatabase", "drop table", "truncate table", "rm -rf /",
        "root_shell", "su ", "sudo ", "setenforce 0",
        "/email/send", "whatsapp_bulk", "outreach_campaign"
    )

    // Low risk reversible directory substrings
    private val LOW_RISK_PATTERNS = listOf(
        "/scratch/", "/temp/", "/test_scratch/", ".tmp", ".md", ".txt", ".log", "/notes/"
    )

    private val provenanceHistory = ConcurrentHashMap<String, MutationProvenanceRecord>()
    private val historyOrder = mutableListOf<String>()

    @Volatile
    private var isGovernanceCompromised = false

    fun isCompromised(): Boolean = isGovernanceCompromised

    /**
     * Resolves canonical filesystem path safely to prevent path-traversal obfuscation attacks.
     * Throws SecurityException on canonicalization failure (Fail-Closed).
     */
    fun canonicalizePath(filePath: String): String {
        return tryCanonicalizePath(filePath)
            ?: throw SecurityException("Path canonicalization failed (Fail-Closed): $filePath")
    }

    /**
     * Attempts to resolve canonical path safely; returns null on failure without falling back to raw path.
     */
    fun tryCanonicalizePath(filePath: String): String? {
        return try {
            val file = File(filePath)
            file.canonicalPath.replace("\\", "/")
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Classifies a target File into an explicit MutationRiskTier using canonical path resolution.
     */
    fun classifyRiskTier(targetFile: File, content: String? = null, action: String? = null): MutationRiskTier {
        val canonical = tryCanonicalizePath(targetFile.path) ?: return MutationRiskTier.CRITICAL_SECURITY_IMMUTABLE
        return classifyRiskTier(canonical, content, action)
    }

    /**
     * Classifies a target file or operation into an explicit MutationRiskTier.
     */
    fun classifyRiskTier(filePath: String, content: String? = null, action: String? = null): MutationRiskTier {
        val canonicalPath = tryCanonicalizePath(filePath) ?: return MutationRiskTier.CRITICAL_SECURITY_IMMUTABLE
        val normalizedPath = filePath.replace("\\", "/").lowercase()
        val canonicalLower = canonicalPath.lowercase()
        val normalizedAction = (action ?: "").lowercase()
        val combined = "$normalizedPath $canonicalLower $normalizedAction ${content?.take(500)?.lowercase() ?: ""}"

        // 1. Critical Security Check
        for (pattern in CRITICAL_SECURITY_PATTERNS) {
            if (combined.contains(pattern.lowercase())) {
                return MutationRiskTier.CRITICAL_SECURITY_IMMUTABLE
            }
        }

        // 2. High Governance Check
        for (pattern in HIGH_GOVERNANCE_PATTERNS) {
            if (combined.contains(pattern.lowercase())) {
                return MutationRiskTier.HIGH_GOVERNANCE
            }
        }

        // 3. Low Reversible Check
        for (pattern in LOW_RISK_PATTERNS) {
            if (normalizedPath.contains(pattern.lowercase()) || canonicalLower.contains(pattern.lowercase())) {
                return MutationRiskTier.LOW_REVERSIBLE
            }
        }

        // 4. Default for workspace source code modification
        return MutationRiskTier.MEDIUM_IMPACT
    }

    /**
     * Evaluates autonomous mutation authority for a target File object.
     */
    fun evaluateMutationAuthority(
        targetFile: File,
        newContent: String,
        isAutonomous: Boolean = true,
        adminAuthToken: String? = null,
        requester: String = if (isAutonomous) "AUTONOMOUS_AI" else "HUMAN_OPERATOR",
        workspaceRoot: File? = null
    ): MutationEvaluation {
        val canonical = tryCanonicalizePath(targetFile.path)
        if (canonical == null) {
            return MutationEvaluation(
                riskTier = MutationRiskTier.CRITICAL_SECURITY_IMMUTABLE,
                decision = ModificationDecision.BLOCKED_PROTECTED_PATH,
                requiresHumanApproval = true,
                reason = "Path canonicalization failed (Fail-Closed): ${targetFile.path}",
                riskFactors = listOf("CANONICALIZATION_FAILURE")
            )
        }
        return evaluateMutationAuthority(
            filePath = canonical,
            newContent = newContent,
            isAutonomous = isAutonomous,
            adminAuthToken = adminAuthToken,
            requester = requester,
            workspaceRoot = workspaceRoot
        )
    }

    /**
     * Evaluates autonomous mutation authority against risk tier, caller identity, workspace containment, and system state.
     */
    fun evaluateMutationAuthority(
        filePath: String,
        newContent: String,
        isAutonomous: Boolean = true,
        adminAuthToken: String? = null,
        requester: String = if (isAutonomous) "AUTONOMOUS_AI" else "HUMAN_OPERATOR",
        workspaceRoot: File? = null
    ): MutationEvaluation {
        // 1. Emergency Stop Check
        if (WastiEmergencyStopController.isEmergencyStopped) {
            return MutationEvaluation(
                riskTier = classifyRiskTier(filePath, newContent),
                decision = ModificationDecision.BLOCKED_EMERGENCY_STOP,
                requiresHumanApproval = true,
                reason = "Emergency stop latch is currently ACTIVE. All mutations blocked.",
                riskFactors = listOf("EMERGENCY_STOP_ENGAGED")
            )
        }

        // 2. Canonical Path Resolution (Fail-Closed on Traversal / Resolution Failure)
        val effectivePath = if (workspaceRoot != null && !File(filePath).isAbsolute) {
            File(workspaceRoot, filePath).path
        } else {
            filePath
        }
        val canonicalTarget = tryCanonicalizePath(effectivePath)
        if (canonicalTarget == null) {
            return MutationEvaluation(
                riskTier = MutationRiskTier.CRITICAL_SECURITY_IMMUTABLE,
                decision = ModificationDecision.BLOCKED_PROTECTED_PATH,
                requiresHumanApproval = true,
                reason = "Target path canonicalization failed (Fail-Closed): $filePath",
                riskFactors = listOf("CANONICALIZATION_FAILURE")
            )
        }

        // 3. Workspace-Root Containment Verification
        if (workspaceRoot != null) {
            val canonicalRoot = tryCanonicalizePath(workspaceRoot.path)
            if (canonicalRoot == null || (!canonicalTarget.startsWith("$canonicalRoot/") && canonicalTarget != canonicalRoot)) {
                return MutationEvaluation(
                    riskTier = MutationRiskTier.CRITICAL_SECURITY_IMMUTABLE,
                    decision = ModificationDecision.BLOCKED_PROTECTED_PATH,
                    requiresHumanApproval = true,
                    reason = "Workspace boundary violation: target '$canonicalTarget' escapes workspace root '${canonicalRoot ?: workspaceRoot.path}'",
                    riskFactors = listOf("WORKSPACE_ESCAPE_DETECTED")
                )
            }
        }

        // 4. Classify Risk Tier using Canonical Path
        val tier = classifyRiskTier(canonicalTarget, newContent)
        val riskFactors = mutableListOf<String>()

        // 5. Critical Security Boundary
        if (tier == MutationRiskTier.CRITICAL_SECURITY_IMMUTABLE) {
            riskFactors.add("CRITICAL_SECURITY_IMMUTABLE: Target is in protected security/signing/CI boundary")
            if (isAutonomous && !SelfModificationSafetyEngine.isValidAdminToken(adminAuthToken)) {
                return MutationEvaluation(
                    riskTier = tier,
                    decision = ModificationDecision.BLOCKED_PROTECTED_PATH,
                    requiresHumanApproval = true,
                    reason = "Autonomous modification of critical security infrastructure is forbidden without verified admin authorization.",
                    riskFactors = riskFactors
                )
            }
        }

        // 6. High Governance Boundary
        if (tier == MutationRiskTier.HIGH_GOVERNANCE) {
            riskFactors.add("HIGH_GOVERNANCE: Operation affects privileged system controls or root operations")
            if (isAutonomous && !SelfModificationSafetyEngine.isValidAdminToken(adminAuthToken)) {
                return MutationEvaluation(
                    riskTier = tier,
                    decision = ModificationDecision.REQUIRES_ADMIN_AUTHORIZATION,
                    requiresHumanApproval = true,
                    reason = "High-governance mutation requires explicit Human Owner approval.",
                    riskFactors = riskFactors
                )
            }
        }

        // 7. Size, Path, and Loop Checks via SelfModificationSafetyEngine
        val engineDecision = SelfModificationSafetyEngine.evaluateModification(
            filePath = canonicalTarget,
            newContent = newContent,
            isAutonomous = isAutonomous,
            adminAuthToken = adminAuthToken
        )

        val requiresApproval = when (engineDecision) {
            ModificationDecision.ALLOWED -> false
            ModificationDecision.REQUIRES_ADMIN_AUTHORIZATION -> true
            else -> false
        }

        return MutationEvaluation(
            riskTier = tier,
            decision = engineDecision,
            requiresHumanApproval = requiresApproval,
            reason = "Evaluation completed: $engineDecision under risk tier $tier",
            riskFactors = riskFactors
        )
    }

    /**
     * Determines whether emergency stop latch reset is authorized.
     * Invariant: SYSTEM, SYSTEM_*, AI, agent, subagent, autonomous, bot, and UNKNOWN identities
     * can NEVER reset emergency stop (fail-closed).
     * A requester string alone must never authorize reset; a cryptographically valid admin token
     * is strictly required.
     */
    fun isEmergencyStopResetPermitted(requester: String?, adminToken: String? = null): Boolean {
        if (requester.isNullOrBlank()) {
            Log.w(TAG, "Emergency Stop reset rejected: Requester identity is blank or null (Fail-Closed).")
            return false
        }

        val normalized = requester.trim().uppercase()

        // 1. Strict fail-closed rejection of SYSTEM, SYSTEM_*, AI, agent, subagent, autonomous, bot, and UNKNOWN identities
        if (normalized == "AUTONOMOUS_AI" ||
            normalized == "AI" ||
            normalized.startsWith("AI_") ||
            normalized.endsWith("_AI") ||
            normalized.contains("AGENT") ||
            normalized.contains("SUBAGENT") ||
            normalized.contains("AUTONOMOUS") ||
            normalized.contains("BOT") ||
            normalized == "UNKNOWN" ||
            normalized == "SYSTEM" ||
            normalized.startsWith("SYSTEM_")
        ) {
            Log.e(TAG, "Security Alert: Autonomous/System/Agent/Bot/Unknown caller '$requester' attempted to reset Emergency Stop latch. REJECTED (Fail-Closed).")
            return false
        }

        // 2. Requester string alone must never authorize reset: strictly requires a non-forgeable verified admin token
        if (SelfModificationSafetyEngine.isValidAdminToken(adminToken)) {
            return true
        }

        // 3. Fail-Closed for all unauthenticated callers (e.g. HUMAN_OPERATOR without valid admin token)
        Log.w(TAG, "Emergency Stop reset rejected for unauthenticated caller: $requester (valid admin token required)")
        return false
    }

    /**
     * Records a cryptographically chained provenance record for a mutation event.
     */
    fun recordProvenance(
        proposalId: String,
        requester: String,
        reason: String,
        proposedMutationHash: String,
        affectedFiles: List<String>,
        riskTier: MutationRiskTier,
        approvalIdentity: String,
        approvalState: MutationApprovalState,
        executionResult: String,
        rollbackSnapshotId: String? = null,
        rollbackGeneration: Int = 1,
        rollbackRestorationStatus: String? = null,
        verificationEvidence: String? = null
    ): MutationProvenanceRecord {
        synchronized(this) {
            val lastId = historyOrder.lastOrNull()
            val prevRecord = if (lastId != null) provenanceHistory[lastId] else null
            val prevHash = prevRecord?.recordHash ?: ExecutionProvenanceLedger.GENESIS_HASH
            val id = UUID.randomUUID().toString()
            val timestamp = System.currentTimeMillis()

            val recordHash = computeProvenanceHash(
                prevHash = prevHash,
                recordId = id,
                proposalId = proposalId,
                requester = requester,
                riskTier = riskTier,
                approvalState = approvalState,
                contentHash = proposedMutationHash,
                timestamp = timestamp
            )

            val record = MutationProvenanceRecord(
                id = id,
                proposalId = proposalId,
                requester = requester,
                reason = reason,
                proposedMutationHash = proposedMutationHash,
                affectedFiles = affectedFiles,
                riskTier = riskTier,
                approvalIdentity = approvalIdentity,
                approvalState = approvalState,
                timestamp = timestamp,
                executionResult = executionResult,
                rollbackSnapshotId = rollbackSnapshotId,
                rollbackGeneration = rollbackGeneration,
                rollbackRestorationStatus = rollbackRestorationStatus,
                verificationEvidence = verificationEvidence,
                previousRecordHash = prevHash,
                recordHash = recordHash
            )

            provenanceHistory[id] = record
            historyOrder.add(id)
            return record
        }
    }

    fun computeProvenanceHash(
        prevHash: String,
        recordId: String,
        proposalId: String,
        requester: String,
        riskTier: MutationRiskTier,
        approvalState: MutationApprovalState,
        contentHash: String,
        timestamp: Long
    ): String {
        val raw = "$prevHash|$recordId|$proposalId|$requester|${riskTier.name}|${approvalState.name}|$contentHash|$timestamp"
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(raw.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    fun getProvenanceHistory(): List<MutationProvenanceRecord> {
        synchronized(this) {
            return historyOrder.mapNotNull { provenanceHistory[it] }
        }
    }

    fun resetForTesting() {
        synchronized(this) {
            provenanceHistory.clear()
            historyOrder.clear()
            isGovernanceCompromised = false
        }
    }
}
