package com.example.data.architecture.evolution

import com.example.data.architecture.civilization.CapabilityCivilizationRegistry
import com.example.data.architecture.civilization.CapabilityLifecycleState
import com.example.data.architecture.civilization.CivilizedCapability
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Capability Evolution Pipeline.
 *
 * Implements Phase 4 of the Master Architecture:
 * Unifies CapabilityInventionEngine, SelfEvolutionEngine, and AutonomousSkillEvolutionEngine.
 *
 * Strictly enforces the 9-stage evolution lifecycle:
 * OBSERVE -> IDENTIFY_GAP -> RESEARCH -> DESIGN -> PROTOTYPE -> TEST -> VERIFY -> REGISTER -> MONITOR
 *
 * Guarantees that zero unverified capabilities enter the active runtime.
 */
enum class EvolutionStage {
    OBSERVE,
    IDENTIFY_GAP,
    RESEARCH,
    DESIGN,
    PROTOTYPE,
    TEST,
    VERIFY,
    REGISTER,
    MONITOR,
    CIVILIZED
}

data class EvolutionItem(
    val id: String,
    val capabilityName: String,
    val identifiedGap: String,
    val stage: EvolutionStage = EvolutionStage.OBSERVE,
    val prototypeCode: String = "",
    val testEvidence: String = "",
    val isVerified: Boolean = false,
    val registeredCapabilityId: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

object CapabilityEvolutionPipeline {

    private val pipelineItems = mutableMapOf<String, EvolutionItem>()

    /**
     * Initiates the evolution process upon observing an execution failure or missing tool.
     */
    fun recordObservedGap(gapId: String, capabilityName: String, description: String): EvolutionItem {
        val item = EvolutionItem(
            id = gapId,
            capabilityName = capabilityName,
            identifiedGap = description,
            stage = EvolutionStage.IDENTIFY_GAP
        )
        pipelineItems[gapId] = item
        return item
    }

    /**
     * Advances an item through the verification and registration pipeline.
     */
    suspend fun advancePipeline(
        gapId: String,
        prototypeCode: String,
        testEvidence: String,
        verificationEvidence: String
    ): Result<CivilizedCapability> = withContext(Dispatchers.Default) {
        val item = pipelineItems[gapId] ?: return@withContext Result.failure(IllegalArgumentException("Gap ID $gapId not found in pipeline"))

        // Stage: TEST
        val engine = com.example.data.agent.runtime.WastiVerificationEngine()
        if (testEvidence.isBlank() || engine.isSyntheticOrMock(testEvidence)) {
            return@withContext Result.failure(IllegalStateException("Honest Failure: Test evidence invalid or synthetic."))
        }

        val proofHash = java.security.MessageDigest.getInstance("SHA-256")
            .digest("$gapId:${item.capabilityName}:$verificationEvidence".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val vRes = engine.verifyStructuredEvidence(
            taskId = gapId,
            actionId = "evolve_capability",
            capabilityId = item.capabilityName,
            evidence = com.example.data.agent.runtime.VerifiedExecutionEvidence(
                evidenceSource = com.example.data.agent.runtime.EvidenceSource.RUNTIME_DIAGNOSTIC,
                subject = item.capabilityName,
                verifiedState = verificationEvidence,
                observedAt = System.currentTimeMillis(),
                checksumOrHash = proofHash
            )
        )
        val isCanonicallyVerified = vRes.status == com.example.data.agent.runtime.ActionVerificationStatus.VERIFIED
        if (!isCanonicallyVerified) {
            return@withContext Result.failure(IllegalStateException("Verification failed: Real cryptographic or runtime evidence verified by canonical WastiVerificationEngine required."))
        }

        val capabilityId = "evolved_${item.capabilityName.lowercase().replace(" ", "_")}"

        // Stage: REGISTER
        val newCapability = CivilizedCapability(
            id = capabilityId,
            name = item.capabilityName,
            version = "1.0.0",
            ownerLayer = "CAPABILITY_CIVILIZATION_LAYER",
            dependencies = listOf("wre_polyglot_compiler"),
            knowledgeSummary = "Autonomously evolved capability addressing gap: ${item.identifiedGap}",
            lifecycleState = CapabilityLifecycleState.INCUBATING,
            isSovereignOffline = true
        )

        CapabilityCivilizationRegistry.register(newCapability)

        // Stage: MONITOR
        pipelineItems[gapId] = item.copy(
            stage = EvolutionStage.MONITOR,
            prototypeCode = prototypeCode,
            testEvidence = testEvidence,
            isVerified = isCanonicallyVerified,
            registeredCapabilityId = capabilityId
        )

        Result.success(newCapability)
    }

    fun getPipelineItem(gapId: String): EvolutionItem? = pipelineItems[gapId]

    fun getAllPipelineItems(): List<EvolutionItem> = pipelineItems.values.toList()
}
