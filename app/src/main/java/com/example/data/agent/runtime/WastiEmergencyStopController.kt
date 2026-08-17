package com.example.data.agent.runtime

import java.util.concurrent.atomic.AtomicReference

/**
 * A consistent, immutable view of the emergency-stop latch.
 *
 * [generation] changes only when the latch changes state, allowing executors to
 * detect a stop/reset transition without relying on separate mutable fields.
 */
data class EmergencyStopSnapshot(
    val isStopped: Boolean = false,
    val reason: String? = null,
    val triggeredAt: Long? = null,
    val lastResetAt: Long? = null,
    val generation: Long = 0L
)

/**
 * Process-local, thread-safe emergency-stop latch.
 *
 * Triggering is fail-safe: an empty reason is replaced with a useful audit
 * message, and a second trigger never overwrites the original stop reason.
 * This class signals an emergency stop; active executors must check
 * [isEmergencyStopped] or [snapshot] at their own safe interruption points.
 */
class WastiEmergencyStopController : EmergencyStopController {

    private val state = AtomicReference(EmergencyStopSnapshot())

    override val isEmergencyStopped: Boolean
        get() = state.get().isStopped

    fun getReason(): String? = state.get().reason

    /** Returns one internally consistent state snapshot. */
    fun snapshot(): EmergencyStopSnapshot = state.get()

    /**
     * Latches the stop state. The first trigger wins so its reason remains
     * available for auditing until an explicit reset succeeds.
     */
    override fun triggerEmergencyStop(reason: String) {
        val normalizedReason = reason.trim().ifBlank {
            "Emergency stop triggered without a specified reason."
        }
        val now = System.currentTimeMillis()

        updateState { current ->
            if (current.isStopped) {
                current
            } else {
                current.copy(
                    isStopped = true,
                    reason = normalizedReason,
                    triggeredAt = now,
                    generation = current.generation + 1L
                )
            }
        }
    }

    /**
     * Clears the latch. Authorization for this operation belongs in the caller's
     * policy layer; this controller performs the state transition atomically.
     */
    override fun resetEmergencyStop() {
        val now = System.currentTimeMillis()

        updateState { current ->
            if (!current.isStopped) {
                current
            } else {
                current.copy(
                    isStopped = false,
                    reason = null,
                    lastResetAt = now,
                    generation = current.generation + 1L
                )
            }
        }
    }

    private inline fun updateState(transform: (EmergencyStopSnapshot) -> EmergencyStopSnapshot) {
        while (true) {
            val current = state.get()
            val next = transform(current)
            if (next === current || state.compareAndSet(current, next)) return
        }
    }
}
