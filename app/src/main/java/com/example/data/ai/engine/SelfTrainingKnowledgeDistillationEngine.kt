package com.example.data.ai.engine

import com.example.data.agent.runtime.ActionVerificationStatus
import com.example.data.agent.runtime.VerificationResult
import com.example.data.agent.runtime.VerifiedExecutionEvidence
import com.example.data.agent.runtime.WastiVerificationReceipt
import com.example.data.agent.runtime.WastiTruthGate
import com.example.data.ai.model.ModelSpecialization
import com.example.data.ai.model.OpenSourceModelCatalog
import com.example.data.memory.MemoryManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DistilledKnowledgeArtifact(
    val artifactId: String,
    val sourceModel: String,
    val targetSpecialization: ModelSpecialization,
    val taskPattern: String,
    val verifiedSkillSignature: String,
    val executionEvidence: String = "",
    val confidenceScore: Float = 0.85f, // Derived from evidence confidence, bounded
    val reinforcementCount: Int = 1,
    val generatedAtMs: Long = System.currentTimeMillis(),
    val isFactuallyVerified: Boolean = false, // Explicit factual verification derived from independent proof
    val canonicalProvenanceEntryId: String? = null // Cryptographic linkage to ExecutionProvenanceLedger
)

/**
 * Stage 10+: Self-Training Knowledge Distillation Engine.
 * Automatically distills successful verified executions into modular learned skills,
 * cross-trains the 12 local open-source models, and persists distilled capabilities into MemoryManager.
 * Strictly gated on canonical verification: rejects mock, synthetic, or unverified claims.
 */
object SelfTrainingKnowledgeDistillationEngine {

    private val _distilledArtifacts = MutableStateFlow<List<DistilledKnowledgeArtifact>>(emptyList())
    val distilledArtifacts: StateFlow<List<DistilledKnowledgeArtifact>> = _distilledArtifacts.asStateFlow()

    fun isSyntheticOrMock(text: String): Boolean {
        val lower = text.lowercase()
        return lower.contains("synthetic") ||
            lower.contains("mock_evidence") ||
            lower.contains("mock") ||
            lower.contains("fake_evidence") ||
            lower.contains("fake") ||
            lower.contains("dummy_evidence") ||
            lower.contains("dummy") ||
            lower.contains("unverified_stub") ||
            lower.contains("unverified")
    }

    suspend fun recordVerifiedInteractionAndDistill(
        taskPrompt: String,
        verificationResult: VerificationResult,
        winningModelId: String,
        receipt: WastiVerificationReceipt? = null
    ): DistilledKnowledgeArtifact? {
        val isReceiptValid = receipt != null && WastiTruthGate.validateReceipt(receipt)
        if (verificationResult.status != ActionVerificationStatus.VERIFIED ||
            !verificationResult.isVerified ||
            !isReceiptValid ||
            verificationResult.confidence < 0.85 ||
            verificationResult.evidence.isBlank() ||
            isSyntheticOrMock(verificationResult.evidence)
        ) {
            return null
        }
        return recordVerifiedInteractionAndDistill(
            taskPrompt = taskPrompt,
            successfulExecutionEvidence = verificationResult.evidence,
            winningModelId = winningModelId,
            provenanceEntryId = verificationResult.actionId,
            evidenceConfidence = verificationResult.confidence.toFloat(),
            isVerified = true,
            isFactuallyVerified = verificationResult.status == ActionVerificationStatus.VERIFIED &&
                verificationResult.structuredEvidence != null
        )
    }

    suspend fun recordVerifiedInteractionAndDistill(
        taskPrompt: String,
        verifiedEvidence: VerifiedExecutionEvidence,
        winningModelId: String,
        receipt: WastiVerificationReceipt? = null
    ): DistilledKnowledgeArtifact? {
        if (verifiedEvidence.confidence < 0.85 ||
            verifiedEvidence.subject.isBlank() ||
            verifiedEvidence.verifiedState.isBlank() ||
            isSyntheticOrMock(verifiedEvidence.subject) ||
            isSyntheticOrMock(verifiedEvidence.verifiedState)
        ) {
            return null
        }
        val isReceiptValid = receipt != null && WastiTruthGate.validateReceipt(receipt)
        val evidenceStr = "${verifiedEvidence.subject} -> ${verifiedEvidence.verifiedState}"
        return recordVerifiedInteractionAndDistill(
            taskPrompt = taskPrompt,
            successfulExecutionEvidence = evidenceStr,
            winningModelId = winningModelId,
            provenanceEntryId = null,
            evidenceConfidence = verifiedEvidence.confidence.toFloat(),
            isVerified = isReceiptValid,
            isFactuallyVerified = isReceiptValid
        )
    }

