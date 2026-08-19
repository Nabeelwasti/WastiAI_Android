package com.example.data.agent.runtime

import java.util.concurrent.ConcurrentHashMap

enum class WastiCapability(val capabilityName: String) {
    CODING("CODING"),
    FILES("FILES"),
    TERMINAL("TERMINAL"),
    GITHUB("GITHUB"),
    WEB("WEB"),
    RESEARCH("RESEARCH"),
    ANDROID_CONTROL("ANDROID_CONTROL"),
    DESIGN("DESIGN"),
    MEDIA("MEDIA"),
    CLOUD("CLOUD"),
    AUTOMATION("AUTOMATION")
}

class WastiCapabilityRegistry : CapabilityRegistry {

    private val enabledCapabilities = ConcurrentHashMap<String, Boolean>()

    init {
        // Stage 2 defaults: FILES and CODING enabled, others disabled by default
        enabledCapabilities[WastiCapability.FILES.capabilityName] = true
        enabledCapabilities[WastiCapability.CODING.capabilityName] = true
    }

    override fun getSupportedCapabilities(): List<String> {
        return WastiCapability.entries.map { it.capabilityName }
    }

    override fun isCapabilityEnabled(capabilityName: String): Boolean {
        if (enabledCapabilities[capabilityName] == false) return false
        if (enabledCapabilities[capabilityName] == true) return true

        val reality = UnifiedExecutionFabric.instance.realityRegistry.getCapabilityReality(capabilityName)
        return reality.executionStatus == CapabilityExecutionStatus.OPERATIONAL &&
                (reality.realityState == CapabilityRealityState.NATIVE ||
                 reality.realityState == CapabilityRealityState.LIVE_CONNECTED)
    }

    fun setCapabilityEnabled(capabilityName: String, enabled: Boolean) {
        enabledCapabilities[capabilityName] = enabled
    }
}
