package com.example.data.agent.runtime

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Task 3: Agent Event Bus.
 * Thread-safe coroutine SharedFlow event channel for runtime observability.
 * Does NOT expose mutable internal state.
 */
class AgentEventBus(replay: Int = 50, extraBufferCapacity: Int = 200) {

    private val _events = MutableSharedFlow<AgentEvent>(
        replay = replay,
        extraBufferCapacity = extraBufferCapacity
    )

    val events: SharedFlow<AgentEvent> = _events.asSharedFlow()

    suspend fun emit(event: AgentEvent) {
        _events.emit(event)
    }

    fun tryEmit(event: AgentEvent): Boolean {
        return _events.tryEmit(event)
    }

    companion object {
        @Volatile
        private var instance: AgentEventBus? = null

        fun getInstance(): AgentEventBus {
            return instance ?: synchronized(this) {
                instance ?: AgentEventBus().also { instance = it }
            }
        }
    }
}
