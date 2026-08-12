package com.example.data.agent.runtime

data class TaskExecutionRecord(
    val taskId: TaskId,
    val userGoal: String,
    val finalState: AgenticState,
    val plan: ExecutionPlan?,
    val observations: List<AgentObservation>,
    val toolInvocations: List<ToolInvocationRecord>,
    val errorDiagnostics: List<ErrorDiagnostic>,
    val correctionProposals: List<CorrectionProposal>,
    val finalResultSummary: String?,
    val startTimeMs: Long,
    val endTimeMs: Long
)

data class ToolInvocationRecord(
    val toolName: String,
    val arguments: Map<String, Any?>,
    val isSuccess: Boolean,
    val resultSummary: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Task 11: Agent Memory Contract.
 * Contract interface allowing the runtime to record task execution history, plans,
 * observations, tool calls, and decisions without touching MemoryManager.kt.
 */
interface AgentMemoryContract {
    suspend fun recordTaskStart(taskId: TaskId, goal: String)
    suspend fun recordPlan(taskId: TaskId, plan: ExecutionPlan)
    suspend fun recordToolCall(taskId: TaskId, toolName: String, args: Map<String, Any?>, result: ToolResult)
    suspend fun recordObservation(taskId: TaskId, observation: AgentObservation)
    suspend fun recordDiagnostic(taskId: TaskId, diagnostic: ErrorDiagnostic)
    suspend fun recordCorrection(taskId: TaskId, proposal: CorrectionProposal)
    suspend fun recordTaskCompletion(taskId: TaskId, record: TaskExecutionRecord)
    suspend fun getTaskRecord(taskId: TaskId): TaskExecutionRecord?
    suspend fun getAllTaskRecords(): List<TaskExecutionRecord>
}

/**
 * In-memory implementation of AgentMemoryContract for runtime logging and test verification.
 */
class InMemoryAgentMemoryStore : AgentMemoryContract {

    private val records = java.util.concurrent.ConcurrentHashMap<String, TaskExecutionRecord>()
    private val pendingGoals = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val pendingPlans = java.util.concurrent.ConcurrentHashMap<String, ExecutionPlan>()
    private val pendingObservations = java.util.concurrent.ConcurrentHashMap<String, MutableList<AgentObservation>>()
    private val pendingToolCalls = java.util.concurrent.ConcurrentHashMap<String, MutableList<ToolInvocationRecord>>()
    private val pendingDiagnostics = java.util.concurrent.ConcurrentHashMap<String, MutableList<ErrorDiagnostic>>()
    private val pendingCorrections = java.util.concurrent.ConcurrentHashMap<String, MutableList<CorrectionProposal>>()
    private val startTimes = java.util.concurrent.ConcurrentHashMap<String, Long>()

    override suspend fun recordTaskStart(taskId: TaskId, goal: String) {
        pendingGoals[taskId.value] = goal
        startTimes[taskId.value] = System.currentTimeMillis()
        pendingObservations[taskId.value] = mutableListOf()
        pendingToolCalls[taskId.value] = mutableListOf()
        pendingDiagnostics[taskId.value] = mutableListOf()
        pendingCorrections[taskId.value] = mutableListOf()
    }

    override suspend fun recordPlan(taskId: TaskId, plan: ExecutionPlan) {
        pendingPlans[taskId.value] = plan
    }

    override suspend fun recordToolCall(taskId: TaskId, toolName: String, args: Map<String, Any?>, result: ToolResult) {
        val list = pendingToolCalls.getOrPut(taskId.value) { mutableListOf() }
        list.add(
            ToolInvocationRecord(
                toolName = toolName,
                arguments = args,
                isSuccess = result.isSuccess,
                resultSummary = result.error ?: result.output.toString()
            )
        )
    }

    override suspend fun recordObservation(taskId: TaskId, observation: AgentObservation) {
        val list = pendingObservations.getOrPut(taskId.value) { mutableListOf() }
        list.add(observation)
    }

    override suspend fun recordDiagnostic(taskId: TaskId, diagnostic: ErrorDiagnostic) {
        val list = pendingDiagnostics.getOrPut(taskId.value) { mutableListOf() }
        list.add(diagnostic)
    }

    override suspend fun recordCorrection(taskId: TaskId, proposal: CorrectionProposal) {
        val list = pendingCorrections.getOrPut(taskId.value) { mutableListOf() }
        list.add(proposal)
    }

    override suspend fun recordTaskCompletion(taskId: TaskId, record: TaskExecutionRecord) {
        records[taskId.value] = record
    }

    override suspend fun getTaskRecord(taskId: TaskId): TaskExecutionRecord? {
        return records[taskId.value]
    }

    override suspend fun getAllTaskRecords(): List<TaskExecutionRecord> {
        return records.values.toList()
    }
}
