package com.example.assistant

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.util.Log

/**
 * Skeleton AccessibilityService. This is opt-in and requires the user to enable
 * the service in Accessibility settings. Use responsibly.
 */
class WastiAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // TODO: inspect events and implement automations (button clicks, navigation)
        Log.d("WastiAccessibility", "Event: ${'$'}{event?.eventType}")
    }

    override fun onInterrupt() {
        // TODO: handle interrupt (cleanup)
        Log.d("WastiAccessibility", "Interrupted")
    }
}
