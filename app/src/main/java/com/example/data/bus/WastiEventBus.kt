package com.example.data.bus

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

sealed class WastiEvent {
    data class MemoryUpdated(val memoryId: String, val action: String) : WastiEvent()
    data class ProviderHealthChanged(val providerId: String, val newStatus: String) : WastiEvent()
    data class VoiceSessionChanged(val isActive: Boolean, val providerName: String) : WastiEvent()
    data class DatabaseMigrationCompleted(val oldVersion: Int, val newVersion: Int) : WastiEvent()
    data class SyncCompleted(val serviceName: String, val success: Boolean) : WastiEvent()
    data class PluginInstalled(val pluginId: String, val pluginName: String) : WastiEvent()
    data class SystemAlert(val level: String, val message: String) : WastiEvent()
}

object WastiEventBus {
    private val _events = MutableSharedFlow<WastiEvent>(
        replay = 0,
        extraBufferCapacity = 64
    )
    val events: SharedFlow<WastiEvent> = _events.asSharedFlow()

    suspend fun emit(event: WastiEvent) {
        _events.emit(event)
    }

    fun tryEmit(event: WastiEvent): Boolean {
        return _events.tryEmit(event)
    }
}
