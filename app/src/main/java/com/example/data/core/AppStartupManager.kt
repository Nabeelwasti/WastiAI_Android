package com.example.data.core

import com.example.data.bus.WastiEvent
import com.example.data.bus.WastiEventBus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

enum class StartupStage(val displayName: String, val isCritical: Boolean) {
    CREDENTIALS("Credentials & Security", true),
    AI_ENGINE("AI Engine & Providers", true),
    OPERATIONS_ENGINE("Operations & Telemetry", false),
    VOICE_ENGINE("Voice Synthesizer", false),
    MEMORY_ENGINE("Memory & Knowledge Graph", false),
    DATABASE("Room Database & Storage", true),
    SYNC_WORKER("Background Sync Worker", false),
    COMPLETE("Startup Complete", false)
}

data class StartupDiagnostic(
    val stageTimings: Map<StartupStage, Long> = emptyMap(),
    val warnings: List<String> = emptyList(),
    val totalStartupTimeMs: Long = 0L
)

sealed class AppStartupState {
    data class Initializing(
        val stage: StartupStage,
        val step: String,
        val progress: Float
    ) : AppStartupState()

    data class CoreReady(
        val diagnostic: StartupDiagnostic
    ) : AppStartupState()

    data class CoreReadyDegraded(
        val diagnostic: StartupDiagnostic,
        val degradedSubsystems: List<StartupStage>
    ) : AppStartupState()

    data class BodyPartUnavailable(
        val diagnostic: StartupDiagnostic,
        val unavailableParts: List<String>
    ) : AppStartupState()

    data class StartupBlocked(
        val stage: StartupStage,
        val reason: String
    ) : AppStartupState()

    data class Ready(
        val diagnostic: StartupDiagnostic
    ) : AppStartupState()

    data class FatalError(
        val stage: StartupStage,
        val message: String,
        val cause: Throwable? = null
    ) : AppStartupState()

    val isWorkspaceAccessible: Boolean
        get() = this is Ready || this is CoreReady || this is CoreReadyDegraded || this is BodyPartUnavailable
}

object AppStartupManager {
    private val _startupState = MutableStateFlow<AppStartupState>(
        AppStartupState.Initializing(
            stage = StartupStage.CREDENTIALS,
            step = "Booting Wasti AI OS Subsystems...",
            progress = 0.05f
        )
    )
    val startupState: StateFlow<AppStartupState> = _startupState.asStateFlow()

    private val stageTimings = ConcurrentHashMap<StartupStage, Long>()
    private val warningsList = mutableListOf<String>()
    private val degradedStages = mutableListOf<StartupStage>()
    private val failedCriticalStages = mutableListOf<StartupStage>()
    private var totalStartTime: Long = System.currentTimeMillis()

    fun startStartupTrace() {
        totalStartTime = System.currentTimeMillis()
        stageTimings.clear()
        warningsList.clear()
        degradedStages.clear()
        failedCriticalStages.clear()
        _startupState.value = AppStartupState.Initializing(
            stage = StartupStage.CREDENTIALS,
            step = "Starting Startup Sequence...",
            progress = 0.05f
        )
    }

    fun updateStageProgress(
        stage: StartupStage,
        step: String,
        progress: Float
    ) {
        val current = _startupState.value
        if (current is AppStartupState.FatalError || current is AppStartupState.StartupBlocked) return
        _startupState.value = AppStartupState.Initializing(
            stage = stage,
            step = step,
            progress = progress.coerceIn(0f, 1f)
        )
    }

    fun recordStageCompletion(stage: StartupStage, durationMs: Long) {
        stageTimings[stage] = durationMs
        WastiEventBus.tryEmit(WastiEvent.SystemAlert("INFO", "Startup Stage [${stage.name}] completed in ${durationMs}ms"))
    }

