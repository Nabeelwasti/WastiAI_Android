package com.example.data.agent.runtime

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class WastiEmergencyStopController : EmergencyStopController {

    private val emergencyStopped = AtomicBoolean(false)
    private val stopReason = AtomicReference<String?>(null)

    override val isEmergencyStopped: Boolean
        get() = emergencyStopped.get()

    fun getReason(): String? = stopReason.get()

    override fun triggerEmergencyStop(reason: String) {
        stopReason.set(reason)
        emergencyStopped.set(true)
    }

    override fun resetEmergencyStop() {
        stopReason.set(null)
        emergencyStopped.set(false)
    }
}
