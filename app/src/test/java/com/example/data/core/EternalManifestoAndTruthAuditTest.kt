package com.example.data.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.agent.runtime.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class EternalManifestoAndTruthAuditTest {

    @Test
    fun testTerminalTruthStateAlgebra() {
        // COMPLETED_VERIFIED
        assertTrue(TerminalTruthState.COMPLETED_VERIFIED.isVerified)
        assertTrue(TerminalTruthState.COMPLETED_VERIFIED.isExecutionSuccess)
        assertFalse(TerminalTruthState.COMPLETED_VERIFIED.isTerminalFailure)
        assertTrue(TerminalTruthState.COMPLETED_VERIFIED.isTerminal)

        // COMPLETED_UNVERIFIED
        assertFalse(TerminalTruthState.COMPLETED_UNVERIFIED.isVerified)
        assertTrue(TerminalTruthState.COMPLETED_UNVERIFIED.isExecutionSuccess)
        assertFalse(TerminalTruthState.COMPLETED_UNVERIFIED.isTerminalFailure)
        assertTrue(TerminalTruthState.COMPLETED_UNVERIFIED.isTerminal)

        // EXECUTION_FAILED
        assertFalse(TerminalTruthState.EXECUTION_FAILED.isVerified)
        assertFalse(TerminalTruthState.EXECUTION_FAILED.isExecutionSuccess)
        assertTrue(TerminalTruthState.EXECUTION_FAILED.isTerminalFailure)

        // VERIFICATION_FAILED
        assertFalse(TerminalTruthState.VERIFICATION_FAILED.isVerified)
        assertFalse(TerminalTruthState.VERIFICATION_FAILED.isExecutionSuccess)
        assertTrue(TerminalTruthState.VERIFICATION_FAILED.isTerminalFailure)

        // BLOCKED
        assertFalse(TerminalTruthState.BLOCKED.isVerified)
        assertFalse(TerminalTruthState.BLOCKED.isExecutionSuccess)
        assertTrue(TerminalTruthState.BLOCKED.isTerminalFailure)

        // CANCELLED & ROLLED_BACK
        assertTrue(TerminalTruthState.CANCELLED.isCancelled)
        assertTrue(TerminalTruthState.ROLLED_BACK.isRolledBack)
    }

    @Test
    fun testEvidenceBundleAndExecutionFactProvenance() {
        val bundle = EvidenceBundle(
            taskId = "task_001",
            actionId = "action_001",
            capabilityId = "files",
            provider = "WorkspaceManager",
            artifactPath = "/tmp/test.txt",
            exitCode = 0,
            confidence = 1.0,
            verifier = "WastiVerificationEngine"
        )

        val fact = ExecutionFact(
            taskId = "task_001",
            actionId = "action_001",
            command = "write_file",
            capabilityId = "files",
            executor = "WorkspaceManager",
            provider = "WorkspaceManager",
            executionStatus = UnifiedExecutionStatus.COMPLETED,
            observationStatus = ObservationStatus.OBSERVED,
            observationEvidence = "File verified on disk",
            verificationStatus = UnifiedVerificationStatus.VERIFIED,
            verificationEvidence = "Checksum matches expected value",
            terminalTruthState = TerminalTruthState.COMPLETED_VERIFIED,
            evidenceBundle = bundle
        )

        assertTrue(fact.isVerifiedSuccess)
        assertFalse(fact.isTerminalFailure)
        assertEquals("files", fact.evidenceBundle?.capabilityId)
        assertEquals(0, fact.evidenceBundle?.exitCode)
        assertEquals(1.0, fact.evidenceBundle?.confidence ?: 0.0, 0.001)
    }

    @Test
    fun testCapabilityRealityRegistryTruthfulness() {
        val registry = CapabilityRealityRegistry()
        
        // Truth check: core capabilities default to IMPLEMENTED_NOT_LIVE_VERIFIED until live tested
        val fileReality = registry.getCapabilityReality("FILES")
        assertEquals(CapabilityRealityState.IMPLEMENTED_NOT_LIVE_VERIFIED, fileReality.realityState)

        val terminalReality = registry.getCapabilityReality("TERMINAL")
        assertEquals(CapabilityRealityState.IMPLEMENTED_NOT_LIVE_VERIFIED, terminalReality.realityState)

        // Truthful registry: not all capabilities are LIVE_CONNECTED / NATIVE
        val report = registry.getSystemRealityReport()
        val unverifiedCount = report.count { it.realityState == CapabilityRealityState.IMPLEMENTED_NOT_LIVE_VERIFIED }
        assertTrue("Registry must report unverified capabilities truthfully", unverifiedCount > 0)
    }

    @Test
    fun testExecutionStrategyResolverForCoreProviders() {
        val registry = CapabilityRealityRegistry()
        val resolver = ExecutionStrategyResolver(registry)
        
        // Core local providers should resolve to NATIVE strategy even when status is IMPLEMENTED_NOT_LIVE_VERIFIED
        val fileDecision = resolver.resolveStrategy("FILES")
        assertEquals(ExecutionStrategy.NATIVE, fileDecision.strategy)

        val terminalDecision = resolver.resolveStrategy("TERMINAL")
        assertEquals(ExecutionStrategy.NATIVE, terminalDecision.strategy)

        // Missing capabilities must route according to resolution logic
        val missingDecision = resolver.resolveStrategy("UNKNOWN_QUANTUM_CAPABILITY")
        assertNotEquals(ExecutionStrategy.NATIVE, missingDecision.strategy)
    }

    @Test
    fun testProductionReadinessGateZeroFabrication() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val assessment = ProductionReadinessGate.assessReadiness(context)
        assertNotNull(assessment)
        // With default unconfigured credentials or offline integrations, system must not falsely claim PRODUCTION_READY
        assertNotEquals(ProductionReadinessState.PRODUCTION_READY, assessment.overallState)
    }

    @Test
    fun testObservationEngineZeroFabrication() = runBlocking {
        val engine = WastiObservationEngine()

        // 1. App launch without confirmed active window package must NOT be OBSERVED
        val unconfirmedLaunchResult = engine.observe(
            ObservationRequest(
                taskId = "task_test",
                actionId = "action_test",
                capabilityId = "device_control",
                parameters = mapOf("action" to "open_app", "target" to "com.test.nonexistent")
            ),
            UnifiedExecutionResult(
                taskId = "task_test",
                actionId = "action_test",
                capabilityId = "device_control",
                status = UnifiedExecutionStatus.COMPLETED,
                output = "Activity intent dispatched",
                executor = "AccessibilityService",
                startedAt = System.currentTimeMillis(),
                completedAt = System.currentTimeMillis(),
                verificationStatus = UnifiedVerificationStatus.UNVERIFIED
            )
        )
        // With accessibility inactive or target mismatch, status must be UNAVAILABLE or NOT_OBSERVED, never falsely OBSERVED
        assertTrue(
            "Unconfirmed app launch must be UNAVAILABLE or NOT_OBSERVED",
            unconfirmedLaunchResult.status == ObservationStatus.UNAVAILABLE ||
                unconfirmedLaunchResult.status == ObservationStatus.NOT_OBSERVED
        )

        // 2. Generic execution without verification evidence must not be claimed as OBSERVED
        val unverifiedExecution = engine.observe(
            ObservationRequest(
                taskId = "task_test_2",
                actionId = "action_test_2",
                capabilityId = "generic_capability",
                parameters = emptyMap()
            ),
            UnifiedExecutionResult(
                taskId = "task_test_2",
                actionId = "action_test_2",
                capabilityId = "generic_capability",
                status = UnifiedExecutionStatus.COMPLETED,
                output = "Completed task without probe",
                executor = "MockExecutor",
                startedAt = System.currentTimeMillis(),
                completedAt = System.currentTimeMillis(),
                verificationStatus = UnifiedVerificationStatus.UNVERIFIED
            )
        )
        assertEquals(ObservationStatus.UNAVAILABLE, unverifiedExecution.status)
        assertEquals(0.0, unverifiedExecution.confidence, 0.001)
    }
}
