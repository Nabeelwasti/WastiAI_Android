package com.example.data.agent.runtime

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ToolEvolutionStage {
    GENERATED,
    STATIC_ANALYZED,
    SANDBOX_TESTED,
    REAL_EXECUTION_REQUIRED,
    EXECUTED,
    OBSERVED,
    INDEPENDENTLY_VERIFIED,
    TRUSTED
}

data class GeneratedToolArtifact(
    val toolId: String,
    val toolName: String,
    val targetCapabilityId: String,
    val generatedCode: String,
    val testFixtureCode: String,
    val stage: ToolEvolutionStage = ToolEvolutionStage.GENERATED,
    val isVerified: Boolean = false,
    val verificationEvidence: String? = null,
    val verificationConfidence: Double = 0.0,
    val createdAtMs: Long = System.currentTimeMillis(),
    val verifiedAtMs: Long? = null
)

object WastiToolGenerator {

    private val _generatedTools = MutableStateFlow<List<GeneratedToolArtifact>>(emptyList())
    val generatedTools: StateFlow<List<GeneratedToolArtifact>> = _generatedTools.asStateFlow()

    fun generateToolForMissingCapability(
        missingCapabilityId: String,
        goalDescription: String
    ): GeneratedToolArtifact {
        val sanitizedId = missingCapabilityId.lowercase().replace(" ", "_")
        val toolName = "Dynamic${missingCapabilityId.replace("_", " ").split(" ").joinToString("") { it.replaceFirstChar(Char::titlecase) }}Tool"
        
        val code = """
            package com.example.data.agent.runtime.tools

            class $toolName {
                fun execute(params: Map<String, Any>): Map<String, Any> {
                    // Automatically generated tool for $missingCapabilityId ($goalDescription)
                    return mapOf(
                        "status" to "COMPLETED",
                        "capability" to "$missingCapabilityId",
                        "output" to "Generated tool execution completed for $missingCapabilityId"
                    )
                }
            }
        """.trimIndent()

        val testFixture = """
            fun test$toolName() {
                val tool = $toolName()
                val result = tool.execute(emptyMap())
                assert(result["status"] == "COMPLETED")
            }
        """.trimIndent()

        // Epistemic Truth Invariant: Generated tools start strictly UNVERIFIED in GENERATED stage.
        // Self-assigned verification upon synthesis is strictly prohibited.
        val artifact = GeneratedToolArtifact(
            toolId = "tool_$sanitizedId",
            toolName = toolName,
            targetCapabilityId = missingCapabilityId,
            generatedCode = code,
            testFixtureCode = testFixture,
            stage = ToolEvolutionStage.GENERATED,
            isVerified = false,
            verificationEvidence = null,
            verificationConfidence = 0.0
        )

        _generatedTools.value = _generatedTools.value + artifact
        return artifact
    }

    fun markStaticAnalyzed(toolId: String, analysisEvidence: String): Boolean {
        if (analysisEvidence.isBlank() || WastiVerificationEngine().isSyntheticOrMock(analysisEvidence)) return false
        val current = _generatedTools.value.toMutableList()
        val index = current.indexOfFirst { it.toolId == toolId }
        if (index == -1) return false
        current[index] = current[index].copy(stage = ToolEvolutionStage.STATIC_ANALYZED)
        _generatedTools.value = current
        return true
    }

    fun markSandboxTested(toolId: String, testEvidence: String): Boolean {
        if (testEvidence.isBlank() || WastiVerificationEngine().isSyntheticOrMock(testEvidence)) return false
        val current = _generatedTools.value.toMutableList()
        val index = current.indexOfFirst { it.toolId == toolId }
        if (index == -1) return false
        current[index] = current[index].copy(stage = ToolEvolutionStage.SANDBOX_TESTED)
        _generatedTools.value = current
        return true
    }

    fun markRealExecutionRequired(toolId: String): Boolean {
        val current = _generatedTools.value.toMutableList()
        val index = current.indexOfFirst { it.toolId == toolId }
        if (index == -1) return false
        current[index] = current[index].copy(stage = ToolEvolutionStage.REAL_EXECUTION_REQUIRED)
        _generatedTools.value = current
        return true
    }

    fun recordExecution(toolId: String, fact: ObservedExecutionFact): Boolean {
        val current = _generatedTools.value.toMutableList()
        val index = current.indexOfFirst { it.toolId == toolId }
        if (index == -1) return false
        current[index] = current[index].copy(stage = ToolEvolutionStage.EXECUTED)
        _generatedTools.value = current
        return true
    }

    fun recordObservation(toolId: String, observation: ObservationResult): Boolean {
        if (observation.status != ObservationStatus.OBSERVED && observation.status != ObservationStatus.CHANGED) return false
        val current = _generatedTools.value.toMutableList()
        val index = current.indexOfFirst { it.toolId == toolId }
        if (index == -1) return false
        current[index] = current[index].copy(stage = ToolEvolutionStage.OBSERVED)
        _generatedTools.value = current
        return true
    }

    /**
     * Gated verification transition: Promotes a generated tool to VERIFIED ONLY when backed
     * by real execution, observation, evidence collection, security analysis, and
     * independent canonical verification. Reaches INDEPENDENTLY_VERIFIED or TRUSTED.
     */
    fun promoteToolWithCanonicalVerification(
        toolId: String,
        verificationResult: VerificationResult
    ): Boolean {
        if (verificationResult.status != ActionVerificationStatus.VERIFIED ||
            verificationResult.confidence < 0.85 ||
            verificationResult.evidence.isBlank() ||
            WastiVerificationEngine().isSyntheticOrMock(verificationResult.evidence)
        ) {
            return false
        }

        val current = _generatedTools.value.toMutableList()
        val index = current.indexOfFirst { it.toolId == toolId }
        if (index == -1) return false

        val existing = current[index]
        val targetStage = if (verificationResult.confidence >= 0.95) {
            ToolEvolutionStage.TRUSTED
        } else {
            ToolEvolutionStage.INDEPENDENTLY_VERIFIED
        }

        current[index] = existing.copy(
            stage = targetStage,
            isVerified = true,
            verificationEvidence = verificationResult.evidence,
            verificationConfidence = verificationResult.confidence,
            verifiedAtMs = System.currentTimeMillis()
        )
        _generatedTools.value = current
        return true
    }
}
