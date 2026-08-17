package com.example.data.agent.runtime

import android.content.Context
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Stage 8: the canonical multi-agent coordination layer.
 *
 * This class implements "one brain, many capabilities": it routes work to
 * specialised profiles, but every real capability invocation goes through the
 * single [UnifiedExecutionFabric] supplied by the application. It deliberately
 * does not create a second tool registry or execution path.
 */
data class SubAgentDefinition(
    val id: String,
    val name: String,
    val role: AgentRole,
    val description: String,
    val capabilities: List<String>,
    val systemPrompt: String,
    val temperature: Float = 0.3f
)

data class AgentDelegation(
    val delegationId: String = UUID.randomUUID().toString(),
    val parentTaskId: String?,
    val subTaskId: String,
    val sourceAgentId: String,
    val targetAgentId: String,
    val targetRole: AgentRole,
    val reason: String,
    val depth: Int = 1,
    val sharedContextKeys: List<String> = emptyList(),
    val delegatedAt: Long = System.currentTimeMillis()
)

data class AgentResult(
    val taskId: String,
    val agentId: String,
    val role: AgentRole,
    val state: AgentTaskState,
    val output: String,
    val structuredData: Map<String, Any?> = emptyMap(),
    val evidence: String? = null,
    val executionTimeMs: Long = 0L,
    val error: String? = null
)

/**
 * Thread-safe shared state. A delegation receives only the keys explicitly
 * listed in [AgentDelegation.sharedContextKeys].
 */
class SharedAgentContext(private val maxLogEntries: Int = 1_000) {
    private val data = ConcurrentHashMap<String, Any>()
    private val logs = CopyOnWriteArrayList<String>()
    private val logLock = Any()

    init {
        require(maxLogEntries > 0) { "maxLogEntries must be positive" }
    }

    fun set(key: String, value: Any?) {
        require(key.isNotBlank()) { "Context keys must not be blank" }
        if (value == null) data.remove(key) else data[key] = value
    }

    fun get(key: String): Any? = data[key]

    @Suppress("UNCHECKED_CAST")
    fun <T> getTyped(key: String): T? = data[key] as? T

    fun getAll(): Map<String, Any> = data.toMap()

    fun snapshot(keys: Collection<String>): Map<String, Any> =
        keys.asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .mapNotNull { key -> data[key]?.let { value -> key to value } }
            .toMap()

    fun appendLog(message: String) {
        synchronized(logLock) {
            logs.add("[${System.currentTimeMillis()}] $message")
            while (logs.size > maxLogEntries) logs.removeAt(0)
        }
    }

    fun getLogs(): List<String> = logs.toList()

    fun clear() {
        data.clear()
        synchronized(logLock) { logs.clear() }
    }
}

/**
 * Default routing profiles. Capability names are routing metadata only;
 * [UnifiedExecutionFabric] remains the sole capability authority.
 */
