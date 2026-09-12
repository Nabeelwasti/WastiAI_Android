package com.example.data.agent.runtime

import java.util.concurrent.ConcurrentHashMap

/**
 * Canonical execution intelligence primitives shared by every Wasti execution body.
 *
 * This layer deliberately has no fixed task-duration limit. Duration is telemetry.
 * It must never be treated as proof that work is stalled or as permission to kill work.
 * Explicit cancellation, emergency stop, or a real unrecoverable process failure are
 * the only valid termination causes.
 */
object AdaptiveExecutionIntelligence {
    enum class Health {
        ACTIVE_PROGRESS,
        ACTIVE_WAITING,
        DEGRADED,
        STALLED_SUSPECTED,
        RECOVERING,
        BLOCKED,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    data class Checkpoint(
        val taskId: String,
        val objective: String,
        val stage: String,
        val health: Health,
        val strategy: String,
        val provider: String?,
        val runtime: String?,
        val actions: List<String>,
        val observations: List<String>,
        val errors: List<String>,
        val evidence: List<String>,
        val nextAction: String?,
        val resumePoint: String?,
        val startedAt: Long,
        val updatedAt: Long = System.currentTimeMillis()
    )

    private val checkpoints = ConcurrentHashMap<String, Checkpoint>()

    fun save(checkpoint: Checkpoint): Checkpoint {
        checkpoints[checkpoint.taskId] = checkpoint.copy(updatedAt = System.currentTimeMillis())
        return checkpoints[checkpoint.taskId]!!
    }

    fun current(taskId: String): Checkpoint? = checkpoints[taskId]

    fun clear(taskId: String) {
        checkpoints.remove(taskId)
    }

    fun elapsedMs(checkpoint: Checkpoint, now: Long = System.currentTimeMillis()): Long =
        (now - checkpoint.startedAt).coerceAtLeast(0L)

    /**
     * A duration overrun is a diagnostic signal only. It never authorizes termination.
     * The caller should observe the live state, diagnose the reason for waiting/degradation,
     * select an alternative route when justified, and continue from the latest checkpoint.
     */
    fun diagnoseDuration(checkpoint: Checkpoint, now: Long = System.currentTimeMillis()): String =
        "Task ${checkpoint.taskId} has been active for ${elapsedMs(checkpoint, now)} ms; " +
            "duration is telemetry only. Observe reality before changing strategy or taking action."
}
