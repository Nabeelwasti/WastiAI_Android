package com.example.data.agent.runtime

import com.example.data.di.WastiServiceLocator
import java.util.UUID

/**
 * Structured semantic task interpretation models for Wasti AI OS.
 */
enum class TaskDomain {
    DEVELOPMENT,
    FILESYSTEM,
    RESEARCH_AND_WEB,
    DEVICE_AUTOMATION,
    MEMORY_AND_KNOWLEDGE,
    SYSTEM_AND_OPS,
    COMMUNICATION,
    SANDBOX_COMPUTE,
    GENERAL_REASONING
}

enum class TaskOperation {
    SEARCH,
    INSPECT,
    ANALYZE,
    CREATE,
    MODIFY,
    BUILD,
    TEST,
    DEPLOY,
    NAVIGATE,
    COMMUNICATE,
    SYNC,
    DIAGNOSE,
    REPAIR,
    VERIFY
}

data class TaskTarget(
    val entityType: String, // "file", "project", "web_topic", "ui_element", "contact", "system_metric"
    val identifier: String = "",
    val path: String? = null,
    val query: String? = null
)

data class TaskInput(
    val key: String,
    val value: Any,
    val isMandatory: Boolean = true
)

data class TaskExpectedOutput(
    val outputType: String, // "FILE_CONTENT", "BUILD_ARTIFACT", "TEST_REPORT", "SYNTHESIS", "UI_STATE_CHANGE"
    val description: String,
    val verificationCriteria: String = "NON_EMPTY"
)

data class TaskConstraint(
    val constraintType: String, // "TIMEOUT", "NO_PATH_TRAVERSAL", "REQUIRE_AUTH", "MAX_DEPTH"
    val value: String
)

data class TaskRequirement(
    val capabilityId: String,
    val isMandatory: Boolean = true,
    val minimumRealityState: CapabilityRealityState = CapabilityRealityState.LIVE_CONNECTED
)

data class SemanticTaskInterpretation(
    val rawGoal: String,
    val primaryIntent: String,
    val domain: TaskDomain,
    val operations: List<TaskOperation>,
    val targets: List<TaskTarget>,
    val inputs: List<TaskInput>,
    val expectedOutputs: List<TaskExpectedOutput>,
    val constraints: List<TaskConstraint> = emptyList(),
    val requiredCapabilities: List<TaskRequirement> = emptyList(),
    val estimatedRisk: RiskLevel = RiskLevel.LOW
)

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
    val semanticInterpretation: SemanticTaskInterpretation? = null,
    val createdAt: Long = System.currentTimeMillis()
)

