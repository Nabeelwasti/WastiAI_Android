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

    @Test
    fun testDependencyIntelligenceEngineAndCycleDetection() {
        // Transitive dependencies of omni_brain
        val transitiveDeps = DependencyIntelligenceEngine.getTransitiveDependencies("omni_brain")
        assertTrue("OmniBrain must have transitive dependencies", transitiveDeps.isNotEmpty())

        // Cycles detection: Architecture Knowledge Graph should be acyclic
        val cycles = DependencyIntelligenceEngine.detectDependencyCycles()
        assertTrue("Architecture Knowledge Graph should have zero cycles", cycles.isEmpty())

        // Blast radius of credential_vault
        val blast = DependencyIntelligenceEngine.assessBlastRadius("credential_vault")
        assertNotNull(blast)
        assertEquals("credential_vault", blast.targetNodeId)
        assertTrue(blast.totalTransitiveAffectedNodes >= 0)

        // Degradation simulation
        val sim = DependencyIntelligenceEngine.simulateDegradation(setOf("external_cloud_cortex"))
        assertNotNull(sim)
        assertTrue("Core reasoning must remain operational even if cloud cortex fails", sim.isCoreReasoningOperational)
        assertTrue(sim.operationalLayersRemaining.contains(ArchitectureLayer.BRAIN_REASONING_LAYER))
    }

    @Test
    fun testCapabilityRelationshipGraphFallbacksAndClusters() {
        val allCaps = CapabilityRelationshipGraph.getAllCapabilities()
        assertTrue(allCaps.isNotEmpty())

        // Check fallback for cloud whisper
        val fallbacks = CapabilityRelationshipGraph.findFallbackPath("cloud_whisper_stt")
        assertTrue("Should find offline vosk fallback for cloud whisper", fallbacks.contains("vosk_offline_stt"))

        // Check fallback for cloud ensemble
        val brainFallbacks = CapabilityRelationshipGraph.findFallbackPath("cloud_ensemble_inference")
        assertTrue("Should find edge SmolLM fallback for cloud ensemble", brainFallbacks.contains("edge_smollm_inference"))

        // Check synergistic clusters
        val cluster = CapabilityRelationshipGraph.getSynergisticCluster("wre_polyglot_sandbox")
        assertTrue(cluster.contains("wre_polyglot_sandbox"))
        assertTrue(cluster.contains("code_prompt_synthesizer"))
    }

    @Test
    fun testFileOwnershipEngineInspectsImpact() {
        val analysis = FileOwnershipEngine.inspectFileImpact("com.example.data.ai.engine.WastiOmniBrain")
        assertNotNull(analysis)
        assertEquals(ArchitectureLayer.BRAIN_REASONING_LAYER, analysis.ownerLayer)
        assertEquals("omni_brain", analysis.subsystemId)
        assertTrue(analysis.whyItExists.contains("Master cognitive engine"))
        assertTrue(analysis.whatItBreaksIfRemoved.isNotEmpty())
        assertEquals(FileOwnershipEngine.CriticalityTier.CRITICAL_SOVEREIGN, analysis.criticality)
    }

    @Test
    fun testArchitectureEvolutionEngineAuditsBoundaries() {
        val audit = ArchitectureEvolutionEngine.auditArchitectureBoundaries()
        assertNotNull(audit)
        assertTrue("Architecture must be compliant with layer boundary rules", audit.isArchitectureCompliant)
        assertTrue(audit.boundaryViolations.isEmpty())
        assertTrue("Sovereign offline integrity must be high (>60%)", audit.sovereignOfflineIntegrityPercent > 60.0f)
        assertTrue(audit.activeProposals.isNotEmpty())
    }

    @Test
    fun testObservatoryTracingAndMetricsEngines() {
        val trace = ObservatoryEngine.Tracing.startSpan("unit_test_op", "test_subsystem")
        assertNotNull(trace.spanId)
        assertNotNull(trace.traceId)
        trace.end(isSuccess = true, networkBytes = 1024L)

        val metrics = ObservatoryEngine.Metrics.currentMetrics
        assertTrue(metrics.totalOperationsRecorded > 0)
        assertTrue(metrics.totalNetworkBytes >= 1024L)
    }

    @Test
    fun testReliabilityHealthAndFailurePrediction() {
        ReliabilityEngine.recordFailureEvent("network_caller", "ConnectTimeoutException", "TIMEOUT")
        val status = ReliabilityEngine.evaluateReliability()
        assertNotNull(status)
        assertTrue(status.recentFailureEvents.any { it.subsystemId == "network_caller" })

        ReliabilityEngine.recordSuccessfulRecovery()
        val postRecovery = ReliabilityEngine.evaluateReliability()
        assertTrue(postRecovery.recoveredFailures > 0)

        val verdict = ReliabilityEngine.HealthScoreEngine.getVerdict()
        assertNotNull(verdict)
    }

    @Test
    fun testCostIntelligencePreExecutionCostEstimate() {
        val shortEstimate = CostIntelligenceEngine.estimatePreExecutionCost("summarize this note")
        assertEquals(CostIntelligenceEngine.CostOptimalRoute.LOCAL_SOVEREIGN_FREE, shortEstimate.recommendedRoute)
        assertTrue(shortEstimate.estimatedBatteryDrainMah > 0.0f)

        val heavyEstimate = CostIntelligenceEngine.estimatePreExecutionCost("a".repeat(3000) + " architecture redesign")
        assertEquals(CostIntelligenceEngine.CostOptimalRoute.CLOUD_DEEP_CORTEX, heavyEstimate.recommendedRoute)
    }
}