object WastiSubAgentCatalog {
    val defaultSubAgents: List<SubAgentDefinition> = listOf(
        SubAgentDefinition(
            id = "wasti_executive_agent",
            name = "Executive Orchestrator",
            role = AgentRole.EXECUTIVE,
            description = "Goal decomposition, priority alignment, and multi-domain synthesis.",
            capabilities = listOf("Executive Strategy", "Domain Orchestration", "Goal Decomposition"),
            systemPrompt = "You are Wasti's Executive Orchestrator. Direct objectives and synthesize verified subtask outputs.",
            temperature = 0.2f
        ),
        SubAgentDefinition(
            id = "wasti_research_agent",
            name = "Research Specialist",
            role = AgentRole.RESEARCH,
            description = "Source-backed research, factual verification, and knowledge synthesis.",
            capabilities = listOf("search_web", "read_web_page", "fetch_url", "Fact Verification"),
            systemPrompt = "You are Wasti's Research Specialist. Return factual findings with evidence and uncertainty.",
            temperature = 0.2f
        ),
        SubAgentDefinition(
            id = "wasti_coding_agent",
            name = "Coding Architect",
            role = AgentRole.CODING,
            description = "Code changes, refactoring, integration, and architecture evolution.",
            capabilities = listOf("create_file", "edit_file", "execute_code", "Kotlin", "Architecture"),
            systemPrompt = "You are Wasti's Coding Architect. Produce small, maintainable, verified changes within workspace boundaries.",
            temperature = 0.1f
        ),
        SubAgentDefinition(
            id = "wasti_testing_agent",
            name = "Testing & Verification Specialist",
            role = AgentRole.TESTING,
            description = "Test discovery, execution, edge-case analysis, and verification.",
            capabilities = listOf("run_tests", "test_project", "Coverage Analysis", "Assertion Verification"),
            systemPrompt = "You are Wasti's Testing Specialist. Verify observable behaviour and report evidence, failures, and gaps.",
            temperature = 0.1f
        ),
        SubAgentDefinition(
            id = "wasti_debugging_agent",
            name = "Diagnostics & Debugging Specialist",
            role = AgentRole.DEBUGGING,
            description = "Failure diagnosis, regression isolation, and root-cause analysis.",
            capabilities = listOf("debug_project", "inspect_logs", "Stack Trace Analysis", "Root Cause Analysis"),
            systemPrompt = "You are Wasti's Debugging Specialist. Identify the smallest evidenced root cause before proposing a fix.",
            temperature = 0.1f
        ),
        SubAgentDefinition(
            id = "wasti_security_agent",
            name = "Security & Policy Auditor",
            role = AgentRole.SECURITY,
            description = "Boundary enforcement, secret protection, and policy auditing.",
            capabilities = listOf("wasti_sandbox", "Security Audit", "Credential Redaction", "Boundary Enforcement"),
            systemPrompt = "You are Wasti's Security Auditor. Enforce policy and protect credentials, data, and workspace boundaries.",
            temperature = 0.0f
        ),
        SubAgentDefinition(
            id = "wasti_design_agent",
            name = "UI/UX & Design Specialist",
            role = AgentRole.DESIGN,
            description = "Accessible Material Design 3 interfaces and visual-system refinement.",
            capabilities = listOf("UI/UX Design", "Material Design 3", "Accessibility", "Visual Hierarchy"),
            systemPrompt = "You are Wasti's Design Specialist. Design clear, accessible, consistent interfaces.",
            temperature = 0.4f
        ),
        SubAgentDefinition(
            id = "wasti_data_agent",
            name = "Data & Storage Specialist",
            role = AgentRole.DATA,
            description = "Schema design, storage, migrations, and structured-data analysis.",
            capabilities = listOf("SQL", "database", "Data Modeling", "Schema Migration"),
            systemPrompt = "You are Wasti's Data Specialist. Preserve data integrity and make migrations reversible where possible.",
            temperature = 0.2f
        ),
        SubAgentDefinition(
            id = "wasti_browser_agent",
            name = "Browser & Network Specialist",
            role = AgentRole.BROWSER_INTERNET,
            description = "Web inspection, network verification, and structured web extraction.",
            capabilities = listOf("read_web_page", "search_web", "Network Inspection"),
            systemPrompt = "You are Wasti's Browser Specialist. Inspect web resources and return structured, attributable results.",
            temperature = 0.2f
        )
    )
}

/**
 * Coordinates a dependency-safe task graph and delegates execution exclusively
 * to [fabric]. Public state changes are serialized so a task is claimed by at
 * most one caller at a time.
 */
