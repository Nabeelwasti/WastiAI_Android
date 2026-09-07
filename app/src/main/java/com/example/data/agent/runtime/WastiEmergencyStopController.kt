package com.example.data.agent.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
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
 * Audit record capturing an emergency stop or reset event with cancellation metrics.
 */
data class EmergencyStopAuditEntry(
    val timestampMs: Long,
    val isStopped: Boolean,
    val reason: String?,
    val generation: Long,
    val cancelledScopesCount: Int = 0,
    val cancelledJobsCount: Int = 0,
    val cancelledHooksCount: Int = 0
)

/**
 * [P0-34] Process-wide, thread-safe emergency-stop controller and active cancellation hub.
 *
 * Grounding Invariants:
 * 1. Cancels active coroutine children, registered jobs, OkHttp network calls,
 *    and background WorkManager tasks where cancellation is possible.
 * 2. Immediately prevents any new execution from starting across UnifiedExecutionFabric,
 *    local neural inference, agent loop, and backend clients.
 * 3. Exposes reactive [stopStateFlow] and records observable audit trail in [auditHistory].
 */
class WastiEmergencyStopController : EmergencyStopController {

    private val state = AtomicReference(EmergencyStopSnapshot())
    private val _stopStateFlow = MutableStateFlow(EmergencyStopSnapshot())
    val stopStateFlow: StateFlow<EmergencyStopSnapshot> = _stopStateFlow.asStateFlow()

    private val registeredScopes = ConcurrentHashMap<String, CoroutineScope>()
    private val registeredJobs = ConcurrentHashMap<String, Job>()
    private val registeredHooks = ConcurrentHashMap<String, (reason: String) -> Unit>()
    private val registeredOkHttpClients = CopyOnWriteArrayList<OkHttpClient>()

    @Volatile
    private var workManagerCancellationHook: ((reason: String) -> Unit)? = null

    private val auditHistory = CopyOnWriteArrayList<EmergencyStopAuditEntry>()

    override val isEmergencyStopped: Boolean
        get() = state.get().isStopped

    fun getReason(): String? = state.get().reason

    /** Returns one internally consistent state snapshot. */
    fun snapshot(): EmergencyStopSnapshot = state.get()

    /** Returns an immutable copy of emergency stop audit history. */
    fun getAuditHistory(): List<EmergencyStopAuditEntry> = auditHistory.toList()

    fun registerScope(name: String, scope: CoroutineScope): AutoCloseable {
        registeredScopes[name] = scope
        return AutoCloseable { registeredScopes.remove(name) }
    }

    fun registerJob(name: String, job: Job): AutoCloseable {
        registeredJobs[name] = job
        return AutoCloseable { registeredJobs.remove(name) }
    }

    fun registerCancellationHook(name: String, hook: (reason: String) -> Unit): AutoCloseable {
        registeredHooks[name] = hook
        return AutoCloseable { registeredHooks.remove(name) }
    }

    fun registerOkHttpClient(client: OkHttpClient): AutoCloseable {
        registeredOkHttpClients.add(client)
        return AutoCloseable { registeredOkHttpClients.remove(client) }
    }

    fun registerWorkManagerCancellation(hook: (reason: String) -> Unit) {
        workManagerCancellationHook = hook
    }

