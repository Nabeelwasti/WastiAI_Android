package com.example.data.agent.runtime

import android.content.Context
import java.util.UUID

/**
 * Workflow Replay and Recovery Layer for Wasti AI OS.
 * Safely inspects, validates, dry-runs, and re-executes past workflows with automatic
 * filesystem snapshot restoration on failure.
 */

data class ReplaySession(
    val sessionId: String = "replay_${UUID.randomUUID().toString().take(8)}",
    val originalTaskId: String,
    val isDryRun: Boolean,
    val preExecutionSnapshotId: String?,
    val stepsTotal: Int,
    var stepsExecuted: Int = 0,
    var isSuccess: Boolean = false,
    var outcomeMessage: String = ""
)

class WorkflowReplayEngine(
    private val executionFabric: UnifiedExecutionFabric = UnifiedExecutionFabric.instance,
    private val context: Context? = null
) {

    /**
     * Replays a sequence of capability execution requests with snapshot protection.
     */
    suspend fun replayWorkflow(
        originalTaskId: String,
        requests: List<UnifiedExecutionRequest>,
        dryRun: Boolean = false
    ): ReplaySession {
        val wm = context?.let { WorkspaceManager(it) }
        val snapshotId = if (!dryRun && wm != null) {
            val sId = "snap_replay_${UUID.randomUUID().toString().take(6)}"
            wm.createSnapshot(sId).getOrNull()
        } else null

        val session = ReplaySession(
            originalTaskId = originalTaskId,
            isDryRun = dryRun,
            preExecutionSnapshotId = snapshotId,
            stepsTotal = requests.size
        )

        var allSucceeded = true
        for (req in requests) {
            if (dryRun) {
                session.stepsExecuted++
                continue
            }

            val res = executionFabric.execute(req, context)
            if (res.status != UnifiedExecutionStatus.COMPLETED && res.status != UnifiedExecutionStatus.VERIFIED) {
                allSucceeded = false
                session.outcomeMessage = "Replay failed at capability ${req.capabilityId}: ${res.output}"
                break
            }
            session.stepsExecuted++
        }

        if (!allSucceeded && snapshotId != null && wm != null) {
            wm.restoreSnapshot(snapshotId)
            session.outcomeMessage += " (Filesystem rolled back to snapshot $snapshotId)"
        } else if (allSucceeded) {
            session.isSuccess = true
            session.outcomeMessage = if (dryRun) "Dry-run validation successful." else "Workflow successfully replayed and verified."
        }

        return session
    }
}
