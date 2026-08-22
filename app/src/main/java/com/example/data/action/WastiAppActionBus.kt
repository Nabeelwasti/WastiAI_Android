package com.example.data.action

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Stage 10: Canonical Application Action Model.
 * Represents semantic OS-level commands triggered by WastiBrain or any interface.
 */
sealed class WastiAppAction {
    data class NavigateTo(
        val destinationId: String,
        val arguments: Map<String, String> = emptyMap()
    ) : WastiAppAction()

    data class OpenProject(val projectId: String) : WastiAppAction()
    data class SearchMemory(val query: String) : WastiAppAction()
    data class StartLocalServer(val port: Int = 8080) : WastiAppAction()
    data class StopLocalServer(val reason: String = "User requested") : WastiAppAction()
    data class ExecuteTerminalCommand(val command: String) : WastiAppAction()
    data class RunProjectTests(val projectId: String) : WastiAppAction()
    data class TriggerVoiceModal(val immediateStart: Boolean = true) : WastiAppAction()
    data class StartBackgroundWorkflow(val request: String) : WastiAppAction()
    data class SwitchTheme(val darkTheme: Boolean) : WastiAppAction()
    data class OpenDevAssistant(val contextQuery: String? = null) : WastiAppAction()
}

/**
 * Stage 10: Global Application Action Bus.
 * Connects the autonomous WastiBrain with UI screens, background services, and external clients.
 */
object WastiAppActionBus {
    private val _actions = MutableSharedFlow<WastiAppAction>(
        replay = 1,
        extraBufferCapacity = 64
    )
    val actions: SharedFlow<WastiAppAction> = _actions.asSharedFlow()

    suspend fun dispatch(action: WastiAppAction) {
        _actions.emit(action)
    }

    fun tryDispatch(action: WastiAppAction): Boolean {
        return _actions.tryEmit(action)
    }
}
