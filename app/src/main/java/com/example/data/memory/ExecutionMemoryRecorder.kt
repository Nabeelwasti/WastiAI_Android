package com.example.data.memory

import android.content.Context
import android.util.Log
import com.example.data.db.ExecutionAuditEntity
import com.example.data.db.WastiDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentLinkedQueue

import com.example.data.agent.runtime.ExecutionFact
import com.example.data.agent.runtime.TerminalTruthState

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
    val terminalTruthState: TerminalTruthState? = null,
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

    suspend fun recordExecutionFact(fact: ExecutionFact, context: Context? = null) = withContext(Dispatchers.IO) {
        val record = ExecutionRecord(
            recordId = fact.factId,
            taskId = fact.taskId,
            goal = fact.command,
            interpretedIntent = fact.actionId,
            selectedCapability = fact.capabilityId,
            selectedNode = fact.nodeId,
            stepsCount = 1,
            durationMs = fact.durationMs,
            isSuccess = fact.isVerifiedSuccess,
            verificationStatus = fact.verificationStatus.name,
            verificationEvidence = fact.verificationEvidence,
            recoveryStrategy = fact.recoveryStrategyApplied,
            error = fact.rawError,
            terminalTruthState = fact.terminalTruthState,
            timestamp = fact.completedAt
        )
        recordExecutionOutcome(record, context)
    }

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
                val statusStr = when {
                    record.terminalTruthState != null -> record.terminalTruthState.name
                    record.isSuccess == true -> "SUCCESS"
                    record.isSuccess == false -> "FAILED"
                    else -> "UNVERIFIED"
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

        // Truthful state mapping: Do NOT claim VERIFIED_FAILED if task succeeded unverified
        try {
            val outcomeStr = when {
                record.terminalTruthState != null -> record.terminalTruthState.name
                record.isSuccess == true -> "VERIFIED_SUCCESS"
                record.verificationStatus == "VERIFICATION_FAILED" -> "VERIFICATION_FAILED"
                record.isSuccess == false -> "EXECUTION_FAILED"
                else -> "COMPLETED_UNVERIFIED"
            }

            val memoryContent = buildString {
                appendLine("[EXECUTION_GRAPH_RECORD]")
                appendLine("Task: ${record.taskId} | Goal: ${record.goal}")
                appendLine("Intent: ${record.interpretedIntent} | Capability: ${record.selectedCapability} | Node: ${record.selectedNode}")
                appendLine("Truth State: $outcomeStr | Verification: ${record.verificationStatus} in ${record.durationMs}ms")
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

            // High importance for verified success or explicit failures needing debugging
            val importance = when {
                record.terminalTruthState == TerminalTruthState.COMPLETED_VERIFIED || record.isSuccess == true -> 0.8f
                record.terminalTruthState == TerminalTruthState.VERIFICATION_FAILED || record.isSuccess == false -> 0.7f
                else -> 0.4f
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
            val terminalTruth = try {
                TerminalTruthState.valueOf(audit.status)
            } catch (_: Exception) {
                when (audit.status) {
                    "SUCCESS" -> if (audit.verificationStatus == "VERIFIED") TerminalTruthState.COMPLETED_VERIFIED else TerminalTruthState.COMPLETED_UNVERIFIED
                    "FAILED" -> if (audit.verificationStatus == "VERIFICATION_FAILED") TerminalTruthState.VERIFICATION_FAILED else TerminalTruthState.EXECUTION_FAILED
                    else -> null
                }
            }
            val isSuccess = when {
                terminalTruth == TerminalTruthState.COMPLETED_VERIFIED || terminalTruth == TerminalTruthState.COMPLETED_UNVERIFIED -> true
                terminalTruth == TerminalTruthState.EXECUTION_FAILED || terminalTruth == TerminalTruthState.VERIFICATION_FAILED -> false
                audit.status == "SUCCESS" -> true
                audit.status == "FAILED" -> false
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
                terminalTruthState = terminalTruth,
                timestamp = audit.timestamp
            )
        } catch (e: Exception) {
            Log.w(TAG, "Error fetching last execution for capability $capabilityId: ${e.message}")
            null
        }
    }

    fun getRecentExecutions(limit: Int = 20, context: Context? = null): List<ExecutionRecord> {
        val inMemory = executionHistory.toList().takeLast(limit).reversed()
        if (inMemory.isNotEmpty()) return inMemory

        // Fallback to database when in-memory queue is empty (e.g. after process restart)
        val ctx = context ?: com.example.WastiApplication.instance ?: return emptyList()
        return try {
            val db = WastiDatabase.getDatabase(ctx)
            val audits = kotlinx.coroutines.runBlocking {
                db.executionAuditDao().getRecentAuditsSync(limit)
            }
            audits.map { audit ->
                val terminalTruth = try {
                    TerminalTruthState.valueOf(audit.status)
                } catch (_: Exception) {
                    when (audit.status) {
                        "SUCCESS" -> if (audit.verificationStatus == "VERIFIED") TerminalTruthState.COMPLETED_VERIFIED else TerminalTruthState.COMPLETED_UNVERIFIED
                        "FAILED" -> if (audit.verificationStatus == "VERIFICATION_FAILED") TerminalTruthState.VERIFICATION_FAILED else TerminalTruthState.EXECUTION_FAILED
                        else -> null
                    }
                }
                val isSuccess = when {
                    terminalTruth == TerminalTruthState.COMPLETED_VERIFIED || terminalTruth == TerminalTruthState.COMPLETED_UNVERIFIED -> true
                    terminalTruth == TerminalTruthState.EXECUTION_FAILED || terminalTruth == TerminalTruthState.VERIFICATION_FAILED -> false
                    audit.status == "SUCCESS" -> true
                    audit.status == "FAILED" -> false
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
                    terminalTruthState = terminalTruth,
                    timestamp = audit.timestamp
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error fetching recent audits from database: ${e.message}")
            emptyList()
        }
    }
}
