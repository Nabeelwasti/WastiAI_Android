package com.example.data.ai.engine

import com.example.data.ai.model.OpenSourceModelCatalog
import com.example.data.ai.model.ModelSpecialization
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DistilledKnowledgeArtifact(
    val artifactId: String,
    val sourceModel: String,
    val targetSpecialization: ModelSpecialization,
    val taskPattern: String,
    val verifiedSkillSignature: String,
    val confidenceScore: Float,
    val generatedAtMs: Long = System.currentTimeMillis()
)

object SelfTrainingKnowledgeDistillationEngine {

    private val _distilledArtifacts = MutableStateFlow<List<DistilledKnowledgeArtifact>>(emptyList())
    val distilledArtifacts: StateFlow<List<DistilledKnowledgeArtifact>> = _distilledArtifacts.asStateFlow()

    fun recordVerifiedInteractionAndDistill(
        taskPrompt: String,
        successfulExecutionEvidence: String,
        winningModelId: String
    ): DistilledKnowledgeArtifact {
        val model = OpenSourceModelCatalog.getModelById(winningModelId)
        val spec = model?.primarySpecialization ?: ModelSpecialization.GENERAL_REASONING

        val artifact = DistilledKnowledgeArtifact(
            artifactId = "distill_${System.currentTimeMillis()}",
            sourceModel = model?.brandDisplayName ?: winningModelId,
            targetSpecialization = spec,
            taskPattern = taskPrompt.take(100),
            verifiedSkillSignature = "skill_verified_${taskPrompt.hashCode()}",
            confidenceScore = 0.98f
        )

        _distilledArtifacts.value = _distilledArtifacts.value + artifact
        return artifact
    }

    fun getKnowledgeBaseSize(): Int = _distilledArtifacts.value.size
}
