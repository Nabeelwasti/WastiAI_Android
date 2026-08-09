package com.example.data.core

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Data model for proposed SkillMatrix changes queued for Admin Approval.
 */
data class ProposedSkillMatrixChange(
    val id: String = System.currentTimeMillis().toString(),
    val currentServices: List<String>,
    val proposedServices: List<String>,
    val addedSkills: List<String>,
    val removedSkills: List<String>,
    val reasoning: String,
    val jsonDiff: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * WastiRootController
 * Privileged OS Controller for Wasti AI OS with self-modification and code generation
 * capabilities, persistent execution write-access, and Continuous Learning / Admin Approval flows.
 */
object WastiRootController {
    private const val TAG = "WastiRootController"

    private val _pendingProposal = MutableStateFlow<ProposedSkillMatrixChange?>(null)
    val pendingProposal: StateFlow<ProposedSkillMatrixChange?> = _pendingProposal.asStateFlow()

    private val _activeSkillMatrix = MutableStateFlow(SkillMatrix())
    val activeSkillMatrix: StateFlow<SkillMatrix> = _activeSkillMatrix.asStateFlow()

    /**
     * Privileged write access method that can write code or scripts to the
     * app/src/main/java/com/example/data/worker/ directory or internal files.
     */
    fun privilegedExecute(
        context: Context,
        targetFileName: String,
        fileContent: String,
        subDir: String = "app/src/main/java/com/example/data/worker/"
    ): Boolean {
        return try {
            val projectDir = context.filesDir.parentFile?.parentFile ?: context.filesDir
            val targetFolder = File(projectDir, subDir)
            if (!targetFolder.exists()) {
                targetFolder.mkdirs()
            }
            val targetFile = File(targetFolder, targetFileName)
            targetFile.writeText(fileContent)
            Log.i(TAG, "Privileged execution written successfully to ${targetFile.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Privileged execution failed writing $targetFileName to $subDir", e)
            try {
                val fallbackFolder = File(context.filesDir, "workers")
                if (!fallbackFolder.exists()) fallbackFolder.mkdirs()
                val fallbackFile = File(fallbackFolder, targetFileName)
                fallbackFile.writeText(fileContent)
                Log.i(TAG, "Privileged execution fallback written to ${fallbackFile.absolutePath}")
                true
            } catch (ex: Exception) {
                Log.e(TAG, "Fallback privileged execution failed", ex)
                false
            }
        }
    }

    /**
     * Save/append match scores and client feedback into TrainingLog.json
     * for the Continuous Learning Loop.
     */
    fun logTrainingMetrics(
        context: Context,
        matchScores: List<Pair<String, Int>>,
        clientFeedbackList: List<Pair<String, String>> = emptyList()
    ) {
        try {
            val logFile = File(context.filesDir, "TrainingLog.json")
            val currentArray = if (logFile.exists() && logFile.length() > 0) {
                try {
                    JSONArray(logFile.readText())
                } catch (_: Exception) {
                    JSONArray()
                }
            } else {
                JSONArray()
            }

            val timestampStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

            matchScores.forEach { (leadTitle, score) ->
                val matchingFeedback = clientFeedbackList.find { it.first == leadTitle }?.second ?: ""
                val entry = JSONObject().apply {
                    put("timestamp", timestampStr)
                    put("leadTitle", leadTitle)
                    put("matchScore", score)
                    put("clientFeedback", matchingFeedback)
                }
                currentArray.put(entry)
            }

            logFile.writeText(currentArray.toString(2))
            Log.i(TAG, "Updated TrainingLog.json with ${matchScores.size} entries. Total log size: ${currentArray.length()}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed writing to TrainingLog.json", e)
        }
    }

    /**
     * Get content of TrainingLog.json
     */
    fun getTrainingLogContent(context: Context): String {
        val logFile = File(context.filesDir, "TrainingLog.json")
        return if (logFile.exists()) logFile.readText() else "[]"
    }

    /**
     * Submit a proposed SkillMatrix update from SelfEnhancementWorker
     */
    fun submitSkillMatrixProposal(proposal: ProposedSkillMatrixChange) {
        _pendingProposal.value = proposal
        Log.i(TAG, "New SkillMatrix proposal submitted for Admin Approval: Added=${proposal.addedSkills}, Removed=${proposal.removedSkills}")
    }

    /**
     * Admin approves proposed SkillMatrix
     */
    fun approveProposal(): Boolean {
        val proposal = _pendingProposal.value ?: return false
        val newMatrix = SkillMatrix(
            services = proposal.proposedServices,
            description = "Updated SkillMatrix optimized by SelfEnhancementWorker AI analysis. Active targeting: ${proposal.proposedServices.joinToString(", ")}"
        )
        _activeSkillMatrix.value = newMatrix
        _pendingProposal.value = null
        Log.i(TAG, "SkillMatrix update APPROVED by Admin. Active services: ${newMatrix.services}")
        return true
    }

    /**
     * Admin rejects proposed SkillMatrix
     */
    fun rejectProposal() {
        Log.i(TAG, "SkillMatrix proposal REJECTED by Admin.")
        _pendingProposal.value = null
    }
}
