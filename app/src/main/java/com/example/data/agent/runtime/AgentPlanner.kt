package com.example.data.agent.runtime

data class ExecutionPlan(
    val goal: String,
    val steps: List<PlannedStep>,
    val rawReasoning: String,
    val isValid: Boolean = true,
    val validationErrors: List<String> = emptyList()
)

/**
 * Task 5: Agent Planner.
 * Uses AgentModelProvider to produce machine-readable plans mapped strictly to available tools.
 */
class AgentPlanner(
    private val modelProvider: AgentModelProvider,
    private val toolRegistry: AgentToolRegistry,
    private val capabilityRegistry: WastiCapabilityRegistry
) {

    suspend fun createPlan(task: AgentTask): ExecutionPlan {
        val availableTools = toolRegistry.list().map { it.name }
        val activeCapabilities = capabilityRegistry.getSupportedCapabilities().filter { capabilityRegistry.isCapabilityEnabled(it) }

        // 1. Generate plan using model provider abstraction
        val response = modelProvider.generatePlan(task.prompt, activeCapabilities)

        // 2. Validate and map steps against tool registry
        val validatedSteps = mutableListOf<PlannedStep>()
        val errors = mutableListOf<String>()

        for (step in response.steps) {
            val tool = toolRegistry.get(step.toolName)
            if (tool == null) {
                errors.add("Step ${step.stepId} requested unavailable tool '${step.toolName}'")
                continue
            }

            // Verify tool capability
            val requiredCap = mapToolToCapability(step.toolName)
            if (requiredCap != null && !capabilityRegistry.isCapabilityEnabled(requiredCap)) {
                errors.add("Step ${step.stepId} tool '${step.toolName}' requires disabled capability '$requiredCap'")
                continue
            }

            validatedSteps.add(step)
        }

        val isValid = errors.isEmpty() && validatedSteps.isNotEmpty()

        return ExecutionPlan(
            goal = task.prompt,
            steps = validatedSteps,
            rawReasoning = response.rawReasoning,
            isValid = isValid,
            validationErrors = errors
        )
    }

    private fun mapToolToCapability(toolName: String): String? {
        return when (toolName) {
            "read_file", "write_file", "list_files", "file_exists", "create_directory", "patch_file" -> "FILES"
            "execute_code" -> "CODING"
            else -> null
        }
    }
}
