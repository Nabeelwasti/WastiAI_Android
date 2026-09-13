package com.example.data.architecture

/**
 * Dependency Intelligence Engine.
 *
 * Implements Phase 1 & Phase 6 Architecture Intelligence:
 * Provides deep transitive dependency analysis, circular dependency detection,
 * blast radius computation, and degradation propagation analysis.
 *
 * Answers for every subsystem:
 * - What depends on it?
 * - What does it break if degraded or modified?
 * - Is the dependency chain fully offline sovereign?
 */
object DependencyIntelligenceEngine {

    data class BlastRadiusAssessment(
        val targetNodeId: String,
        val targetNodeName: String,
        val directDependents: List<String>,
        val totalTransitiveAffectedNodes: Int,
        val affectedSubsystemIds: Set<String>,
        val affectedLayers: Set<ArchitectureLayer>,
        val breaksSovereignOffline: Boolean,
        val severity: Severity
    ) {
        enum class Severity {
            LOW,
            MEDIUM,
            HIGH,
            CRITICAL_SYSTEMIC
        }
    }

    data class DegradationImpactReport(
        val primaryDegradedNodes: Set<String>,
        val cascadingImpactedNodes: Set<String>,
        val operationalLayersRemaining: Set<ArchitectureLayer>,
        val compromisedLayers: Set<ArchitectureLayer>,
        val isCoreReasoningOperational: Boolean
    )

    /**
     * Resolves all direct and indirect dependencies of a given node.
     */
    fun getTransitiveDependencies(nodeId: String): Set<String> {
        val visited = mutableSetOf<String>()
        val queue = ArrayDeque<String>()
        queue.add(nodeId)

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val directDeps = ArchitectureKnowledgeGraph.getDependencies(current)
            for (dep in directDeps) {
                if (visited.add(dep.id)) {
                    queue.add(dep.id)
                }
            }
        }
        return visited
    }

    /**
     * Resolves all direct and indirect dependents (nodes that rely on this node).
     */
    fun getTransitiveDependents(nodeId: String): Set<String> {
        val visited = mutableSetOf<String>()
        val queue = ArrayDeque<String>()
        queue.add(nodeId)

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val directDependents = ArchitectureKnowledgeGraph.getDependents(current)
            for (dependent in directDependents) {
                if (visited.add(dependent.id)) {
                    queue.add(dependent.id)
                }
            }
        }
        return visited
    }

    /**
     * Detects any dependency cycles across the Architecture Knowledge Graph.
     * Returns a list of cycles if detected (empty list indicates a healthy DAG).
     */
    fun detectDependencyCycles(): List<List<String>> {
        val allNodes = ArchitectureKnowledgeGraph.getAllNodes()
        val cycles = mutableListOf<List<String>>()
        val visited = mutableSetOf<String>()
        val recursionStack = LinkedHashSet<String>()

        fun dfs(nodeId: String) {
            visited.add(nodeId)
            recursionStack.add(nodeId)

            val dependencies = ArchitectureKnowledgeGraph.getDependencies(nodeId)
            for (dep in dependencies) {
                if (dep.id in recursionStack) {
                    val cycleList = recursionStack.toList()
                    val cycleStartIndex = cycleList.indexOf(dep.id)
                    if (cycleStartIndex != -1) {
                        cycles.add(cycleList.subList(cycleStartIndex, cycleList.size) + dep.id)
                    }
                } else if (dep.id !in visited) {
                    dfs(dep.id)
                }
            }

            recursionStack.remove(nodeId)
        }

        for (node in allNodes) {
            if (node.id !in visited) {
                dfs(node.id)
            }
        }

        return cycles
    }

    /**
     * Computes the Blast Radius for a given node to determine what breaks if this node fails or changes.
     */
    fun assessBlastRadius(nodeId: String): BlastRadiusAssessment {
        val node = ArchitectureKnowledgeGraph.getNode(nodeId)
        val targetName = node?.name ?: nodeId
        val directDependents = ArchitectureKnowledgeGraph.getDependents(nodeId).map { it.id }
        val allAffected = getTransitiveDependents(nodeId)

        val affectedNodes = allAffected.mapNotNull { ArchitectureKnowledgeGraph.getNode(it) }
        val affectedLayers = affectedNodes.map { it.layer }.toSet()

        val breaksSovereign = affectedNodes.any { it.isSovereignOffline }

        val severity = when {
            allAffected.size >= 8 || ArchitectureLayer.BRAIN_REASONING_LAYER in affectedLayers ->
                BlastRadiusAssessment.Severity.CRITICAL_SYSTEMIC
            allAffected.size >= 4 ->
                BlastRadiusAssessment.Severity.HIGH
            allAffected.isNotEmpty() ->
                BlastRadiusAssessment.Severity.MEDIUM
            else ->
                BlastRadiusAssessment.Severity.LOW
        }

        return BlastRadiusAssessment(
            targetNodeId = nodeId,
            targetNodeName = targetName,
            directDependents = directDependents,
            totalTransitiveAffectedNodes = allAffected.size,
            affectedSubsystemIds = allAffected,
            affectedLayers = affectedLayers,
            breaksSovereignOffline = breaksSovereign,
            severity = severity
        )
    }

    /**
     * Simulates cascading failure propagation given a set of degraded or disabled nodes.
     */
    fun simulateDegradation(degradedNodeIds: Set<String>): DegradationImpactReport {
        val cascading = mutableSetOf<String>()
        for (degradedId in degradedNodeIds) {
            cascading.addAll(getTransitiveDependents(degradedId))
        }

        val allNodes = ArchitectureKnowledgeGraph.getAllNodes()
        val survivingNodes = allNodes.filter { it.id !in degradedNodeIds && it.id !in cascading }

        val operatingLayers = survivingNodes.map { it.layer }.toSet()
        val allPossibleLayers = ArchitectureLayer.entries.toSet()
        val compromisedLayers = allPossibleLayers - operatingLayers

        val isCoreReasoningOperational = survivingNodes.any {
            it.id == "omni_brain" || it.id == "unified_local_brain"
        }

        return DegradationImpactReport(
            primaryDegradedNodes = degradedNodeIds,
            cascadingImpactedNodes = cascading,
            operationalLayersRemaining = operatingLayers,
            compromisedLayers = compromisedLayers,
            isCoreReasoningOperational = isCoreReasoningOperational
        )
    }
}
