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
        // Canonical operational defaults for Wasti AI OS
        enabledCapabilities[WastiCapability.FILES.capabilityName] = true
        enabledCapabilities[WastiCapability.CODING.capabilityName] = true
        enabledCapabilities[WastiCapability.TERMINAL.capabilityName] = true
        enabledCapabilities[WastiCapability.AUTOMATION.capabilityName] = true
        enabledCapabilities[WastiCapability.ANDROID_CONTROL.capabilityName] = true
        enabledCapabilities[WastiCapability.RESEARCH.capabilityName] = true
        enabledCapabilities[WastiCapability.WEB.capabilityName] = true
        enabledCapabilities[WastiCapability.CLOUD.capabilityName] = true
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
                 reality.realityState == CapabilityRealityState.LIVE_CONNECTED ||
                 reality.realityState == CapabilityRealityState.IMPLEMENTED_NOT_LIVE_VERIFIED)
    }

    fun setCapabilityEnabled(capabilityName: String, enabled: Boolean) {
        enabledCapabilities[capabilityName] = enabled
    }

    /**
     * [P0-36] Structured representation of capability permission truth.
     * Segregates configured default enablement from actual OS runtime authorization and user consent.
     */
    data class CapabilityPermissionTruth(
        val capabilityName: String,
        val isConfiguredEnabled: Boolean,
        val isOsPermissionGranted: Boolean,
        val isUserConsentGranted: Boolean,
        val canExecuteLive: Boolean,
        val notes: String
    )

    /**
     * Evaluates whether a capability is not merely configured in software,
     * but has live OS permissions and user consent required for execution.
     */
    fun verifyCapabilityPermissionTruth(
        capabilityName: String,
        context: Any? = null
    ): CapabilityPermissionTruth {
        val isConfigured = isCapabilityEnabled(capabilityName)
        if (!isConfigured) {
            return CapabilityPermissionTruth(
                capabilityName = capabilityName,
                isConfiguredEnabled = false,
                isOsPermissionGranted = false,
                isUserConsentGranted = false,
                canExecuteLive = false,
                notes = "DISABLED_IN_REGISTRY: Capability disabled in configuration"
            )
        }

        // For non-device capabilities (FILES, CODING, TERMINAL, RESEARCH, WEB, CLOUD),
        // internal runtime execution is permitted within sandbox boundary.
        if (capabilityName != WastiCapability.ANDROID_CONTROL.capabilityName &&
            capabilityName != "device_control"
        ) {
            return CapabilityPermissionTruth(
                capabilityName = capabilityName,
                isConfiguredEnabled = true,
                isOsPermissionGranted = true,
                isUserConsentGranted = true,
                canExecuteLive = true,
                notes = "INTERNAL_SANDBOX_OPERATIONAL"
            )
        }

        // For ANDROID_CONTROL / device_control:
        // Declared or configured != Granted by OS.
        val appContext = context as? android.content.Context ?: com.example.WastiApplication.instance
        if (appContext == null) {
            return CapabilityPermissionTruth(
                capabilityName = capabilityName,
                isConfiguredEnabled = true,
                isOsPermissionGranted = false,
                isUserConsentGranted = com.example.assistant.PermissionManager.hasUserConsent(capabilityName),
                canExecuteLive = false,
                notes = "BLOCKED_HOST_OR_NULL_CONTEXT: Android OS Context required to verify runtime permissions"
            )
        }

        val overlayGranted = com.example.assistant.PermissionManager.canDrawOverlays(appContext)
        val accessibilityActive = com.example.service.WastiAccessibilityService.isServiceActive
        val userConsented = com.example.assistant.PermissionManager.hasUserConsent(appContext, capabilityName)
        val osGranted = overlayGranted || accessibilityActive

        val canExecute = osGranted && userConsented

        val notes = when {
            !osGranted -> "DECLARED_OR_CONFIGURED_ONLY: Overlay permission or Accessibility Service not active on device"
            !userConsented -> "BLOCKED_BY_POLICY: User consent policy not granted for device control"
            else -> "LIVE_AUTHORIZED: Android OS permissions and user consent verified"
        }

        return CapabilityPermissionTruth(
            capabilityName = capabilityName,
            isConfiguredEnabled = true,
            isOsPermissionGranted = osGranted,
            isUserConsentGranted = userConsented,
            canExecuteLive = canExecute,
            notes = notes
        )
    }
}

