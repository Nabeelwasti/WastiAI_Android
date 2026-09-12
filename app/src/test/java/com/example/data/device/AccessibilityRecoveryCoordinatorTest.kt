package com.example.data.device

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityRecoveryCoordinatorTest {
    @Test
    fun disabledAccessibility_isDetectedWithoutClaimingAvailability() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertFalse(AccessibilityRecoveryCoordinator.isAccessibilityEnabled(context))
    }

    @Test
    fun recoveryPlan_containsMultipleIndependentRoutes() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val plan = AccessibilityRecoveryCoordinator.requestRecovery(
            context = context,
            objective = "tap the requested control",
            target = "Settings"
        )

        assertTrue(plan.routes.size >= 6)
        assertTrue(plan.routes.any { it.contains("AccessibilityService") })
        assertTrue(plan.routes.any { it.contains("Intent") })
        assertTrue(plan.routes.any { it.contains("Native") })
        assertTrue(plan.routes.any { it.contains("Remote") })
    }
}