    /**
     * Latches the stop state and triggers active cancellation across all registered components.
     */
    override fun triggerEmergencyStop(reason: String) {
        val normalizedReason = reason.trim().ifBlank {
            "Emergency stop triggered without a specified reason."
        }
        val now = System.currentTimeMillis()

        var updatedSnapshot: EmergencyStopSnapshot? = null
        updateState { current ->
            if (current.isStopped) {
                current
            } else {
                val next = current.copy(
                    isStopped = true,
                    reason = normalizedReason,
                    triggeredAt = now,
                    generation = current.generation + 1L
                )
                updatedSnapshot = next
                next
            }
        }

        val snap = updatedSnapshot ?: state.get()
        _stopStateFlow.value = snap

        // 1. Actively cancel registered coroutine scopes
        var cancelledScopes = 0
        for ((_, scope) in registeredScopes) {
            try {
                scope.coroutineContext.cancelChildren(CancellationException("Emergency Stop: $normalizedReason"))
                cancelledScopes++
            } catch (_: Throwable) {}
        }

        // 2. Actively cancel registered coroutine jobs
        var cancelledJobs = 0
        for ((_, job) in registeredJobs) {
            try {
                if (job.isActive) {
                    job.cancel(CancellationException("Emergency Stop: $normalizedReason"))
                    cancelledJobs++
                }
            } catch (_: Throwable) {}
        }

        // 3. Actively cancel in-flight OkHttp network calls
        for (client in registeredOkHttpClients) {
            try {
                client.dispatcher.cancelAll()
            } catch (_: Throwable) {}
        }

        // 4. Actively invoke WorkManager cancellation
        try {
            workManagerCancellationHook?.invoke(normalizedReason)
        } catch (_: Throwable) {}

        // 5. Actively invoke custom cancellation hooks (native runtime, mesh, etc.)
        var cancelledHooks = 0
        for ((_, hook) in registeredHooks) {
            try {
                hook(normalizedReason)
                cancelledHooks++
            } catch (_: Throwable) {}
        }

        auditHistory.add(
            EmergencyStopAuditEntry(
                timestampMs = now,
                isStopped = true,
                reason = normalizedReason,
                generation = snap.generation,
                cancelledScopesCount = cancelledScopes,
                cancelledJobsCount = cancelledJobs,
                cancelledHooksCount = cancelledHooks
            )
        )
    }

    /**
     * Clears the latch atomically and records observable reset event.
     */
    override fun resetEmergencyStop() {
        val now = System.currentTimeMillis()

        var updatedSnapshot: EmergencyStopSnapshot? = null
        updateState { current ->
            if (!current.isStopped) {
                current
            } else {
                val next = current.copy(
                    isStopped = false,
                    reason = null,
                    lastResetAt = now,
                    generation = current.generation + 1L
                )
                updatedSnapshot = next
                next
            }
        }

        val snap = updatedSnapshot ?: state.get()
        _stopStateFlow.value = snap

        auditHistory.add(
            EmergencyStopAuditEntry(
                timestampMs = now,
                isStopped = false,
                reason = null,
                generation = snap.generation
            )
        )
    }

    private inline fun updateState(transform: (EmergencyStopSnapshot) -> EmergencyStopSnapshot) {
        while (true) {
            val current = state.get()
            val next = transform(current)
            if (next === current || state.compareAndSet(current, next)) return
        }
    }

    companion object {
        val instance: WastiEmergencyStopController
            get() = com.example.data.di.WastiServiceLocator.emergencyStopController

        val isEmergencyStopped: Boolean
            get() = instance.isEmergencyStopped

        val stopStateFlow: StateFlow<EmergencyStopSnapshot>
            get() = instance.stopStateFlow

        fun triggerEmergencyStop(reason: String) = instance.triggerEmergencyStop(reason)
        fun triggerReset() = instance.triggerReset()
        fun registerScope(name: String, scope: CoroutineScope): AutoCloseable = instance.registerScope(name, scope)
        fun registerJob(name: String, job: Job): AutoCloseable = instance.registerJob(name, job)
        fun registerCancellationHook(name: String, hook: (reason: String) -> Unit): AutoCloseable = instance.registerCancellationHook(name, hook)
        fun registerOkHttpClient(client: OkHttpClient): AutoCloseable = instance.registerOkHttpClient(client)
        fun registerWorkManagerCancellation(hook: (reason: String) -> Unit) = instance.registerWorkManagerCancellation(hook)
        fun getReason(): String? = instance.getReason()
        fun snapshot(): EmergencyStopSnapshot = instance.snapshot()
        fun getAuditHistory(): List<EmergencyStopAuditEntry> = instance.getAuditHistory()
    }
}
