package com.example.data.memory

import android.content.Context
import android.util.Log
import com.example.data.db.ExecutionAuditEntity
import com.example.data.db.WastiDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentLinkedQueue

data class ExecutionRecord(
    val recordId: String = java.util.UUID.randomUUID().toString(),
    val taskId: String,
    val goal: String,
    val interpretedIntent: String,
    val selectedCapability: String,
    val selectedNode: String = "local_android_node",
    val stepsCount: Int = 1,
    val durationMs: Long = 0L,
    val isSuccess: Boolean? = null, // null = unknown/unverified, false = verified failure, true = verified success
    val verificationStatus: String = "UNVERIFIED",
    val verificationEvidence: String? = null,
    val recoveryStrategy: String? = null,
    val error: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Stage 10: Canonical Execution Memory Recorder.
 * Records meaningful autonomous workflows, actions, and self-correction strategies
 * directly into Wasti Memory and Room ExecutionAudit database.
 */
object ExecutionMemoryRecorder {
    private const val TAG = "ExecutionMemoryRecorder"
    private val executionHistory = ConcurrentLinkedQueue<ExecutionRecord>()

    suspend fun recordExecutionOutcome(record: ExecutionRecord, context: Context? = null) = withContext(Dispatchers.IO) {
        executionHistory.add(record)
        // Keep max 100 recent in-memory records
        while (executionHistory.size > 100) {
            executionHistory.poll()
        }

        val ctx = context ?: com.example.WastiApplication.instance
        if (ctx != null) {
            try {
                val db = WastiDatabase.getDatabase(ctx)
                val statusStr = when (record.isSuccess) {
                    true -> "SUCCESS"
                    false -> "FAILED"
                    null -> "UNVERIFIED"
                }
                db.executionAuditDao().insertAudit(
                    ExecutionAuditEntity(
                        auditId = record.recordId,
                        taskId = record.taskId,
                        userGoal = record.goal,
                        capabilityId = record.selectedCapability,
                        actionName = record.interpretedIntent,
                        executionDestination = record.selectedNode,
                        status = statusStr,
                        verificationStatus = record.verificationStatus,
                        verificationEvidence = record.verificationEvidence,
                        error = record.error,
                        executionDurationMs = record.durationMs,
                        timestamp = record.timestamp
                    )
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to persist execution audit to Room database: ${e.message}")
            }
        }

        // Only verified success or explicit failures get indexed into semantic memory
        try {
            val outcomeStr = when (record.isSuccess) {
                true -> "VERIFIED_SUCCESS"
                false -> "VERIFIED_FAILED"
                null -> "OUTCOME_UNVERIFIED"
            }

            val memoryContent = buildString {
                appendLine("[EXECUTION_GRAPH_RECORD]")
                appendLine("Task: ${record.taskId} | Goal: ${record.goal}")
                appendLine("Intent: ${record.interpretedIntent} | Capability: ${record.selectedCapability} | Node: ${record.selectedNode}")
                appendLine("Result: $outcomeStr | Verification: ${record.verificationStatus} in ${record.durationMs}ms")
                if (!record.verificationEvidence.isNullOrBlank()) {
                    appendLine("Evidence: ${record.verificationEvidence}")
                }
                if (!record.error.isNullOrBlank()) {
                    appendLine("Error: ${record.error}")
                }
                if (!record.recoveryStrategy.isNullOrBlank()) {
                    appendLine("Recovery Applied: ${record.recoveryStrategy}")
                }
            }

            // Only promote high importance for verified success or verified failure for debugging
            val importance = when (record.isSuccess) {
                true -> 0.8f
                false -> 0.9f
                null -> 0.3f
            }

            MemoryManager.saveMemory(
                key = "ExecutionGraph_${record.taskId}",
                category = "System Execution History",
                value = memoryContent,
                importanceScore = importance
            )
            Log.d(TAG, "Recorded execution graph for task ${record.taskId} with outcome: $outcomeStr")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist execution record to MemoryManager", e)
        }
    }

    suspend fun getLastExecutionForCapability(capabilityId: String, context: Context? = null): ExecutionRecord? = withContext(Dispatchers.IO) {
        val memoryMatch = executionHistory.toList().lastOrNull { it.selectedCapability.equals(capabilityId, ignoreCase = true) }
        if (memoryMatch != null) return@withContext memoryMatch

        val ctx = context ?: com.example.WastiApplication.instance ?: return@withContext null
        try {
            val db = WastiDatabase.getDatabase(ctx)
            val audit = db.executionAuditDao().getLastExecutionForCapability(capabilityId) ?: return@withContext null
            val isSuccess = when (audit.status) {
                "SUCCESS" -> true
                "FAILED" -> false
                else -> null
            }
            ExecutionRecord(
                recordId = audit.auditId,
                taskId = audit.taskId,
                goal = audit.userGoal,
                interpretedIntent = audit.actionName,
                selectedCapability = audit.capabilityId,
                selectedNode = audit.executionDestination,
                durationMs = audit.executionDurationMs,
                isSuccess = isSuccess,
                verificationStatus = audit.verificationStatus,
                verificationEvidence = audit.verificationEvidence,
                error = audit.error,
                timestamp = audit.timestamp
            )
        } catch (e: Exception) {
            Log.w(TAG, "Error fetching last execution for capability $capabilityId: ${e.message}")
            null
        }
    }

    fun getRecentExecutions(limit: Int = 20): List<ExecutionRecord> {
        return executionHistory.toList().takeLast(limit).reversed()
    }
}