class CapabilityPlanner(
    private val realityRegistry: CapabilityRealityRegistry? = null
) {
    private val registry: CapabilityRealityRegistry
        get() = realityRegistry ?: UnifiedExecutionFabric.instance.realityRegistry

    /**
     * Performs semantic task interpretation on natural language input.
     */
    fun interpretGoal(userGoal: String): SemanticTaskInterpretation {
        val lower = userGoal.lowercase().trim()
        val domain = when {
            lower.contains("b2b") || lower.contains("lead") || lower.contains("prospect") -> TaskDomain.RESEARCH_AND_WEB
            lower.contains("email") || lower.contains("linkedin") || lower.contains("draft") -> TaskDomain.COMMUNICATION
            lower.contains("find") && (lower.contains("fix") || lower.contains("edit") || lower.contains("error")) -> TaskDomain.DEVELOPMENT
            lower.contains("build") || lower.contains("compile") || lower.contains("test") || lower.contains("gradle") || lower.contains("package") -> TaskDomain.DEVELOPMENT
            lower.contains("search") || lower.contains("research") || lower.contains("google") || lower.contains("compare") || lower.contains("summarize") -> TaskDomain.RESEARCH_AND_WEB
            lower.contains("screen") || lower.contains("tap") || lower.contains("click") || lower.contains("whatsapp") || lower.contains("open app") -> TaskDomain.DEVICE_AUTOMATION
            lower.contains("file") || lower.contains("directory") || lower.contains("folder") || lower.contains("read") || lower.contains("write") -> TaskDomain.FILESYSTEM
            lower.contains("memory") || lower.contains("recall") || lower.contains("remember") || lower.contains("knowledge") -> TaskDomain.MEMORY_AND_KNOWLEDGE
            lower.contains("wasm") || lower.contains("sandbox") || lower.contains("compute") -> TaskDomain.SANDBOX_COMPUTE
            lower.contains("system") || lower.contains("status") || lower.contains("cpu") || lower.contains("battery") -> TaskDomain.SYSTEM_AND_OPS
            else -> TaskDomain.GENERAL_REASONING
        }

        val operations = mutableListOf<TaskOperation>()
        val targets = mutableListOf<TaskTarget>()
        val requiredCaps = mutableListOf<TaskRequirement>()

        when (domain) {
            TaskDomain.DEVELOPMENT -> {
                if (lower.contains("find") || lower.contains("search")) operations.add(TaskOperation.SEARCH)
                if (lower.contains("analyze") || lower.contains("diagnos") || lower.contains("error")) operations.add(TaskOperation.DIAGNOSE)
                if (lower.contains("fix") || lower.contains("repair") || lower.contains("edit")) operations.add(TaskOperation.REPAIR)
                if (lower.contains("build") || lower.contains("compile")) operations.add(TaskOperation.BUILD)
                if (lower.contains("test")) operations.add(TaskOperation.TEST)
                if (operations.isEmpty()) operations.add(TaskOperation.INSPECT)

                targets.add(TaskTarget(entityType = "project", query = extractExtensibleTarget(userGoal)))
                requiredCaps.add(TaskRequirement("files"))
                requiredCaps.add(TaskRequirement("build_project", isMandatory = false))
            }
            TaskDomain.COMMUNICATION -> {
                operations.add(TaskOperation.CREATE)
                operations.add(TaskOperation.COMMUNICATE)
                targets.add(TaskTarget(entityType = "draft", query = userGoal))
                requiredCaps.add(TaskRequirement("draft_persistence"))
            }
            TaskDomain.RESEARCH_AND_WEB -> {
                operations.add(TaskOperation.SEARCH)
                operations.add(TaskOperation.ANALYZE)
                if (lower.contains("compare") || lower.contains("contradict") || lower.contains("summarize")) {
                    operations.add(TaskOperation.VERIFY)
                }
                targets.add(TaskTarget(entityType = "web_topic", query = userGoal))
                requiredCaps.add(TaskRequirement("search_web"))
            }
            TaskDomain.DEVICE_AUTOMATION -> {
                operations.add(TaskOperation.INSPECT)
                if (lower.contains("tap") || lower.contains("click") || lower.contains("send")) {
                    operations.add(TaskOperation.NAVIGATE)
                }
                targets.add(TaskTarget(entityType = "ui_element", query = userGoal))
                requiredCaps.add(TaskRequirement("device_control"))
            }
            TaskDomain.MEMORY_AND_KNOWLEDGE -> {
                operations.add(TaskOperation.SEARCH)
                targets.add(TaskTarget(entityType = "memory_node", query = userGoal))
                requiredCaps.add(TaskRequirement("memory_search"))
            }
            TaskDomain.SANDBOX_COMPUTE -> {
                operations.add(TaskOperation.CREATE)
                targets.add(TaskTarget(entityType = "sandbox_module", query = userGoal))
                requiredCaps.add(TaskRequirement("wasm_sandbox"))
            }
            else -> {
                operations.add(TaskOperation.INSPECT)
                targets.add(TaskTarget(entityType = "general", query = userGoal))
                requiredCaps.add(TaskRequirement("terminal"))
            }
        }

        val estimatedRisk = if (operations.contains(TaskOperation.REPAIR) ||
            operations.contains(TaskOperation.MODIFY) ||
            operations.contains(TaskOperation.NAVIGATE) ||
            lower.contains("delete") || lower.contains("rm")
        ) RiskLevel.HIGH else RiskLevel.LOW

        return SemanticTaskInterpretation(
            rawGoal = userGoal,
            primaryIntent = "${domain.name}_${operations.firstOrNull()?.name ?: "EXECUTE"}",
            domain = domain,
            operations = operations,
            targets = targets,
            inputs = listOf(TaskInput("goal", userGoal)),
            expectedOutputs = listOf(TaskExpectedOutput("EXECUTION_REPORT", "Observable outcome of $domain workflow")),
            requiredCapabilities = requiredCaps,
            estimatedRisk = estimatedRisk
        )
    }

    /**
     * Creates an execution plan from a structured semantic task interpretation.
     */
    fun createExecutionPlan(interpretation: SemanticTaskInterpretation): PlannedCapabilityGraph =
        createPlan(interpretation.rawGoal)

    fun createExecutionPlan(userGoal: String): PlannedCapabilityGraph =
        createPlan(userGoal)

    /**
     * Deconstructs a natural language or structured user goal into a dependency-ordered PlannedCapabilityGraph.
     * Uses pure SemanticTaskInterpretation without keyword branching in graph creation.
     */
    fun createPlan(userGoal: String): PlannedCapabilityGraph {
        val interpretation = interpretGoal(userGoal)
        val nodes = mutableListOf<PlannedCapabilityNode>()
        var requiresApproval = false

        when (interpretation.domain) {
            TaskDomain.DEVELOPMENT -> {
                val hasBuild = interpretation.operations.contains(TaskOperation.BUILD)
                val hasTest = interpretation.operations.contains(TaskOperation.TEST)
                val hasRepair = interpretation.operations.contains(TaskOperation.REPAIR) || interpretation.operations.contains(TaskOperation.DIAGNOSE)

                if (hasRepair) {
                    val searchNode = PlannedCapabilityNode(
                        capabilityId = "files",
                        actionName = "search_files",
                        description = "Search workspace for target files matching query",
                        inputParameters = mapOf("query" to (extractQueryKeyword(interpretation.rawGoal) ?: "")),
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
                } else if (hasBuild && hasTest) {
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
                } else if (hasBuild) {
                    nodes.add(
                        PlannedCapabilityNode(
                            capabilityId = "build_project",
                            actionName = "build",
                            description = "Compile and build project artifacts",
                            expectedEvidenceType = "BUILD_ARTIFACT"
                        )
                    )
                } else if (hasTest) {
                    nodes.add(
                        PlannedCapabilityNode(
                            capabilityId = "test_project",
                            actionName = "run_tests",
                            description = "Run automated test suite",
                            expectedEvidenceType = "TEST_REPORT"
                        )
                    )
                } else {
                    nodes.add(
                        PlannedCapabilityNode(
                            capabilityId = "files",
                            actionName = "inspect_project",
                            description = "Inspect workspace structure and files",
                            inputParameters = mapOf("target" to (interpretation.targets.firstOrNull()?.query ?: userGoal)),
                            expectedEvidenceType = "PROJECT_STRUCTURE"
                        )
                    )
                }
            }

            TaskDomain.DEVICE_AUTOMATION -> {
                val inspectNode = PlannedCapabilityNode(
                    capabilityId = "device_control",
                    actionName = "read_screen",
                    description = "Capture and analyze current screen UI hierarchy",
                    expectedEvidenceType = "SCREEN_HIERARCHY"
                )
                nodes.add(inspectNode)

                val needsTap = interpretation.operations.contains(TaskOperation.NAVIGATE)
                val actNode = PlannedCapabilityNode(
                    capabilityId = "device_control",
                    actionName = if (needsTap) "simulate_tap" else "read_screen",
                    description = "Execute targeted interactive screen action",
                    dependencies = listOf(inspectNode.nodeId),
                    expectedEvidenceType = "UI_STATE_CHANGE"
                )
                nodes.add(actNode)
            }

            TaskDomain.RESEARCH_AND_WEB -> {
                val searchNode = PlannedCapabilityNode(
                    capabilityId = "search_web",
                    actionName = "deep_search",
                    description = "Execute multi-source deep query and extract evidence",
                    inputParameters = mapOf("query" to userGoal),
                    expectedEvidenceType = "SYNTHESIS_REPORT"
                )
                nodes.add(searchNode)
            }

            TaskDomain.MEMORY_AND_KNOWLEDGE -> {
                nodes.add(
                    PlannedCapabilityNode(
                        capabilityId = "memory_search",
                        actionName = "recall_memory",
                        description = "Retrieve semantic, episodic, or procedural knowledge",
                        inputParameters = mapOf("query" to userGoal),
                        expectedEvidenceType = "KNOWLEDGE_GRAPH"
                    )
                )
            }

            TaskDomain.SANDBOX_COMPUTE -> {
                nodes.add(
                    PlannedCapabilityNode(
                        capabilityId = "wasm_sandbox",
                        actionName = "execute_wasm",
                        description = "Execute isolated WebAssembly sandboxed computation",
                        inputParameters = mapOf("query" to userGoal),
                        expectedEvidenceType = "COMPUTE_OUTPUT"
                    )
                )
            }

            TaskDomain.FILESYSTEM -> {
                val actionName = when {
                    interpretation.operations.contains(TaskOperation.CREATE) || interpretation.operations.contains(TaskOperation.MODIFY) -> "write_file"
                    interpretation.operations.contains(TaskOperation.SEARCH) -> "search_files"
                    else -> "read_file"
                }
                nodes.add(
                    PlannedCapabilityNode(
                        capabilityId = "files",
                        actionName = actionName,
                        description = "Perform filesystem operation ($actionName)",
                        inputParameters = mapOf("path" to (interpretation.targets.firstOrNull()?.path ?: ""), "query" to userGoal),
                        expectedEvidenceType = "FILE_OPERATION_RESULT"
                    )
                )
            }

            TaskDomain.SYSTEM_AND_OPS -> {
                nodes.add(
                    PlannedCapabilityNode(
                        capabilityId = "system_info",
                        actionName = "query_telemetry",
                        description = "Query active system telemetry and environment health",
                        inputParameters = mapOf("query" to userGoal),
                        expectedEvidenceType = "SYSTEM_TELEMETRY"
                    )
                )
            }

            TaskDomain.COMMUNICATION -> {
                nodes.add(
                    PlannedCapabilityNode(
                        capabilityId = "draft_persistence",
                        actionName = "create_draft",
                        description = "Draft structured communication message",
                        inputParameters = mapOf("content" to userGoal),
                        expectedEvidenceType = "DRAFT_ENTITY"
                    )
                )
            }

            TaskDomain.GENERAL_REASONING -> {
                val primaryCap = interpretation.requiredCapabilities.firstOrNull()?.capabilityId ?: "terminal"
                val params = if (primaryCap == "terminal") {
                    mapOf("command" to if (userGoal.startsWith("echo ") || userGoal.startsWith("ls ") || userGoal.startsWith("pwd")) userGoal else "echo '$userGoal'")
                } else {
                    mapOf("query" to userGoal)
                }

                nodes.add(
                    PlannedCapabilityNode(
                        capabilityId = primaryCap,
                        actionName = "execute",
                        description = "Direct execution of requested capability '$primaryCap'",
                        inputParameters = params,
                        expectedEvidenceType = "EXECUTION_REPORT"
                    )
                )
            }
        }

        val maxRisk = nodes.maxOfOrNull { node ->
            if (node.actionName in listOf("delete_file", "write_file", "simulate_tap")) RiskLevel.HIGH else RiskLevel.LOW
        } ?: interpretation.estimatedRisk

        if (maxRisk == RiskLevel.HIGH || maxRisk == RiskLevel.CRITICAL) {
            requiresApproval = true
        }

        return PlannedCapabilityGraph(
            userGoal = userGoal,
            nodes = nodes,
            estimatedRisk = maxRisk,
            requiresUserApproval = requiresApproval,
            semanticInterpretation = interpretation
        )
    }

    private fun extractQueryKeyword(text: String): String? {
        val tokens = text.split(" ")
        val keywords = listOf("python", "kt", "kotlin", "json", "js", "ts", "md", "txt", "code", "file")
        return tokens.firstOrNull { t -> keywords.any { kw -> t.contains(kw) } }
    }

    private fun extractExtensibleTarget(goal: String): String {
        val tokens = goal.split(" ")
        return tokens.drop(1).joinToString(" ").take(40).ifBlank { goal }
    }
}
