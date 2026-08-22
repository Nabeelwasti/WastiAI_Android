package com.example.data.agent.runtime

enum class CapabilityLifecycleStage {
    DISCOVERED,
    DESIGNED,
    IMPLEMENTED,
    TESTED,
    VALIDATED,
    REGISTERED,
    EVALUATED,
    PROMOTED
}

data class CapabilityProposal(
    val capabilityId: String,
    val capabilityName: String,
    val description: String,
    val targetLanguage: String,
    val sourceFiles: Map<String, String>,
    val testFiles: Map<String, String>,
    val stage: CapabilityLifecycleStage = CapabilityLifecycleStage.DISCOVERED,
    val isExperimental: Boolean = true,
    val createdAtMs: Long = System.currentTimeMillis()
)

data class CapabilityValidationResult(
    val isValid: Boolean,
    val testResultsSummary: String,
    val errors: List<String> = emptyList()
)

/**
 * Stage 3 Task 6: Capability Development Contract.
 * Architectural contract establishing Wasti's self-development lifecycle:
 * Create → Test → Validate → Register → Evaluate → Promote.
 * Safe sandbox progression without modifying the protected core agent runtime.
 */
interface CapabilityDevelopmentContract {
    suspend fun proposeCapability(proposal: CapabilityProposal): CapabilityProposal
    suspend fun implementCapability(capabilityId: String): CapabilityProposal
    suspend fun testCapability(capabilityId: String): CapabilityValidationResult
    suspend fun validateCapability(capabilityId: String): CapabilityValidationResult
    suspend fun registerExperimentalCapability(capabilityId: String): Boolean
    suspend fun evaluateCapability(capabilityId: String): CapabilityLifecycleStage
    suspend fun promoteCapability(capabilityId: String): Boolean
}

// TODO: unused — evaluate for removal or wiring in
class WastiCapabilityDevelopmentEngine(
    private val workspaceManager: WorkspaceManager,
    private val executionRouter: ExecutionProviderRouter
) : CapabilityDevelopmentContract {

    private val proposals = mutableMapOf<String, CapabilityProposal>()

    override suspend fun proposeCapability(proposal: CapabilityProposal): CapabilityProposal {
        val updated = proposal.copy(stage = CapabilityLifecycleStage.DESIGNED)
        proposals[proposal.capabilityId] = updated
        return updated
    }

    override suspend fun implementCapability(capabilityId: String): CapabilityProposal {
        val proposal = proposals[capabilityId] ?: throw IllegalArgumentException("Capability $capabilityId not found")
        // Write implementation source files into workspace experimental directory
        proposal.sourceFiles.forEach { (path, content) ->
            workspaceManager.writeFile("experimental/capabilities/$capabilityId/$path", content)
        }
        proposal.testFiles.forEach { (path, content) ->
            workspaceManager.writeFile("experimental/capabilities/$capabilityId/tests/$path", content)
        }
        val updated = proposal.copy(stage = CapabilityLifecycleStage.IMPLEMENTED)
        proposals[capabilityId] = updated
        return updated
    }

    override suspend fun testCapability(capabilityId: String): CapabilityValidationResult {
        val proposal = proposals[capabilityId] ?: return CapabilityValidationResult(false, "Proposal not found")
        // Execute test suite inside workspace sandbox
        val req = ExecutionRequest(
            executable = "kotlinc",
            arguments = listOf("-script", "experimental/capabilities/$capabilityId/tests/run_tests.kts"),
            workingDirectory = "experimental/capabilities/$capabilityId"
        )
        val res = executionRouter.execute(req)
        val isSuccess = res.status.isSuccess
        val updated = proposal.copy(stage = if (isSuccess) CapabilityLifecycleStage.TESTED else CapabilityLifecycleStage.IMPLEMENTED)
        proposals[capabilityId] = updated
        return CapabilityValidationResult(
            isValid = isSuccess,
            testResultsSummary = res.stdout,
            errors = if (isSuccess) emptyList() else listOf(res.stderr)
        )
    }

    override suspend fun validateCapability(capabilityId: String): CapabilityValidationResult {
        val proposal = proposals[capabilityId] ?: return CapabilityValidationResult(false, "Proposal not found")
        val isTested = (proposal.stage == CapabilityLifecycleStage.TESTED)
        val updated = proposal.copy(stage = if (isTested) CapabilityLifecycleStage.VALIDATED else proposal.stage)
        proposals[capabilityId] = updated
        return CapabilityValidationResult(
            isValid = isTested,
            testResultsSummary = if (isTested) "Capability passed validation checks" else "Capability has not completed testing stage"
        )
    }

    override suspend fun registerExperimentalCapability(capabilityId: String): Boolean {
        val proposal = proposals[capabilityId] ?: return false
        if (proposal.stage != CapabilityLifecycleStage.VALIDATED) return false
        proposals[capabilityId] = proposal.copy(stage = CapabilityLifecycleStage.REGISTERED)
        return true
    }

    override suspend fun evaluateCapability(capabilityId: String): CapabilityLifecycleStage {
        val proposal = proposals[capabilityId] ?: return CapabilityLifecycleStage.DISCOVERED
        if (proposal.stage == CapabilityLifecycleStage.REGISTERED) {
            val updated = proposal.copy(stage = CapabilityLifecycleStage.EVALUATED)
            proposals[capabilityId] = updated
            return CapabilityLifecycleStage.EVALUATED
        }
        return proposal.stage
    }

    override suspend fun promoteCapability(capabilityId: String): Boolean {
        val proposal = proposals[capabilityId] ?: return false
        if (proposal.stage == CapabilityLifecycleStage.EVALUATED) {
            proposals[capabilityId] = proposal.copy(
                stage = CapabilityLifecycleStage.PROMOTED,
                isExperimental = false
            )
            return true
        }
        return false
    }
}
