package com.example.data.memory

import android.util.Log
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
    val isSuccess: Boolean = true,
    val verificationEvidence: String? = null,
    val recoveryStrategy: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Stage 10: Canonical Execution Memory Recorder.
 * Records meaningful autonomous workflows, actions, and self-correction strategies
 * directly into Wasti Memory as an Execution Graph.
 */
object ExecutionMemoryRecorder {
    private const val TAG = "ExecutionMemoryRecorder"
    private val executionHistory = ConcurrentLinkedQueue<ExecutionRecord>()

    suspend fun recordExecutionOutcome(record: ExecutionRecord) = withContext(Dispatchers.IO) {
        executionHistory.add(record)
        // Keep max 100 recent in-memory records
        while (executionHistory.size > 100) {
            executionHistory.poll()
        }

        try {
            // Index into semantic memory
            val memoryContent = buildString {
                appendLine("[EXECUTION_GRAPH_RECORD]")
                appendLine("Task: ${record.taskId} | Goal: ${record.goal}")
                appendLine("Intent: ${record.interpretedIntent} | Capability: ${record.selectedCapability} | Node: ${record.selectedNode}")
                appendLine("Result: ${if (record.isSuccess) "SUCCESS" else "FAILED"} in ${record.durationMs}ms")
                if (!record.verificationEvidence.isNullOrBlank()) {
                    appendLine("Evidence: ${record.verificationEvidence}")
                }
                if (!record.recoveryStrategy.isNullOrBlank()) {
                    appendLine("Recovery Applied: ${record.recoveryStrategy}")
                }
            }

            MemoryManager.saveMemory(
                key = "ExecutionGraph_${record.taskId}",
                category = "System Execution History",
                value = memoryContent,
                importanceScore = if (record.isSuccess) 0.7f else 0.9f
            )
            Log.d(TAG, "Recorded execution graph for task ${record.taskId}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist execution record to MemoryManager", e)
        }
    }

    fun getRecentExecutions(limit: Int = 20): List<ExecutionRecord> {
        return executionHistory.toList().takeLast(limit).reversed()
    }
}
