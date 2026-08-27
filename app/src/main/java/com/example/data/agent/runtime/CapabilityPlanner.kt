package com.example.data.agent.runtime

import com.example.data.di.WastiServiceLocator
import java.util.UUID

/**
 * Capability Planner for Wasti AI OS.
 * Breaks high-level user goals into executable capability execution graphs (DAGs),
 * mapping dependencies, required preconditions, fallback routes, and verification criteria.
 */
data class PlannedCapabilityNode(
    val nodeId: String = "node_${UUID.randomUUID().toString().take(8)}",
    val capabilityId: String,
    val actionName: String,
    val description: String,
    val inputParameters: Map<String, Any> = emptyMap(),
    val dependencies: List<String> = emptyList(), // nodeIds that must succeed first
    val fallbackCapabilityIds: List<String> = emptyList(),
    val requiredPreconditions: List<String> = emptyList(),
    val expectedEvidenceType: String = "EXECUTION_COMPLETION",
    val isOptional: Boolean = false
)

data class PlannedCapabilityGraph(
    val planId: String = "plan_${UUID.randomUUID().toString().take(8)}",
    val userGoal: String,
    val nodes: List<PlannedCapabilityNode>,
    val estimatedRisk: RiskLevel = RiskLevel.LOW,
    val requiresUserApproval: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

class CapabilityPlanner(
    private val realityRegistry: CapabilityRealityRegistry = CapabilityRealityRegistry()
) {

    /**
     * Deconstructs a natural language or structured user goal into a dependency-ordered PlannedCapabilityGraph.
     */
    fun createPlan(userGoal: String): PlannedCapabilityGraph {
        val lower = userGoal.lowercase().trim()
        val nodes = mutableListOf<PlannedCapabilityNode>()
        var requiresApproval = false

        when {
            lower.contains("find") && (lower.contains("fix") || lower.contains("edit") || lower.contains("error")) -> {
                // Multi-step file inspection -> diagnostics -> repair -> verification plan
                val searchNode = PlannedCapabilityNode(
                    capabilityId = "files",
                    actionName = "search_files",
                    description = "Search workspace for target files matching query",
                    inputParameters = mapOf("query" to (extractQueryKeyword(lower) ?: "")),
                    expectedEvidenceType = "FILE_LIST"
                )
                nodes.add(searchNode)

                val readNode = PlannedCapabilityNode(
                    capabilityId = "files",
                    actionName = "read_file",
                    description = "Read target file content for inspection",
                    dependencies = listOf(searchNode.nodeId),
                    expectedEvidenceType = "FILE_CONTENT"
                )
                nodes.add(readNode)

                val diagNode = PlannedCapabilityNode(
                    capabilityId = "debug_project",
                    actionName = "analyze_diagnostics",
                    description = "Analyze syntax and runtime diagnostics",
                    dependencies = listOf(readNode.nodeId),
                    expectedEvidenceType = "STRUCTURED_FINDINGS"
                )
                nodes.add(diagNode)

                val patchNode = PlannedCapabilityNode(
                    capabilityId = "files",
                    actionName = "write_file",
                    description = "Apply verified repair patch to file",
                    dependencies = listOf(diagNode.nodeId),
                    expectedEvidenceType = "FILE_WRITTEN"
                )
                nodes.add(patchNode)
            }

            lower.contains("build") && lower.contains("test") -> {
                val buildNode = PlannedCapabilityNode(
                    capabilityId = "build_project",
                    actionName = "build",
                    description = "Compile and build project artifacts",
                    expectedEvidenceType = "BUILD_ARTIFACT"
                )
                nodes.add(buildNode)

                val testNode = PlannedCapabilityNode(
                    capabilityId = "test_project",
                    actionName = "run_tests",
                    description = "Run automated test suite",
                    dependencies = listOf(buildNode.nodeId),
                    expectedEvidenceType = "TEST_REPORT"
                )
                nodes.add(testNode)
            }

            lower.contains("screen") || lower.contains("tap") || lower.contains("click") -> {
                val inspectNode = PlannedCapabilityNode(
                    capabilityId = "device_control",
                    actionName = "read_screen",
                    description = "Capture and analyze current screen UI hierarchy",
                    expectedEvidenceType = "SCREEN_HIERARCHY"
                )
                nodes.add(inspectNode)

                val actNode = PlannedCapabilityNode(
                    capabilityId = "device_control",
                    actionName = if (lower.contains("tap") || lower.contains("click")) "simulate_tap" else "read_screen",
                    description = "Execute targeted interactive screen action",
                    dependencies = listOf(inspectNode.nodeId),
                    expectedEvidenceType = "UI_STATE_CHANGE"
                )
                nodes.add(actNode)
            }

            else -> {
                // Single step or direct capability execution
                val primaryCap = if (lower.contains("memory")) "memory_search"
                else if (lower.contains("search") || lower.contains("google") || lower.contains("lookup")) "search_web"
                else if (lower.contains("wasm")) "wasm_sandbox"
                else if (lower.contains("system") || lower.contains("status")) "system_info"
                else "terminal"

                nodes.add(
                    PlannedCapabilityNode(
                        capabilityId = primaryCap,
                        actionName = "execute",
                        description = "Direct execution of requested capability '$primaryCap'",
                        inputParameters = mapOf("query" to userGoal)
                    )
                )
            }
        }

        val maxRisk = nodes.maxOfOrNull { node ->
            val reality = realityRegistry.get(node.capabilityId)
            if (node.actionName in listOf("delete_file", "write_file", "simulate_tap")) RiskLevel.HIGH else RiskLevel.LOW
        } ?: RiskLevel.LOW

        if (maxRisk == RiskLevel.HIGH || maxRisk == RiskLevel.CRITICAL) {
            requiresApproval = true
        }

        return PlannedCapabilityGraph(
            userGoal = userGoal,
            nodes = nodes,
            estimatedRisk = maxRisk,
            requiresUserApproval = requiresApproval
        )
    }

    private fun extractQueryKeyword(text: String): String? {
        val tokens = text.split(" ")
        val keywords = listOf("python", "kt", "kotlin", "json", "js", "ts", "md", "txt", "code", "file")
        return tokens.firstOrNull { t -> keywords.any { kw -> t.contains(kw) } }
    }
}
