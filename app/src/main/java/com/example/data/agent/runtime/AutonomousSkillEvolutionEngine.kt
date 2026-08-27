package com.example.data.agent.runtime

import android.content.Context
import com.example.data.db.ExecutionAuditEntity
import com.example.data.db.LearnedSkillDao
import com.example.data.db.LearnedSkillEntity
import com.example.data.db.WastiDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Autonomous Skill Evolution Engine for Wasti AI OS.
 * Transforms verified multi-step capability executions into persistent learned skills.
 * Manages skill lifecycle, regression scoring, promotion tiers, and automated learning.
 */
class AutonomousSkillEvolutionEngine(
    private val context: Context,
    private val database: WastiDatabase = WastiDatabase.getDatabase(context),
    private val learnedSkillDao: LearnedSkillDao = database.learnedSkillDao(),
    private val libraryManager: CapabilityLibraryManager = CapabilityLibraryManager(),
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {

    fun getLearnedSkillsFlow(): Flow<List<LearnedSkillEntity>> = learnedSkillDao.getAllLearnedSkills()

    suspend fun getActiveLearnedSkills(): List<LearnedSkillEntity> = learnedSkillDao.getActiveSkillsSync()

    /**
     * Evaluates a completed task execution graph. If the execution was truthfully verified
     * and non-trivial, it automatically packages it into a Learned Skill candidate.
     */
    suspend fun learnFromExecution(
        originatingTaskId: String,
        userGoal: String,
        planGraph: PlannedCapabilityGraph,
        executionAudits: List<ExecutionAuditEntity>
    ): LearnedSkillEntity? {
        // Validation 1: Must have executed at least 1 step successfully
        if (executionAudits.isEmpty()) return null

        // Validation 2: Must be fully verified, not merely completed or fabricated
        val allVerified = executionAudits.all {
            it.verificationStatus == UnifiedVerificationStatus.VERIFIED.name
        }
        if (!allVerified) return null

        val skillId = "skill_${UUID.randomUUID().toString().take(8)}"
        val skillName = generateSkillName(userGoal)
        val description = "Learned autonomous skill for goal: $userGoal"

        // Serialize execution graph
        val graphArray = JSONArray()
        for (node in planGraph.nodes) {
            val nodeObj = JSONObject().apply {
                put("nodeId", node.nodeId)
                put("capabilityId", node.capabilityId)
                put("actionName", node.actionName)
                put("description", node.description)
                put("dependencies", JSONArray(node.dependencies))
                put("expectedEvidenceType", node.expectedEvidenceType)
            }
            graphArray.put(nodeObj)
        }

        // Required capabilities
        val capabilities = planGraph.nodes.map { it.capabilityId }.distinct()
        val capabilitiesJson = JSONArray(capabilities).toString()

        // Required permissions
        val permissions = mutableListOf<String>()
        if (capabilities.any { it.contains("device") || it.contains("accessibility") }) {
            permissions.add("android.permission.BIND_ACCESSIBILITY_SERVICE")
        }
        val permissionsJson = JSONArray(permissions).toString()

        // Input parameters schema
        val inputParamsObj = JSONObject()
        planGraph.nodes.forEach { node ->
            node.inputParameters.forEach { (k, v) ->
                inputParamsObj.put(k, v.toString())
            }
        }

        // Verification evidence summary
        val evidenceSummary = executionAudits.mapNotNull { it.verificationEvidence }.joinToString(" | ")

        val entity = LearnedSkillEntity(
            skillId = skillId,
            name = skillName,
            description = description,
            originatingTaskId = originatingTaskId,
            executionGraphJson = graphArray.toString(),
            requiredCapabilitiesJson = capabilitiesJson,
            requiredPermissionsJson = permissionsJson,
            inputParametersJson = inputParamsObj.toString(),
            expectedOutputsJson = "{\"status\":\"VERIFIED\"}",
            verificationCriteriaJson = "{\"allStepsVerified\":true}",
            actualEvidenceSummary = evidenceSummary,
            successCount = 1,
            failureCount = 0,
            recoveryCount = 0,
            regressionScore = 1.0f,
            promotionTier = CapabilityPromotionTier.SANDBOX_EXPERIMENTAL.name,
            operationalStatus = "ACTIVE",
            version = "1.0.0"
        )

        learnedSkillDao.insertSkill(entity)

        // Also register into CapabilityLibraryManager as a dynamic package
        libraryManager.registerPackage(
            CapabilityPackage(
                packageId = "pkg_$skillId",
                name = skillName,
                version = "1.0.0",
                description = description,
                tier = CapabilityPromotionTier.SANDBOX_EXPERIMENTAL,
                author = "WastiAutonomousLearner",
                capabilitiesProvided = capabilities,
                verifiedRunsCount = 1,
                regressionScore = 1.0f
            )
        )

        return entity
    }

    /**
     * Promotes a learned skill to higher tiers after repeated verified executions.
     */
    suspend fun recordExecutionOutcome(skillId: String, wasVerified: Boolean) {
        val skill = learnedSkillDao.getSkillById(skillId) ?: return
        if (wasVerified) {
            val newSuccess = skill.successCount + 1
            val newScore = (newSuccess.toFloat() / (newSuccess + skill.failureCount)).coerceIn(0.0f, 1.0f)
            val newTier = if (newSuccess >= 5 && newScore >= 0.95f) {
                CapabilityPromotionTier.COMMUNITY_VERIFIED.name
            } else if (newSuccess >= 15 && newScore >= 0.99f) {
                CapabilityPromotionTier.CORE_PROMOTED.name
            } else {
                skill.promotionTier
            }
            learnedSkillDao.recordSkillSuccess(skillId)
            learnedSkillDao.updatePromotionTier(skillId, newTier)
        } else {
            val newFailure = skill.failureCount + 1
            val newScore = (skill.successCount.toFloat() / (skill.successCount + newFailure)).coerceIn(0.0f, 1.0f)
            learnedSkillDao.recordSkillFailure(skillId, newScore)
            if (newScore < 0.5f) {
                learnedSkillDao.updatePromotionTier(skillId, CapabilityPromotionTier.SANDBOX_EXPERIMENTAL.name)
            }
        }
    }

    private fun generateSkillName(goal: String): String {
        val clean = goal.replace(Regex("[^a-zA-Z0-9 ]"), "").trim()
        val words = clean.split(" ").filter { it.isNotEmpty() }.take(5)
        return words.joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
    }
}
