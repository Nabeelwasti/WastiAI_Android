package com.example.data.architecture

import com.example.data.ai.council.AICouncilEngine
import com.example.data.architecture.civilization.CapabilityCivilizationRegistry
import com.example.data.architecture.civilization.CapabilityLifecycleState
import com.example.data.architecture.civilization.CivilizedCapability
import com.example.data.architecture.evolution.CapabilityEvolutionPipeline
import com.example.data.architecture.evolution.EvolutionStage
import com.example.data.architecture.evolution.ResearchIntelligenceEngine
import com.example.data.architecture.observatory.CostIntelligenceEngine
import com.example.data.architecture.observatory.ObservatoryEngine
import com.example.data.architecture.observatory.ReliabilityEngine
import com.example.data.core.TestCategory
import com.example.data.core.TestTier
import com.example.data.core.WastiExperienceMode
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
    description = "Unit tests verifying Architecture Intelligence, Capability Civilization, and Enterprise Observatory Layers"
)
class ArchitectureAndCivilizationTest {

    @Test
    fun testArchitectureKnowledgeGraphTopologyAndImpactRadius() {
        val allNodes = ArchitectureKnowledgeGraph.getAllNodes()
        assertTrue("ArchitectureKnowledgeGraph must contain registered subsystems", allNodes.isNotEmpty())

        val omniNode = ArchitectureKnowledgeGraph.getNode("omni_brain")
        assertNotNull(omniNode)
        assertEquals(ArchitectureLayer.BRAIN_REASONING_LAYER, omniNode?.layer)

        val impactRadius = ArchitectureKnowledgeGraph.calculateImpactRadius("omni_brain")
        assertNotNull(impactRadius)

        val userNodes = ArchitectureKnowledgeGraph.getNodesByLayer(ArchitectureLayer.USER_INTERACTION_LAYER)
        assertTrue(userNodes.any { it.id == "floating_bubble" })
    }

    @Test
    fun testCodebaseIntelligenceEngineIntrospectsArchitecture() = runBlocking {
        val report = CodebaseIntelligenceEngine.introspectArchitecture()
        assertNotNull(report)
        assertTrue(report.totalSubsystems > 0)
        assertTrue(report.layerBreakdown.isNotEmpty())
        assertTrue(report.sovereignOfflineNodeCount > 0)

        val resolved = CodebaseIntelligenceEngine.resolveHandlingSubsystem("read the screen and click button")
        assertEquals("device_controller", resolved?.id)

        val wakeResolved = CodebaseIntelligenceEngine.resolveHandlingSubsystem("hey wasti voice activation")
        assertEquals("vosk_wake_word", wakeResolved?.id)
    }

    @Test
    fun testRuntimeTopologyMapOptimalExecutionTarget() {
        val topology = RuntimeTopologyMap.topologyState.value
        assertNotNull(topology.primaryNode)
        assertTrue(topology.primaryNode.isOnline)

        val compileTarget = RuntimeTopologyMap.getOptimalExecutionTarget("heavy_compile")
        assertNotNull(compileTarget)

        val researchTarget = RuntimeTopologyMap.getOptimalExecutionTarget("deep_research")
        assertNotNull(researchTarget)
    }

    @Test
    fun testCapabilityCivilizationRegistryLifecycleAndMetrics() {
        val cap = CivilizedCapability(
            id = "test_custom_cap",
            name = "Test Custom Capability",
            ownerLayer = "CAPABILITY_CIVILIZATION_LAYER",
            isSovereignOffline = true
        )
        CapabilityCivilizationRegistry.register(cap)

        val retrieved = CapabilityCivilizationRegistry.get("test_custom_cap")
        assertNotNull(retrieved)
        assertEquals(1.0f, retrieved?.healthScore)

        CapabilityCivilizationRegistry.recordExecution("test_custom_cap", isSuccess = true, latencyMs = 120L)
        val updated = CapabilityCivilizationRegistry.get("test_custom_cap")
        assertEquals(1L, updated?.metrics?.totalExecutions)
        assertEquals(1L, updated?.metrics?.successfulExecutions)
        assertEquals(CapabilityLifecycleState.STABLE, updated?.lifecycleState)
    }

    @Test
    fun testCapabilityEvolutionPipelineRejectsMockEvidence() = runBlocking {
        CapabilityEvolutionPipeline.recordObservedGap("gap_test_01", "CustomTool", "Missing specialized math parser")

        val result = CapabilityEvolutionPipeline.advancePipeline(
            gapId = "gap_test_01",
            prototypeCode = "fun parse() = 42",
            testEvidence = "mock_evidence of tool execution",
            verificationEvidence = "verified"
        )
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("Honest Failure") == true)
    }

    @Test
    fun testObservatoryEngineRecordsTelemetryAndComputesP95() {
        val span = ObservatoryEngine.TelemetrySpan(
            spanId = "span_01",
            operationName = "test_op",
            subsystem = "test_sub",
            latencyMs = 45L,
            isSuccess = true
        )
        ObservatoryEngine.recordSpan(span)

        val metrics = ObservatoryEngine.metricsState.value
        assertTrue(metrics.totalOperationsRecorded > 0)
        assertTrue(metrics.averageLatencyMs >= 0L)
    }

    @Test
    fun testReliabilityEngineStatusEvaluation() {
        val status = ReliabilityEngine.evaluateReliability()
        assertNotNull(status)
        assertTrue(status.overallHealthScorePercent in 0.0f..100.0f)
        assertNotNull(status.verdict)
    }

    @Test
    fun testCostIntelligenceEngineTracksSavings() {
        CostIntelligenceEngine.recordLocalInferenceFreeOfCharge()
        val ledger = CostIntelligenceEngine.costLedgerState.value
        assertTrue(ledger.localFreeInferencesExecuted > 0)
        assertTrue(ledger.totalMoneySavedUsd >= 0.0)

        val shouldLocal = CostIntelligenceEngine.shouldPreferLocalSovereignty("write a hello world function in kotlin")
        assertTrue(shouldLocal)
    }

    @Test
    fun testWastiExperienceModeSwitching() {
        WastiExperienceMode.setMode(WastiExperienceMode.ARCHITECT_MODE)
        assertEquals(WastiExperienceMode.ARCHITECT_MODE, WastiExperienceMode.currentMode.value)
        assertTrue(WastiExperienceMode.ARCHITECT_MODE.showArchitectureGraph)

        WastiExperienceMode.setMode(WastiExperienceMode.SIMPLE_MODE)
        assertFalse(WastiExperienceMode.SIMPLE_MODE.showArchitectureGraph)
    }

    @Test
    fun testResearchIntelligenceEngineGeneratesBriefing() = runBlocking {
        val briefing = ResearchIntelligenceEngine.generateDailyArchitectureBriefing()
        assertNotNull(briefing)
        assertTrue(briefing.topics.isNotEmpty())
        assertTrue(briefing.coreStatus.isNotBlank())
    }

    @Test
    fun testAICouncilEngineDeliberation() = runBlocking {
        val deliberation = AICouncilEngine.deliberate("Assess current system memory allocation and plan optimization")
        assertNotNull(deliberation)
        assertTrue(deliberation.finalVerdict.isNotBlank())
        assertTrue(deliberation.participatingMembers.isNotEmpty())
        assertEquals("VERIFY -> REMEMBER -> IMPROVE", deliberation.identityOrbitStage)
    }
}
