package com.example.data.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
@TestCategory(
    tier = TestTier.ROBOLECTRIC,
    description = "Unit tests verifying WastiSystemResilienceGovernor anti-burden, anti-hang, and memory shielding"
)
class WastiSystemResilienceGovernorTest {

    @Test
    fun testAssessSystemHealthReturnsValidTelemetry() {
        val telemetry = WastiSystemResilienceGovernor.assessSystemHealth()
        assertNotNull(telemetry)
        assertTrue(telemetry.maxMemoryMb >= 0)
        assertTrue(telemetry.totalMemoryMb >= 0)
        assertTrue(telemetry.freeMemoryMb >= 0)
        assertTrue(telemetry.memoryUsagePercent >= 0.0f)
        assertNotNull(telemetry.loadState)

        val flowTelemetry = WastiSystemResilienceGovernor.telemetryState.value
        assertEquals(telemetry.loadState, flowTelemetry.loadState)
    }

    @Test
    fun testProactivelyTrimMemoryDoesNotThrow() {
        WastiSystemResilienceGovernor.proactivelyTrimMemory()
        val telemetry = WastiSystemResilienceGovernor.assessSystemHealth()
        assertNotNull(telemetry)
    }

    @Test
    fun testWithCrashShieldReturnsSuccessOnNormalExecution() = runBlocking {
        val result = WastiSystemResilienceGovernor.withCrashShield(
            taskName = "UnitTestNormal",
            defaultFallback = "fallback_value"
        ) {
            "computation_success"
        }
        assertEquals("computation_success", result)
    }

    @Test
    fun testWithCrashShieldRecoversAndReturnsFallbackOnException() = runBlocking {
        val result = WastiSystemResilienceGovernor.withCrashShield(
            taskName = "UnitTestCrashingTask",
            defaultFallback = "safe_fallback"
        ) {
            throw IllegalStateException("Intentional test fault")
        }
        assertEquals("safe_fallback", result)
    }

    @Test
    fun testRunGuardedInferenceExecutesSuccessfully() = runBlocking {
        val result = WastiSystemResilienceGovernor.runGuardedInference(
            operationName = "GuardedInferenceTest",
            timeoutMs = 5000L
        ) {
            "inference_output_payload"
        }
        assertTrue(result.isSuccess)
        assertEquals("inference_output_payload", result.getOrNull())
    }

    @Test
    fun testRunGuardedInferenceCatchesTimeoutGracefully() = runBlocking {
        val result = WastiSystemResilienceGovernor.runGuardedInference(
            operationName = "HangingInferenceTest",
            timeoutMs = 50L
        ) {
            kotlinx.coroutines.delay(500L)
            "should_not_reach_here"
        }
        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()
        assertNotNull(exception)
        assertTrue(exception?.message?.contains("watchdog timeout") == true)
    }
}
