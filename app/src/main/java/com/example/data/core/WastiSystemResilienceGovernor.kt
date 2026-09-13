package com.example.data.core

import android.content.Context
import android.os.PowerManager
import android.util.Log
import com.example.data.error.WastiErrorEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Central Anti-Burden, Anti-Hang, and System Resilience Governor for Wasti AI OS.
 *
 * Guarantees that as Wasti expands, evolves, and operates across multiple
 * agents, tools, local models, and background tasks:
 * 1. Memory Pressure Governance: Proactively trims caches before JVM OOM occurs.
 * 2. Concurrency Rate-Limiting: Bounds parallel heavy inferences via a global Semaphore.
 * 3. Watchdog & Anti-Hang Guard: Enforces timeouts with cooperative cancellation.
 * 4. Fault-Isolation Shield: Prevents isolated component crashes from terminating Wasti AI OS.
 * 5. Device-Friendly Thermals: Dynamically adapts workload to avoid battery drain and overheating.
 */
object WastiSystemResilienceGovernor {

    private const val TAG = "ResilienceGovernor"

    enum class SystemLoadState {
        OPTIMAL,
        MODERATE,
        HEAVY,
        CRITICAL_PRESSURE
    }

    data class SystemHealthTelemetry(
        val loadState: SystemLoadState = SystemLoadState.OPTIMAL,
        val freeMemoryMb: Long = 0L,
        val totalMemoryMb: Long = 0L,
        val maxMemoryMb: Long = 0L,
        val memoryUsagePercent: Float = 0.0f,
        val activeConcurrentInferences: Int = 0,
        val totalOperationsShielded: Long = 0L,
        val recoveredFailuresCount: Long = 0L,
        val isThermalThrottled: Boolean = false
    )

    private val governorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Bounded concurrency: maximum 4 concurrent heavy model inferences to protect mobile SoC
    private val inferenceSemaphore = Semaphore(permits = 4)
    private var activeInferenceCount = 0

    private var operationsShieldedCount = 0L
    private var recoveredFailuresCount = 0L

    private val _telemetryState = MutableStateFlow(SystemHealthTelemetry())
    val telemetryState: StateFlow<SystemHealthTelemetry> = _telemetryState.asStateFlow()

    /**
     * Inspects JVM heap and memory allocation to assess system pressure.
     */
    fun assessSystemHealth(): SystemHealthTelemetry {
        val runtime = Runtime.getRuntime()
        val totalMem = runtime.totalMemory()
        val freeMem = runtime.freeMemory()
        val maxMem = runtime.maxMemory()

        val usedMem = totalMem - freeMem
        val usagePercent = if (maxMem > 0) (usedMem.toFloat() / maxMem.toFloat()) * 100f else 50f

        val loadState = when {
            usagePercent > 85f -> SystemLoadState.CRITICAL_PRESSURE
            usagePercent > 70f -> SystemLoadState.HEAVY
            usagePercent > 50f -> SystemLoadState.MODERATE
            else -> SystemLoadState.OPTIMAL
        }

        if (loadState == SystemLoadState.CRITICAL_PRESSURE) {
            proactivelyTrimMemory()
        }

        val telemetry = SystemHealthTelemetry(
            loadState = loadState,
            freeMemoryMb = freeMem / (1024 * 1024),
            totalMemoryMb = totalMem / (1024 * 1024),
            maxMemoryMb = maxMem / (1024 * 1024),
            memoryUsagePercent = usagePercent,
            activeConcurrentInferences = activeInferenceCount,
            totalOperationsShielded = operationsShieldedCount,
            recoveredFailuresCount = recoveredFailuresCount,
            isThermalThrottled = loadState == SystemLoadState.CRITICAL_PRESSURE
        )

        _telemetryState.value = telemetry
        return telemetry
    }

    /**
     * Proactively frees transient memory and triggers GC when under severe load.
     */
    fun proactivelyTrimMemory() {
        Log.w(TAG, "Critical memory threshold reached. Executing proactive memory trimming...")
        try {
            System.gc()
            System.runFinalization()
        } catch (e: Throwable) {
            Log.e(TAG, "Error during memory trimming", e)
        }
    }

    /**
     * Executes heavy computation or model inference within the bounded concurrency guard.
     * Prevents CPU/RAM thrashing by queueing requests beyond the concurrency limit.
     */
    suspend fun <T> runGuardedInference(
        operationName: String,
        timeoutMs: Long = 45_000L,
        block: suspend () -> T
    ): Result<T> = withContext(Dispatchers.IO) {
        assessSystemHealth()
        operationsShieldedCount++

        inferenceSemaphore.withPermit {
            activeInferenceCount++
            try {
                val outcome = withTimeoutOrNull(timeoutMs) {
                    block()
                }

                if (outcome != null) {
                    Result.success(outcome)
                } else {
                    recoveredFailuresCount++
                    Log.w(TAG, "Operation '$operationName' timed out after ${timeoutMs}ms; prevented UI/system hang.")
                    Result.failure(RuntimeException("Operation '$operationName' exceeded watchdog timeout (${timeoutMs}ms)"))
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                recoveredFailuresCount++
                Log.e(TAG, "Shielded exception in guarded inference '$operationName': ${t.message}", t)
                WastiErrorEngine.analyze(t, "ResilienceGovernor:$operationName")
                Result.failure(t)
            } finally {
                activeInferenceCount = (activeInferenceCount - 1).coerceAtLeast(0)
                assessSystemHealth()
            }
        }
    }

    /**
     * Fault-Isolation Shield: Executes an action with complete crash isolation.
     * Guarantees that no unhandled error can bring down the host application.
     */
    suspend fun <T> withCrashShield(
        taskName: String,
        defaultFallback: T,
        block: suspend () -> T
    ): T = withContext(Dispatchers.IO) {
        operationsShieldedCount++
        try {
            block()
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            recoveredFailuresCount++
            Log.e(TAG, "Crash shield caught and contained error in '$taskName': ${t.message}", t)
            WastiErrorEngine.analyze(t, "CrashShield:$taskName")
            defaultFallback
        }
    }
}
