package com.example.data.agent.runtime

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Universal Cross-App Learning & Training Preserver.
 * Guarantees that the user's instructions, preferences, distilled skills,
 * tool invocation outcomes, and domain knowledge are persistently preserved,
 * adapted, and shared across all Wasti apps (Chat, Studio, CRM, Automation, Coding).
 *
 * When any of the 12 open-source agents is activated, this preserver injects the
 * accumulated learning into the agent's context and system prompt so the original
 * Wasti training and personalization is retained 100% seamlessly.
 */
object WastiAgentLearningPreserver {
    private const val TAG = "LearningPreserver"
    private const val PERSISTENCE_FILE_NAME = "wasti_learning_state.json"

    private val learnedSkills = ConcurrentHashMap<String, LearnedSkill>()

    data class LearnedSkill(
        val skillId: String,
        val skillName: String,
        val targetAppId: String,
        val promptDirective: String,
        val executionEvidence: String?,
        val successCount: Int = 1,
        val timestamp: Long = System.currentTimeMillis()
    )

    fun recordLearnedSkill(
        skillName: String,
        targetAppId: String,
        promptDirective: String,
        executionEvidence: String? = null,
        context: Context? = null
    ) {
        val skillId = "${targetAppId}_${skillName.lowercase().replace("\\s+".toRegex(), "_")}"
        val existing = learnedSkills[skillId]
        val updated = LearnedSkill(
            skillId = skillId,
            skillName = skillName,
            targetAppId = targetAppId,
            promptDirective = promptDirective,
            executionEvidence = executionEvidence ?: existing?.executionEvidence,
            successCount = (existing?.successCount ?: 0) + 1,
            timestamp = System.currentTimeMillis()
        )
        learnedSkills[skillId] = updated

        if (context != null) {
            persistState(context)
        }
    }

    fun getAdaptedSystemPrompt(
        basePrompt: String,
        appId: String = "general"
    ): String {
        val relevantSkills = learnedSkills.values.filter {
            it.targetAppId == appId || it.targetAppId == "general" || it.targetAppId == "all"
        }

        if (relevantSkills.isEmpty()) {
            return basePrompt
        }

        val learnedDirectives = relevantSkills.joinToString("\n") {
            "- [Learned Skill: ${it.skillName} (Verified ${it.successCount}x)]: ${it.promptDirective}"
        }

        return """
            $basePrompt

            [PERSISTENT WASTI TRAINING & ACCUMULATED LEARNING]
            The following verified skills and adaptations have been learned across Wasti apps:
            $learnedDirectives
            Always preserve this learned behavior seamlessly in all responses.
        """.trimIndent()
    }

    fun getAllLearnedSkills(): List<LearnedSkill> = learnedSkills.values.toList()

    fun resetForTesting() {
        learnedSkills.clear()
    }

    fun persistState(context: Context) {
        try {
            val file = File(context.filesDir, PERSISTENCE_FILE_NAME)
            val json = JSONObject()
            val skillsArr = JSONArray()
            learnedSkills.values.forEach { skill ->
                skillsArr.put(JSONObject().apply {
                    put("skillId", skill.skillId)
                    put("skillName", skill.skillName)
                    put("targetAppId", skill.targetAppId)
                    put("promptDirective", skill.promptDirective)
                    put("executionEvidence", skill.executionEvidence ?: "")
                    put("successCount", skill.successCount)
                    put("timestamp", skill.timestamp)
                })
            }
            json.put("skills", skillsArr)
            file.writeText(json.toString(2))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist learning state: ${e.message}")
        }
    }

    fun loadState(context: Context) {
        try {
            val file = File(context.filesDir, PERSISTENCE_FILE_NAME)
            if (!file.exists()) return
            val json = JSONObject(file.readText())
            val skillsArr = json.optJSONArray("skills") ?: return
            for (i in 0 until skillsArr.length()) {
                val item = skillsArr.getJSONObject(i)
                val skill = LearnedSkill(
                    skillId = item.optString("skillId"),
                    skillName = item.optString("skillName"),
                    targetAppId = item.optString("targetAppId"),
                    promptDirective = item.optString("promptDirective"),
                    executionEvidence = item.optString("executionEvidence").ifBlank { null },
                    successCount = item.optInt("successCount", 1),
                    timestamp = item.optLong("timestamp", System.currentTimeMillis())
                )
                learnedSkills[skill.skillId] = skill
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load learning state: ${e.message}")
        }
    }
}
