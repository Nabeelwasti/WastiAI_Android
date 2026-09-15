package com.example.data.agent.runtime

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class GeneratedToolArtifact(
    val toolId: String,
    val toolName: String,
    val targetCapabilityId: String,
    val generatedCode: String,
    val testFixtureCode: String,
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

        // Epistemic Truth Invariant: Generated tools start strictly UNVERIFIED.
        // Self-assigned verification upon synthesis is strictly prohibited.
        val artifact = GeneratedToolArtifact(
            toolId = "tool_$sanitizedId",
            toolName = toolName,
            targetCapabilityId = missingCapabilityId,
            generatedCode = code,
            testFixtureCode = testFixture,
            isVerified = false,
            verificationEvidence = null,
            verificationConfidence = 0.0
        )

        _generatedTools.value = _generatedTools.value + artifact
        return artifact
    }

    /**
     * Gated verification transition: Promotes a generated tool to VERIFIED ONLY when backed
     * by real execution, observation, evidence collection, security analysis, and
     * independent canonical verification.
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
        current[index] = existing.copy(
            isVerified = true,
            verificationEvidence = verificationResult.evidence,
            verificationConfidence = verificationResult.confidence,
            verifiedAtMs = System.currentTimeMillis()
        )
        _generatedTools.value = current
        return true
    }
}