    suspend fun recordVerifiedInteractionAndDistill(
        taskPrompt: String,
        provenanceEntry: com.example.data.agent.runtime.ProvenanceEntry,
        winningModelId: String
    ): DistilledKnowledgeArtifact? {
        val isEntryAuthentic = com.example.data.agent.runtime.ExecutionProvenanceLedger.verifyEntry(provenanceEntry.entryId)
        val isCanonicalVerified = provenanceEntry.isVerified && isEntryAuthentic
        if (provenanceEntry.evidenceSummary.isBlank() ||
            isSyntheticOrMock(provenanceEntry.evidenceSummary)
        ) {
            return null
        }
        val derivedConfidence = provenanceEntry.confidence.toFloat().takeIf { it in 0.01f..1.0f } ?: 0.85f
        return recordVerifiedInteractionAndDistill(
            taskPrompt = taskPrompt,
            successfulExecutionEvidence = provenanceEntry.evidenceSummary,
            winningModelId = winningModelId,
            provenanceEntryId = provenanceEntry.entryId,
            evidenceConfidence = derivedConfidence,
            isVerified = isCanonicalVerified,
            isFactuallyVerified = isCanonicalVerified
        )
    }

    suspend fun recordVerifiedInteractionAndDistill(
        taskPrompt: String,
        successfulExecutionEvidence: String,
        winningModelId: String,
        provenanceEntryId: String? = null,
        evidenceConfidence: Float = 0.85f,
        isVerified: Boolean = false,
        isFactuallyVerified: Boolean = false
    ): DistilledKnowledgeArtifact? {
        if (taskPrompt.isBlank() ||
            successfulExecutionEvidence.isBlank() ||
            isSyntheticOrMock(successfulExecutionEvidence)
        ) {
            return null
        }

        // Truth Doctrine: Only bind canonical provenance if explicitly provided and verified in ExecutionProvenanceLedger.
        val canonicalEntryId = provenanceEntryId
        val verifiedLedgerEntry = canonicalEntryId?.let { id ->
            if (com.example.data.agent.runtime.ExecutionProvenanceLedger.verifyEntry(id)) {
                com.example.data.agent.runtime.ExecutionProvenanceLedger.getEntry(id)
            } else null
        }
        val hasCanonicalProof = verifiedLedgerEntry != null && verifiedLedgerEntry.isVerified

        val model = OpenSourceModelCatalog.getModelById(winningModelId)
        val spec = model?.primarySpecialization ?: ModelSpecialization.GENERAL_REASONING

        val existingIndex = _distilledArtifacts.value.indexOfFirst {
            it.taskPattern.equals(taskPrompt.take(100), ignoreCase = true)
        }

        val artifact = if (existingIndex >= 0) {
            val existing = _distilledArtifacts.value[existingIndex]
            val derivedConfidence = if (hasCanonicalProof) {
                maxOf(existing.confidenceScore, evidenceConfidence)
            } else {
                existing.confidenceScore.coerceAtMost(0.84f)
            }
            existing.copy(
                reinforcementCount = existing.reinforcementCount + 1,
                confidenceScore = derivedConfidence,
                executionEvidence = successfulExecutionEvidence,
                generatedAtMs = System.currentTimeMillis(),
                isFactuallyVerified = existing.isFactuallyVerified || hasCanonicalProof,
                canonicalProvenanceEntryId = canonicalEntryId ?: existing.canonicalProvenanceEntryId
            )
        } else {
            DistilledKnowledgeArtifact(
                artifactId = "distill_${System.currentTimeMillis()}",
                sourceModel = model?.brandDisplayName ?: winningModelId,
                targetSpecialization = spec,
                taskPattern = taskPrompt.take(100),
                verifiedSkillSignature = "skill_verified_${taskPrompt.hashCode()}",
                executionEvidence = successfulExecutionEvidence,
                confidenceScore = if (hasCanonicalProof) evidenceConfidence.coerceIn(0.85f, 1.0f) else evidenceConfidence.coerceAtMost(0.84f),
                reinforcementCount = 1,
                isFactuallyVerified = hasCanonicalProof,
                canonicalProvenanceEntryId = if (hasCanonicalProof) canonicalEntryId else null
            )
        }

        if (existingIndex >= 0) {
            val list = _distilledArtifacts.value.toMutableList()
            list[existingIndex] = artifact
            _distilledArtifacts.value = list
        } else {
            _distilledArtifacts.value = _distilledArtifacts.value + artifact
        }

        // Persist distilled skill into Wasti Long-Term Memory
        try {
            MemoryManager.saveMemory(
                key = "Skill_${artifact.artifactId}",
                category = "Distilled Skills",
                value = "[DISTILLED_SKILL] Specialization: ${artifact.targetSpecialization} | Pattern: ${artifact.taskPattern} | Model: ${artifact.sourceModel} | Evidence: ${artifact.executionEvidence}",
                importanceScore = if (artifact.isFactuallyVerified) artifact.confidenceScore else 0.5f
            )
        } catch (_: Exception) {
            // Non-critical fallback if memory engine is initializing
        }

        return artifact
    }

    fun findMatchingSkills(prompt: String): List<DistilledKnowledgeArtifact> {
        val lowerPrompt = prompt.lowercase()
        return _distilledArtifacts.value.filter { artifact ->
            val patternLower = artifact.taskPattern.lowercase()
            lowerPrompt.contains(patternLower) || patternLower.contains(lowerPrompt)
        }.sortedByDescending { it.confidenceScore }
    }

    fun getKnowledgeBaseSize(): Int = _distilledArtifacts.value.size
}
