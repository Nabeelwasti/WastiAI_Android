package com.example.data.architecture

import com.example.data.agent.runtime.WastiCapabilityRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Codebase Intelligence Engine.
 *
 * Enables Wasti to fully understand its own codebase, architecture,
 * capabilities, workflows, APIs, and subsystems introspectively.
 *
 * Solves the complexity curve: Wasti manages its own architecture
 * rather than relying solely on manual maintenance.
 */
object CodebaseIntelligenceEngine {

    data class SubsystemSummary(
        val name: String,
        val layer: ArchitectureLayer,
        val responsibility: String,
        val dependencyCount: Int,
        val dependentCount: Int,
        val impactRadiusCount: Int,
        val isSovereignOffline: Boolean
    )

    data class ArchitectureSelfInspectionReport(
        val totalSubsystems: Int,
        val layerBreakdown: Map<ArchitectureLayer, Int>,
        val sovereignOfflineNodeCount: Int,
        val cloudDependentNodeCount: Int,
        val highestImpactNodes: List<Pair<String, Int>>,
        val subsystems: List<SubsystemSummary>,
        val timestamp: Long = System.currentTimeMillis()
    )

    /**
     * Conducts a full introspective analysis of the Wasti AI OS architecture.
     */
    suspend fun introspectArchitecture(): ArchitectureSelfInspectionReport = withContext(Dispatchers.Default) {
        val allNodes = ArchitectureKnowledgeGraph.getAllNodes()
        val layerCounts = allNodes.groupBy { it.layer }.mapValues { it.value.size }

        val subsystemSummaries = allNodes.map { node ->
            val deps = ArchitectureKnowledgeGraph.getDependencies(node.id)
            val dependents = ArchitectureKnowledgeGraph.getDependents(node.id)
            val impact = ArchitectureKnowledgeGraph.calculateImpactRadius(node.id)

            SubsystemSummary(
                name = node.name,
                layer = node.layer,
                responsibility = node.responsibility,
                dependencyCount = deps.size,
                dependentCount = dependents.size,
                impactRadiusCount = impact.size,
                isSovereignOffline = node.isSovereignOffline
            )
        }

        val sortedByImpact = allNodes.map { node ->
            node.id to ArchitectureKnowledgeGraph.calculateImpactRadius(node.id).size
        }.sortedByDescending { it.second }

        ArchitectureSelfInspectionReport(
            totalSubsystems = allNodes.size,
            layerBreakdown = layerCounts,
            sovereignOfflineNodeCount = allNodes.count { it.isSovereignOffline },
            cloudDependentNodeCount = allNodes.count { !it.isSovereignOffline },
            highestImpactNodes = sortedByImpact.take(5),
            subsystems = subsystemSummaries
        )
    }

    /**
     * Analyzes an intent or query and pinpoints which subsystem and layer is best suited to handle it.
     */
    fun resolveHandlingSubsystem(query: String): ArchitectureNode? {
        val lower = query.lowercase().trim()
        val nodes = ArchitectureKnowledgeGraph.getAllNodes()

        return when {
            lower.contains("screen") || lower.contains("tap") || lower.contains("click") ->
                nodes.firstOrNull { it.id == "device_controller" }
            lower.contains("wake") || lower.contains("voice") || lower.contains("listen") ->
                nodes.firstOrNull { it.id == "vosk_wake_word" }
            lower.contains("code") || lower.contains("python") || lower.contains("bash") || lower.contains("compile") ->
                nodes.firstOrNull { it.id == "wre_runtime" }
            lower.contains("memory") || lower.contains("remember") || lower.contains("recall") ->
                nodes.firstOrNull { it.id == "memory_manager" }
            lower.contains("swarm") || lower.contains("mesh") || lower.contains("offload") ->
                nodes.firstOrNull { it.id == "mesh_swarm" }
            lower.contains("verify") || lower.contains("audit") || lower.contains("truth") ->
                nodes.firstOrNull { it.id == "verification_engine" }
            else ->
                nodes.firstOrNull { it.id == "omni_brain" }
        }
    }

    /**
     * Retrieves all registered capabilities from the central capability registry.
     */
    fun getRegisteredCapabilities(): List<String> {
        return WastiCapabilityRegistry.getSupportedCapabilities()
    }
}
