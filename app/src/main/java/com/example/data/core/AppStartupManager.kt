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

    data class Ready(
        val diagnostic: StartupDiagnostic
    ) : AppStartupState()

    data class FatalError(
        val stage: StartupStage,
        val message: String,
        val cause: Throwable? = null
    ) : AppStartupState()
}

object AppStartupManager {
    @Volatile
    private var isReadyPermanent = true

    private val _startupState = MutableStateFlow<AppStartupState>(
        AppStartupState.Ready(StartupDiagnostic())
    )
    val startupState: StateFlow<AppStartupState> = _startupState.asStateFlow()

    private val stageTimings = ConcurrentHashMap<StartupStage, Long>()
    private val warningsList = mutableListOf<String>()
    private var totalStartTime: Long = System.currentTimeMillis()

    fun startStartupTrace() {
        if (isReadyPermanent) return
        totalStartTime = System.currentTimeMillis()
        stageTimings.clear()
        warningsList.clear()
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
        if (isReadyPermanent || _startupState.value is AppStartupState.Ready) return
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
        WastiEventBus.tryEmit(WastiEvent.SystemAlert("WARNING", warningStr))
    }

    fun setReady() {
        isReadyPermanent = true
        val totalMs = System.currentTimeMillis() - totalStartTime
        val diagnostic = StartupDiagnostic(
            stageTimings = HashMap(stageTimings),
            warnings = ArrayList(warningsList),
            totalStartupTimeMs = totalMs
        )
        _startupState.value = AppStartupState.Ready(diagnostic)
        WastiEventBus.tryEmit(WastiEvent.SystemAlert("INFO", "Wasti AI OS Ready in ${totalMs}ms (${warningsList.size} warnings)"))
    }

    fun setFatalError(stage: StartupStage, message: String, cause: Throwable? = null) {
        if (isReadyPermanent) return
        _startupState.value = AppStartupState.FatalError(stage, message, cause)
        WastiEventBus.tryEmit(WastiEvent.SystemAlert("ERROR", "Fatal Startup Error in [${stage.name}]: $message"))
    }

    fun getDiagnosticSummary(): StartupDiagnostic {
        val currentState = _startupState.value
        return if (currentState is AppStartupState.Ready) {
            currentState.diagnostic
        } else {
            StartupDiagnostic(
                stageTimings = HashMap(stageTimings),
                warnings = ArrayList(warningsList),
                totalStartupTimeMs = System.currentTimeMillis() - totalStartTime
            )
        }
    }
}