class AgentTaskCoordinator(
    private val fabric: UnifiedExecutionFabric,
    subAgents: List<SubAgentDefinition> = WastiSubAgentCatalog.defaultSubAgents,
    val maxDelegationDepth: Int = 5
) {
    private val coordinationLock = Any()
    private val tasks = ConcurrentHashMap<String, AgentTask>()
    private val results = ConcurrentHashMap<String, AgentResult>()
    private val delegations = CopyOnWriteArrayList<AgentDelegation>()
    private val agentsById = ConcurrentHashMap<String, SubAgentDefinition>()

    val sharedContext = SharedAgentContext()

    init {
        require(maxDelegationDepth > 0) { "maxDelegationDepth must be positive" }
        subAgents.forEach(::registerSubAgent)
    }

    /**
     * Adds or replaces a routing profile. This does not grant any capability:
     * the execution fabric and its policy remain the only authority.
     */
    fun registerSubAgent(agent: SubAgentDefinition) {
        require(agent.id.isNotBlank()) { "Sub-agent id must not be blank" }
        require(agent.name.isNotBlank()) { "Sub-agent name must not be blank" }
        require(agent.temperature in 0.0f..2.0f) { "Temperature must be between 0.0 and 2.0" }
        agentsById[agent.id] = agent.copy(
            capabilities = agent.capabilities.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        )
    }

    fun getSubAgents(): List<SubAgentDefinition> = agentsById.values.sortedBy { it.id }

    fun selectAgentForRole(role: AgentRole): SubAgentDefinition? =
        agentsById.values.filter { it.role == role }.minByOrNull { it.id }

    /** Returns null when no profile actually matches the requested capability. */
    fun selectAgentForCapabilities(capabilities: List<String>): SubAgentDefinition? {
        val requested = capabilities.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (requested.isEmpty()) return null

        return agentsById.values
            .map { agent ->
                agent to requested.count { requestedCapability ->
                    agent.capabilities.any { it.equals(requestedCapability, ignoreCase = true) }
                }
            }
            .filter { (_, score) -> score > 0 }
            .sortedWith(compareByDescending<Pair<SubAgentDefinition, Int>> { it.second }.thenBy { it.first.id })
            .firstOrNull()
            ?.first
    }

    /**
     * Tests whether attaching [dependencies] to [taskId] would create a cycle.
     * The returned path begins and ends with [taskId].
     */
    fun detectCycle(taskId: String, dependencies: List<String>): List<String>? = synchronized(coordinationLock) {
        detectCycleLocked(taskId, dependencies)
    }

    fun createTask(
        title: String,
        description: String,
        assignedRole: AgentRole? = null,
        priority: AgentTaskPriority = AgentTaskPriority.MEDIUM,
        dependencies: List<String> = emptyList(),
        inputData: Map<String, Any?> = emptyMap(),
        requiredCapabilities: List<String> = emptyList()
    ): AgentTask = synchronized(coordinationLock) {
        val selectedAgent = assignedRole?.let(::selectAgentForRole)
            ?: selectAgentForCapabilities(requiredCapabilities)
        val task = AgentTask(
            taskId = TaskId(),
            prompt = description,
            title = title,
            description = description,
            assignedRole = assignedRole ?: selectedAgent?.role,
            assignedAgentId = selectedAgent?.id,
            priority = priority,
            state = if (dependencies.isEmpty()) AgentTaskState.SCHEDULED else AgentTaskState.PENDING,
            dependencies = dependencies.distinct(),
            inputData = inputData,
            requiredCapabilities = requiredCapabilities.distinct()
        )

        val validationError = validateNewTaskLocked(task, assignedRole, selectedAgent)
        if (validationError != null) return@synchronized rejectTaskCreationLocked(task, validationError)

        val failedDependency = firstFailedDependencyLocked(task)
        if (failedDependency != null) {
            return@synchronized blockNewTaskLocked(task, "Dependency '$failedDependency' is not successful")
        }

        tasks[task.taskId.value] = task
        sharedContext.appendLog("Created task '${task.title}' [${task.taskId.value}] as ${task.state}")
        task
    }

    /**
     * Delegates one task to one target profile. A task can have only one parent
     * delegation, avoiding ambiguous lineages and duplicate execution claims.
     */
    fun delegateTask(
        parentTaskId: String?,
        subTask: AgentTask,
        sourceAgentId: String,
        targetRole: AgentRole,
        reason: String,
        sharedContextKeys: List<String> = emptyList()
    ): AgentDelegation = synchronized(coordinationLock) {
        val targetAgent = selectAgentForRole(targetRole)
        val targetAgentId = targetAgent?.id ?: "unknown"
        val currentDepth = getDelegationChainLocked(parentTaskId).size + 1
        val normalizedContextKeys = sharedContextKeys.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val canonicalSubTask = tasks[subTask.taskId.value] ?: subTask

        fun reject(message: String): AgentDelegation = rejectDelegationLocked(
            parentTaskId = parentTaskId,
            subTask = canonicalSubTask,
            sourceAgentId = sourceAgentId,
            targetAgentId = targetAgentId,
            targetRole = targetRole,
            reason = message,
            depth = currentDepth,
            sharedContextKeys = normalizedContextKeys
        )

        if (reason.isBlank()) return@synchronized reject("Delegation reason is required")
        if (agentsById[sourceAgentId] == null) return@synchronized reject("Unknown source agent '$sourceAgentId'")
        if (targetAgent == null) return@synchronized reject("No registered agent has role $targetRole")
        if (sourceAgentId == targetAgentId) return@synchronized reject("Self-delegation is prohibited")
        if (parentTaskId != null && tasks[parentTaskId] == null) return@synchronized reject("Unknown parent task '$parentTaskId'")
        if (delegations.any { it.subTaskId == canonicalSubTask.taskId.value }) {
            return@synchronized reject("Task already has a parent delegation")
        }
        if (isTerminal(canonicalSubTask.state)) return@synchronized reject("Cannot delegate a terminal task")

        val parentTask = parentTaskId?.let(tasks::get)
        if (parentTask?.assignedAgentId != null && parentTask.assignedAgentId != sourceAgentId) {
            return@synchronized reject("Source agent is not assigned to the parent task")
        }
        if (currentDepth > maxDelegationDepth) {
            return@synchronized reject("Maximum delegation depth ($maxDelegationDepth) exceeded")
        }

        val chain = getDelegationChainLocked(parentTaskId)
        val lineage = chain.flatMap { listOf(it.sourceAgentId, it.targetAgentId) } + sourceAgentId
        if (targetAgentId in lineage) {
            return@synchronized reject("Recursive delegation loop detected: ${(lineage + targetAgentId).joinToString(" -> ")}")
        }

        val validationError = validateDelegatedTaskLocked(canonicalSubTask)
        if (validationError != null) return@synchronized reject(validationError)

        val delegatedTask = canonicalSubTask.copy(
            assignedRole = targetRole,
            assignedAgentId = targetAgentId,
            state = if (canonicalSubTask.dependencies.isEmpty()) AgentTaskState.SCHEDULED else AgentTaskState.PENDING,
            updatedAt = System.currentTimeMillis()
        )
        val delegation = AgentDelegation(
            parentTaskId = parentTaskId,
            subTaskId = delegatedTask.taskId.value,
            sourceAgentId = sourceAgentId,
            targetAgentId = targetAgentId,
            targetRole = targetRole,
            reason = reason.trim(),
            depth = currentDepth,
            sharedContextKeys = normalizedContextKeys
        )

        tasks[delegatedTask.taskId.value] = delegatedTask
        delegations.add(delegation)
        sharedContext.appendLog("Delegated '${delegatedTask.title}' to ${targetAgent.name} at depth $currentDepth")
        delegation
    }

    /**
     * Executes one task through the canonical fabric. A task is atomically
     * claimed before I/O so concurrent schedulers cannot execute it twice.
     */
    suspend fun executeTask(taskId: String, context: Context? = null): AgentResult {
        val task = synchronized(coordinationLock) {
            val existing = tasks[taskId] ?: return AgentResult(
                taskId = taskId,
                agentId = "unknown",
                role = AgentRole.EXECUTIVE,
                state = AgentTaskState.FAILED,
                output = "Task not found",
                error = "Task '$taskId' is not registered"
            )

            when (existing.state) {
                AgentTaskState.RUNNING -> return AgentResult(
                    taskId = taskId,
                    agentId = existing.assignedAgentId ?: "wasti_executive_agent",
                    role = existing.assignedRole ?: AgentRole.EXECUTIVE,
                    state = AgentTaskState.RUNNING,
                    output = "Task is already executing"
                )
                AgentTaskState.COMPLETED,
                AgentTaskState.FAILED,
                AgentTaskState.CANCELLED,
                AgentTaskState.BLOCKED -> return results[taskId] ?: AgentResult(
                    taskId = taskId,
                    agentId = existing.assignedAgentId ?: "wasti_executive_agent",
                    role = existing.assignedRole ?: AgentRole.EXECUTIVE,
                    state = existing.state,
                    output = "Task cannot execute in state ${existing.state}",
                    error = "Terminal task state"
                )
                else -> Unit
            }

            if (!areDependenciesSatisfiedLocked(existing)) {
                return AgentResult(
                    taskId = taskId,
                    agentId = existing.assignedAgentId ?: "wasti_executive_agent",
                    role = existing.assignedRole ?: AgentRole.EXECUTIVE,
                    state = AgentTaskState.PENDING,
                    output = "Task is waiting for dependencies"
                )
            }

            val claimed = existing.copy(state = AgentTaskState.RUNNING, updatedAt = System.currentTimeMillis())
            tasks[taskId] = claimed
            sharedContext.appendLog("Claimed task '${claimed.title}' for execution")
            claimed
        }

        val capabilityId = task.requiredCapabilities.firstOrNull() ?: "workspace_inspection"
        val request = UnifiedExecutionRequest(
            taskId = task.taskId.value,
            capabilityId = capabilityId,
            parameters = buildExecutionParameters(task)
        )
        val localStartedAt = System.currentTimeMillis()

        val fabricResult = try {
            fabric.execute(request, context)
        } catch (cancelled: CancellationException) {
            val result = cancelledResult(task, "Execution coroutine cancelled")
            recordResult(result)
            throw cancelled
        } catch (error: Exception) {
            val result = AgentResult(
                taskId = task.taskId.value,
                agentId = task.assignedAgentId ?: "wasti_executive_agent",
                role = task.assignedRole ?: AgentRole.EXECUTIVE,
                state = AgentTaskState.FAILED,
                output = "UnifiedExecutionFabric threw an exception",
                evidence = error.javaClass.name,
                executionTimeMs = System.currentTimeMillis() - localStartedAt,
                error = error.message ?: error::class.simpleName
            )
            recordResult(result)
            return result
        }

        val finalState = when (fabricResult.status) {
            UnifiedExecutionStatus.COMPLETED,
            UnifiedExecutionStatus.VERIFIED,
            UnifiedExecutionStatus.EXECUTOR_COMPLETED,
            UnifiedExecutionStatus.OBSERVED -> AgentTaskState.COMPLETED
            UnifiedExecutionStatus.RUNNING -> AgentTaskState.RUNNING
            UnifiedExecutionStatus.CANCELLED -> AgentTaskState.CANCELLED
            UnifiedExecutionStatus.FAILED,
            UnifiedExecutionStatus.VERIFICATION_FAILED -> AgentTaskState.FAILED
            else -> AgentTaskState.FAILED
        }
        val reportedDuration = fabricResult.completedAt - fabricResult.startedAt
        val result = AgentResult(
            taskId = task.taskId.value,
            agentId = task.assignedAgentId ?: "wasti_executive_agent",
            role = task.assignedRole ?: AgentRole.EXECUTIVE,
            state = finalState,
            output = fabricResult.output.ifBlank { "Executed capability $capabilityId" },
            structuredData = mapOf(
                "capabilityId" to capabilityId,
                "fabricStatus" to fabricResult.status.toString(),
                "sharedContextKeys" to scopedContextKeysFor(task.taskId.value)
            ),
            evidence = fabricResult.verificationEvidence
                ?: "UnifiedExecutionFabric status: ${fabricResult.status}; verification: ${fabricResult.verificationStatus}",
            executionTimeMs = if (reportedDuration > 0) reportedDuration else System.currentTimeMillis() - localStartedAt,
            error = if (finalState == AgentTaskState.COMPLETED || finalState == AgentTaskState.RUNNING) null else fabricResult.error
                ?: "Execution status: ${fabricResult.status}"
        )
        recordResult(result)
        return result
    }

    /** Executes the ready snapshot in priority order through the single fabric. */
    suspend fun executeReadyTasks(context: Context? = null): List<AgentResult> =
        getReadyTasks().map { executeTask(it.taskId.value, context) }

    fun getReadyTasks(): List<AgentTask> = synchronized(coordinationLock) {
        promoteEligiblePendingTasksLocked()
        tasks.values
            .filter { it.state == AgentTaskState.SCHEDULED }
            .sortedWith(compareByDescending<AgentTask> { it.priority }.thenBy { it.taskId.value })
    }

    /** Records an externally delivered fabric result and updates the graph. */
    fun recordResult(result: AgentResult) = synchronized(coordinationLock) {
        val task = tasks[result.taskId]
        if (task == null) {
            results[result.taskId] = result
            sharedContext.appendLog("Recorded result for unknown task [${result.taskId}]")
            return@synchronized
        }

        if (isTerminal(task.state) && task.state != result.state) {
            sharedContext.appendLog("Ignored late ${result.state} result for terminal task [${result.taskId}]")
            return@synchronized
        }

        val normalizedResult = if (result.state == AgentTaskState.COMPLETED && !areDependenciesSatisfiedLocked(task)) {
            result.copy(
                state = AgentTaskState.FAILED,
                output = "Task completed before its dependencies",
                error = "Dependency invariant violated",
                evidence = "All dependencies must be COMPLETED before a task can complete"
            )
        } else {
            result
        }

        results[normalizedResult.taskId] = normalizedResult
        tasks[normalizedResult.taskId] = task.copy(
            state = normalizedResult.state,
            updatedAt = System.currentTimeMillis()
        )
        sharedContext.appendLog("Task [${normalizedResult.taskId}] marked ${normalizedResult.state}")

        when (normalizedResult.state) {
            AgentTaskState.COMPLETED -> promoteEligiblePendingTasksLocked()
            AgentTaskState.FAILED,
            AgentTaskState.CANCELLED,
            AgentTaskState.BLOCKED -> blockDependentTasksLocked(normalizedResult.taskId, normalizedResult.state)
            else -> Unit
        }
    }

    /**
     * Cancels the coordinator's task state and blocks all descendants. The
     * fabric must expose its own cancellation mechanism to stop I/O in flight;
     * any late fabric result is intentionally ignored.
     */
    fun cancelTask(taskId: String, reason: String) = synchronized(coordinationLock) {
        val task = tasks[taskId] ?: return@synchronized
        if (isTerminal(task.state)) return@synchronized

        val cancellation = cancelledResult(task, reason.ifBlank { "Cancelled by coordinator" })
        results[taskId] = cancellation
        tasks[taskId] = task.copy(state = AgentTaskState.CANCELLED, updatedAt = System.currentTimeMillis())
        sharedContext.appendLog("Task [$taskId] cancelled: ${cancellation.error}")
        blockDependentTasksLocked(taskId, AgentTaskState.CANCELLED)
    }

    fun getTask(taskId: String): AgentTask? = tasks[taskId]
    fun getResult(taskId: String): AgentResult? = results[taskId]
    fun getAllTasks(): List<AgentTask> = synchronized(coordinationLock) { tasks.values.toList() }
    fun getAllDelegations(): List<AgentDelegation> = delegations.toList()

    private fun validateNewTaskLocked(
        task: AgentTask,
        requestedRole: AgentRole?,
        selectedAgent: SubAgentDefinition?
    ): String? = when {
        task.title.isBlank() -> "Task title is required"
        task.description.isBlank() -> "Task description is required"
        task.dependencies.size != task.dependencies.distinct().size -> "Task dependencies must be unique"
        task.dependencies.any { !tasks.containsKey(it) } -> "Every dependency must already exist"
        detectCycleLocked(task.taskId.value, task.dependencies) != null -> "Task dependencies create a DAG cycle"
        requestedRole != null && selectedAgent == null -> "No registered agent has role $requestedRole"
        task.requiredCapabilities.isNotEmpty() && selectedAgent == null -> "No registered agent matches the required capabilities"
        else -> null
    }

    private fun validateDelegatedTaskLocked(task: AgentTask): String? = when {
        task.dependencies.any { !tasks.containsKey(it) } -> "Every delegated-task dependency must already exist"
        detectCycleLocked(task.taskId.value, task.dependencies) != null -> "Delegated task dependencies create a DAG cycle"
        else -> null
    }

    private fun rejectTaskCreationLocked(task: AgentTask, reason: String): AgentTask {
        val failedTask = task.copy(state = AgentTaskState.FAILED, updatedAt = System.currentTimeMillis())
        tasks[failedTask.taskId.value] = failedTask
        results[failedTask.taskId.value] = AgentResult(
            taskId = failedTask.taskId.value,
            agentId = failedTask.assignedAgentId ?: "wasti_executive_agent",
            role = failedTask.assignedRole ?: AgentRole.EXECUTIVE,
            state = AgentTaskState.FAILED,
            output = "Task creation rejected",
            error = reason
        )
        sharedContext.appendLog("Rejected task '${task.title}': $reason")
        return failedTask
    }

    private fun blockNewTaskLocked(task: AgentTask, reason: String): AgentTask {
        val blockedTask = task.copy(state = AgentTaskState.BLOCKED, updatedAt = System.currentTimeMillis())
        tasks[blockedTask.taskId.value] = blockedTask
        results[blockedTask.taskId.value] = AgentResult(
            taskId = blockedTask.taskId.value,
            agentId = blockedTask.assignedAgentId ?: "wasti_executive_agent",
            role = blockedTask.assignedRole ?: AgentRole.EXECUTIVE,
            state = AgentTaskState.BLOCKED,
            output = "Task blocked by dependency state",
            error = reason
        )
        sharedContext.appendLog("Blocked new task '${task.title}': $reason")
        return blockedTask
    }

    private fun rejectDelegationLocked(
        parentTaskId: String?,
        subTask: AgentTask,
        sourceAgentId: String,
        targetAgentId: String,
        targetRole: AgentRole,
        reason: String,
        depth: Int,
        sharedContextKeys: List<String>
    ): AgentDelegation {
        val failedTask = subTask.copy(state = AgentTaskState.FAILED, updatedAt = System.currentTimeMillis())
        tasks[failedTask.taskId.value] = failedTask
        results[failedTask.taskId.value] = AgentResult(
            taskId = failedTask.taskId.value,
            agentId = sourceAgentId,
            role = targetRole,
            state = AgentTaskState.FAILED,
            output = "Delegation rejected",
            error = reason
        )
        blockDependentTasksLocked(failedTask.taskId.value, AgentTaskState.FAILED)
        sharedContext.appendLog("Rejected delegation for '${subTask.title}': $reason")
        return AgentDelegation(
            parentTaskId = parentTaskId,
            subTaskId = failedTask.taskId.value,
            sourceAgentId = sourceAgentId,
            targetAgentId = targetAgentId,
            targetRole = targetRole,
            reason = "REJECTED: $reason",
            depth = depth,
            sharedContextKeys = sharedContextKeys
        )
    }

    private fun getDelegationChainLocked(taskId: String?): List<AgentDelegation> {
        if (taskId == null) return emptyList()
        val chain = mutableListOf<AgentDelegation>()
        val visitedTasks = mutableSetOf<String>()
        var currentTaskId: String? = taskId

        while (currentTaskId != null && visitedTasks.add(currentTaskId)) {
            val parentDelegation = delegations.firstOrNull { it.subTaskId == currentTaskId } ?: break
            chain += parentDelegation
            currentTaskId = parentDelegation.parentTaskId
        }
        return chain
    }

    private fun detectCycleLocked(taskId: String, dependencies: List<String>): List<String>? {
        if (taskId in dependencies) return listOf(taskId, taskId)

        fun visit(current: String, path: List<String>, visiting: MutableSet<String>): List<String>? {
            if (current == taskId) return path
            if (!visiting.add(current)) return null
            for (dependency in tasks[current]?.dependencies.orEmpty()) {
                val cycle = visit(dependency, path + dependency, visiting)
                if (cycle != null) return cycle
            }
            visiting.remove(current)
            return null
        }

        for (dependency in dependencies) {
            val cycle = visit(dependency, listOf(taskId, dependency), mutableSetOf())
            if (cycle != null) return cycle
        }
        return null
    }

    private fun buildExecutionParameters(task: AgentTask): Map<String, Any> {
        val parameters = task.inputData.filterNotNullValues().toMutableMap()
        parameters["prompt"] = task.prompt
        val scopedContext = sharedContext.snapshot(scopedContextKeysFor(task.taskId.value))
        if (scopedContext.isNotEmpty()) parameters["shared_context"] = scopedContext
        return parameters
    }

    private fun scopedContextKeysFor(taskId: String): List<String> =
        delegations.firstOrNull { it.subTaskId == taskId }?.sharedContextKeys.orEmpty()

    private fun Map<String, Any?>.filterNotNullValues(): Map<String, Any> {
        val nonNullValues = mutableMapOf<String, Any>()
        forEach { (key, value) -> if (value != null) nonNullValues[key] = value }
        return nonNullValues
    }

    private fun areDependenciesSatisfiedLocked(task: AgentTask): Boolean =
        task.dependencies.all { dependencyId -> results[dependencyId]?.state == AgentTaskState.COMPLETED }

    private fun firstFailedDependencyLocked(task: AgentTask): String? = task.dependencies.firstOrNull { dependencyId ->
        when (tasks[dependencyId]?.state) {
            AgentTaskState.FAILED,
            AgentTaskState.CANCELLED,
            AgentTaskState.BLOCKED -> true
            else -> false
        }
    }

    private fun promoteEligiblePendingTasksLocked() {
        tasks.values
            .filter { it.state == AgentTaskState.PENDING }
            .forEach { pendingTask ->
                val failedDependency = firstFailedDependencyLocked(pendingTask)
                when {
                    failedDependency != null -> blockDependentTasksLocked(failedDependency, tasks[failedDependency]?.state ?: AgentTaskState.FAILED)
                    areDependenciesSatisfiedLocked(pendingTask) -> {
                        tasks[pendingTask.taskId.value] = pendingTask.copy(
                            state = AgentTaskState.SCHEDULED,
                            updatedAt = System.currentTimeMillis()
                        )
                        sharedContext.appendLog("Scheduled task [${pendingTask.taskId.value}]: dependencies completed")
                    }
                }
            }
    }

    private fun blockDependentTasksLocked(sourceTaskId: String, sourceState: AgentTaskState) {
        val queue = ArrayDeque<String>()
        val seen = mutableSetOf<String>()
        queue.addLast(sourceTaskId)

        while (queue.isNotEmpty()) {
            val failedDependencyId = queue.removeFirst()
            tasks.values
                .filter { failedDependencyId in it.dependencies }
                .forEach { dependentTask ->
                    if (!seen.add(dependentTask.taskId.value) || isTerminal(dependentTask.state)) return@forEach

                    val blocked = dependentTask.copy(
                        state = AgentTaskState.BLOCKED,
                        updatedAt = System.currentTimeMillis()
                    )
                    tasks[blocked.taskId.value] = blocked
                    results[blocked.taskId.value] = AgentResult(
                        taskId = blocked.taskId.value,
                        agentId = blocked.assignedAgentId ?: "wasti_executive_agent",
                        role = blocked.assignedRole ?: AgentRole.EXECUTIVE,
                        state = AgentTaskState.BLOCKED,
                        output = "Task blocked by dependency failure",
                        evidence = "Dependency '$failedDependencyId' entered state $sourceState",
                        error = "Blocked by dependency '$failedDependencyId'"
                    )
                    sharedContext.appendLog("Blocked task [${blocked.taskId.value}] because [$failedDependencyId] is $sourceState")
                    queue.addLast(blocked.taskId.value)
                }
        }
    }

    private fun cancelledResult(task: AgentTask, reason: String): AgentResult = AgentResult(
        taskId = task.taskId.value,
        agentId = task.assignedAgentId ?: "wasti_executive_agent",
        role = task.assignedRole ?: AgentRole.EXECUTIVE,
        state = AgentTaskState.CANCELLED,
        output = "Task cancelled",
        error = reason
    )

    private fun isTerminal(state: AgentTaskState): Boolean = state == AgentTaskState.COMPLETED ||
        state == AgentTaskState.FAILED ||
        state == AgentTaskState.CANCELLED ||
        state == AgentTaskState.BLOCKED
}