    fun recordWarning(stage: StartupStage, message: String) {
        val warningStr = "[${stage.displayName}] $message"
        warningsList.add(warningStr)
        if (!degradedStages.contains(stage)) {
            degradedStages.add(stage)
        }
        WastiEventBus.tryEmit(WastiEvent.SystemAlert("WARNING", warningStr))
    }

    fun setReady() {
        val totalMs = System.currentTimeMillis() - totalStartTime
        val diagnostic = StartupDiagnostic(
            stageTimings = HashMap(stageTimings),
            warnings = ArrayList(warningsList),
            totalStartupTimeMs = totalMs
        )

        if (failedCriticalStages.isNotEmpty()) {
            val criticalStage = failedCriticalStages.first()
            _startupState.value = AppStartupState.FatalError(
                stage = criticalStage,
                message = "Critical subsystem [${criticalStage.displayName}] failed to initialize properly."
            )
            WastiEventBus.tryEmit(WastiEvent.SystemAlert("ERROR", "Wasti AI OS Startup Blocked: Critical failure in ${criticalStage.name}"))
        } else if (degradedStages.isNotEmpty()) {
            _startupState.value = AppStartupState.CoreReadyDegraded(diagnostic, ArrayList(degradedStages))
            WastiEventBus.tryEmit(WastiEvent.SystemAlert("WARNING", "Wasti AI OS Core Ready (Degraded: ${degradedStages.joinToString { it.name }}) in ${totalMs}ms"))
        } else {
            _startupState.value = AppStartupState.Ready(diagnostic)
            WastiEventBus.tryEmit(WastiEvent.SystemAlert("INFO", "Wasti AI OS Ready in ${totalMs}ms (${warningsList.size} warnings)"))
        }
    }

    fun setCoreReadyDegraded(degraded: List<StartupStage>) {
        val totalMs = System.currentTimeMillis() - totalStartTime
        val diagnostic = StartupDiagnostic(
            stageTimings = HashMap(stageTimings),
            warnings = ArrayList(warningsList),
            totalStartupTimeMs = totalMs
        )
        _startupState.value = AppStartupState.CoreReadyDegraded(diagnostic, degraded)
    }

    fun setBodyPartUnavailable(unavailableParts: List<String>) {
        val totalMs = System.currentTimeMillis() - totalStartTime
        val diagnostic = StartupDiagnostic(
            stageTimings = HashMap(stageTimings),
            warnings = ArrayList(warningsList),
            totalStartupTimeMs = totalMs
        )
        _startupState.value = AppStartupState.BodyPartUnavailable(diagnostic, unavailableParts)
    }

    fun setStartupBlocked(stage: StartupStage, reason: String) {
        if (stage.isCritical && !failedCriticalStages.contains(stage)) {
            failedCriticalStages.add(stage)
        }
        _startupState.value = AppStartupState.StartupBlocked(stage, reason)
        WastiEventBus.tryEmit(WastiEvent.SystemAlert("ERROR", "Startup Blocked in [${stage.name}]: $reason"))
    }

    fun setFatalError(stage: StartupStage, message: String, cause: Throwable? = null) {
        if (stage.isCritical && !failedCriticalStages.contains(stage)) {
            failedCriticalStages.add(stage)
        }
        _startupState.value = AppStartupState.FatalError(stage, message, cause)
        WastiEventBus.tryEmit(WastiEvent.SystemAlert("ERROR", "Fatal Startup Error in [${stage.name}]: $message"))
    }

    fun getDiagnosticSummary(): StartupDiagnostic {
        val currentState = _startupState.value
        return when (currentState) {
            is AppStartupState.Ready -> currentState.diagnostic
            is AppStartupState.CoreReady -> currentState.diagnostic
            is AppStartupState.CoreReadyDegraded -> currentState.diagnostic
            is AppStartupState.BodyPartUnavailable -> currentState.diagnostic
            else -> {
                StartupDiagnostic(
                    stageTimings = HashMap(stageTimings),
                    warnings = ArrayList(warningsList),
                    totalStartupTimeMs = System.currentTimeMillis() - totalStartTime
                )
            }
        }
    }
}
