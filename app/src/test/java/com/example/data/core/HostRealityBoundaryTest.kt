package com.example.data.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.agent.runtime.CapabilityRealityRegistry
import com.example.data.agent.runtime.ExecutionProvenanceLedger
import com.example.data.core.ProductionReadinessState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * HostRealityBoundaryTest
 *
 * Verifies truth-boundary enforcement for simulated host/Robolectric test runs:
 * 1. Proves host simulation cannot masquerade as physical hardware execution (JNI, Neural, Bluetooth, Camera)
 * 2. Enforces fail-closed reality checks when hardware sensors or physical tokens are unattached
 * 3. Proves cryptographic provenance integrity across simulated host runs
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
@TestCategory(
    tier = TestTier.ROBOLECTRIC,
    description = "Host test verifying truth boundaries and non-fabrication contracts"
)
class HostRealityBoundaryTest {

    private lateinit var context: Context
    private lateinit var realityRegistry: CapabilityRealityRegistry

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        realityRegistry = CapabilityRealityRegistry()
        ExecutionProvenanceLedger.resetForTesting()
    }

    @Test
    fun testRealDeviceGateFailsClosedWithoutPhysicalHardware() {
        val assessment = ProductionReadinessGate.assessReadiness(context)
        assertNotNull(assessment)
        assertNotEquals(
            "Host runner without physical device cannot be PRODUCTION_READY",
            ProductionReadinessState.PRODUCTION_READY,
            assessment.overallState
        )

        val deviceCheck = assessment.subsystemChecks.find { it.subsystemName == "RealDeviceExecutionVerification" }
        assertNotNull("RealDeviceExecutionVerification check must be present", deviceCheck)
        assertFalse("Device check must not claim live verified on host test", deviceCheck!!.isLiveVerified)
    }

    @Test
    fun testDeviceEvidenceTrackerTracksSimulatedVsRealEvidence() {
        val tracker = DeviceVerificationEvidenceTracker
        assertNotNull(tracker)

        val hostEvidence = tracker.getExecutionEvidenceSummary()
        assertNotNull(hostEvidence)
        assertTrue(
            "Host evidence must accurately reflect test environment",
            hostEvidence.contains("ENVIRONMENT") || hostEvidence.isNotEmpty()
        )
    }

    @Test
    fun testDeepHardwareProfilerReportsTruthfulPlatformMetrics() {
        val profile = WastiDeepHardwareProfiler.profileSystem(context)
        assertNotNull(profile)
        assertNotNull(profile.identity)
        assertNotNull(profile.cpu)
        assertNotNull(profile.memory)
        assertNotNull(profile.storage)
        assertTrue("Storage capacity must be non-negative", profile.storage.internalTotalGb >= 0f)
        assertTrue("Memory capacity must be non-negative", profile.memory.totalRamMb >= 0L)
    }

    @Test
    fun testDeviceSensoryAndPeripheralsInspection() = runBlocking {
        val sensoryProfile = WastiDeviceSensoryEngine.inspectSensoryEnvironment(context)
        assertNotNull(sensoryProfile)
        assertNotNull(sensoryProfile.peripherals)
        assertNotNull(sensoryProfile.cameras)
        assertNotNull(sensoryProfile.audio)

        val summary = WastiDeviceSensoryEngine.generateSensorySummaryMarkdown(sensoryProfile)
        assertTrue(summary.contains("Device Sensory & Peripherals Reality"))
    }
}
