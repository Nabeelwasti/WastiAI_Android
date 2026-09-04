package com.example.data.agent.runtime

data class CurriculumTask(
    val taskId: String,
    val title: String,
    val prerequisiteTaskIds: List<String>,
    val complexityTier: Int,
    val isMastered: Boolean = false
)

object CurriculumPlanner {

    private val syllabus = mutableListOf(
        CurriculumTask("step_01", "Initialize Wasti Core & Sandboxed Workspace", emptyList(), 1, true),
        CurriculumTask("step_02", "Execute Local Open-Source Reasoning", listOf("step_01"), 1, true),
        CurriculumTask("step_03", "Discover System APIs and Verify Evidence", listOf("step_01", "step_02"), 2, true),
        CurriculumTask("step_04", "Autonomous Capability Generation & Promotion", listOf("step_03"), 3, true),
        CurriculumTask("step_05", "Distributed Mesh & Multi-Agent Collaboration", listOf("step_04"), 4, true)
    )

    fun getNextCurriculumTask(): CurriculumTask? {
        return syllabus.find { !it.isMastered }
    }

    fun getAllTasks(): List<CurriculumTask> = syllabus.toList()
}

object StateActionMapper {

    fun resolveNextOptimalAction(
        currentState: String,
        targetGoal: String
    ): String {
        return when {
            currentState.contains("MISSING_CAPABILITY") -> "TRIGGER_TOOL_GENERATOR"
            currentState.contains("EXECUTION_FAILED") -> "INVOKE_RECOVERY_PLANNER"
            currentState.contains("UNVERIFIED") -> "DISPATCH_INDEPENDENT_PROBE"
            else -> "EXECUTE_VIA_UNIFIED_FABRIC"
        }
    }
}
