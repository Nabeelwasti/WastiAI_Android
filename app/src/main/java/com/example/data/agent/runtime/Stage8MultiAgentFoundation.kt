package com.example.data.agent.runtime

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Stage 8 Foundation: Canonical Multi-Agent Collaboration Contracts & Coordination Layer.
 *
 * Governs:
 * - SubAgentDefinition and Role taxonomy (Research, Coding, Testing, Debugging, Security, Design, Data, Browser/Internet, etc.)
 * - Directed Acyclic Task Graphs with priority, state machine, and dependency resolution
 * - Formal AgentDelegation contracts with shared context scopes
 * - Structured AgentResult aggregation and failure propagation
 * - Strict UnifiedExecutionFabric coupling (NO duplicate tool registries or execution authorities)
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
 * Thread-safe shared context container for multi-agent workflows.
 */
class SharedAgentContext {
    private val data = ConcurrentHashMap<String, Any?>()
    private val logs = CopyOnWriteArrayList<String>()

    fun set(key: String, value: Any?) {
        if (value != null) {
            data[key] = value
        } else {
            data.remove(key)
        }
    }

    fun get(key: String): Any? = data[key]

    @Suppress("UNCHECKED_CAST")
    fun <T> getTyped(key: String): T? = data[key] as? T

    fun getAll(): Map<String, Any?> = data.toMap()

    fun appendLog(message: String) {
        logs.add("[${System.currentTimeMillis()}] $message")
    }

    fun getLogs(): List<String> = logs.toList()

    fun clear() {
        data.clear()
        logs.clear()
    }
}

/**
 * Canonical registry of default specialized SubAgents in Wasti OS.
 */
object WastiSubAgentCatalog {
    val defaultSubAgents: List<SubAgentDefinition> = listOf(
        SubAgentDefinition(
            id = "wasti_executive_agent",
            name = "Executive Orchestrator",
            role = AgentRole.EXECUTIVE,
            description = "High-level goal decomposition, multi-domain synthesis, and priority alignment.",
            capabilities = listOf("Executive Strategy", "Domain Orchestration", "Goal Decomposition"),
            systemPrompt = "You are Wasti's Executive Orchestrator. Direct high-level objectives and synthesize sub-agent outputs.",
            temperature = 0.2f
        ),
        SubAgentDefinition(
            id = "wasti_research_agent",
            name = "Research Specialist",
            role = AgentRole.RESEARCH,
            description = "Deep web search, factual verification, knowledge retrieval, and data synthesis.",
            capabilities = listOf("search_web", "read_web_page", "Web Research", "Fact Verification"),
            systemPrompt = "You are Wasti's Research Specialist. Gather and synthesize factual information from real sources.",
            temperature = 0.2f
        ),
        SubAgentDefinition(
            id = "wasti_coding_agent",
            name = "Coding Architect",
            role = AgentRole.CODING,
            description = "Code generation, multi-language editing, project scaffolding, and architecture refactoring.",
            capabilities = listOf("project_dev_manager", "files", "execute_code", "Code Generation", "Refactoring"),
            systemPrompt = "You are Wasti's Coding Architect. Write clean, modular, production-grade code within workspace boundaries.",
            temperature = 0.1f
        ),
        SubAgentDefinition(
            id = "wasti_testing_agent",
            name = "Testing & Verification Specialist",
            role = AgentRole.TESTING,
            description = "Test discovery, test case generation, test execution, and assertion verification.",
            capabilities = listOf("test_project", "run_tests", "Test Execution", "Assertion Verification"),
            systemPrompt = "You are Wasti's Testing Specialist. Discover and execute automated tests to verify functional correctness.",
            temperature = 0.1f
        ),
        SubAgentDefinition(
            id = "wasti_debugging_agent",
            name = "Diagnostic & Debugging Specialist",
            role = AgentRole.DEBUGGING,
            description = "Compiler error analysis, stack trace diagnosis, and root cause localization.",
            capabilities = listOf("debug_project", "analyze_diagnostics", "Stack Trace Analysis", "Root Cause Discovery"),
            systemPrompt = "You are Wasti's Debugging Specialist. Analyze failure logs and locate exact root causes.",
            temperature = 0.1f
        ),
        SubAgentDefinition(
            id = "wasti_security_agent",
            name = "Security & Policy Auditor",
            role = AgentRole.SECURITY,
            description = "Workspace boundary enforcement, risk policy auditing, credential protection, and emergency stop.",
            capabilities = listOf("wasti_sandbox", "Security Audit", "Credential Vault", "Boundary Enforcement"),
            systemPrompt = "You are Wasti's Security Auditor. Protect workspace boundaries and enforce security policies.",
            temperature = 0.0f
        ),
        SubAgentDefinition(
            id = "wasti_design_agent",
            name = "UI/UX & Design Specialist",
            role = AgentRole.DESIGN,
            description = "Material Design 3 layouts, responsive ergonomics, accessibility standards, and visual polish.",
            capabilities = listOf("UI/UX Design", "Material Design 3", "Accessibility", "Visual Hierarchy"),
            systemPrompt = "You are Wasti's Design Specialist. Craft accessible, high-contrast, beautiful user interfaces.",
            temperature = 0.4f
        ),
        SubAgentDefinition(
            id = "wasti_data_agent",
            name = "Data & Storage Specialist",
            role = AgentRole.DATA,
            description = "SQL schema design, database queries, structured data extraction, and Room persistence.",
            capabilities = listOf("SQL", "database", "Data Modeling", "Schema Migration"),
            systemPrompt = "You are Wasti's Data Specialist. Manage relational schemas and local persistent storage.",
            temperature = 0.2f
        ),
        SubAgentDefinition(
            id = "wasti_browser_agent",
            name = "Browser & Network Specialist",
            role = AgentRole.BROWSER_INTERNET,
            description = "Web page inspection, network request verification, and web asset extraction.",
            capabilities = listOf("read_web_page", "search_web", "Network Inspection"),
            systemPrompt = "You are Wasti's Browser Specialist. Inspect web resources and extract structured web content.",
            temperature = 0.2f
        )
    )
}

/**
 * Foundational Agent Task Coordinator for managing task graphs, delegations, and result aggregation.
 * Strictly operates through UnifiedExecutionFabric for execution.
 */
class AgentTaskCoordinator(
    private val fabric: UnifiedExecutionFabric,
    private val subAgents: List<SubAgentDefinition> = WastiSubAgentCatalog.defaultSubAgents,
    val maxDelegationDepth: Int = 5
) {
    private val tasks = ConcurrentHashMap<String, AgentTask>()
    private val results = ConcurrentHashMap<String, AgentResult>()
    private val delegations = CopyOnWriteArrayList<AgentDelegation>()
    val sharedContext = SharedAgentContext()

    /**
     * Detects if adding [dependencies] to [taskId] creates a cycle in the task graph.
     * Returns the cycle as a list of Task IDs (e.g. [A, A] or [A, B, A] or [A, B, C, A]), or null if acyclic.
     */
    fun detectCycle(taskId: String, dependencies: List<String>): List<String>? {
        if (dependencies.contains(taskId)) {
            return listOf(taskId, taskId) // Self-cycle
        }

        // Check if any dependency can reach taskId via its existing dependencies
        val visited = mutableSetOf<String>()
        val path = mutableListOf<String>()

        fun dfs(current: String): Boolean {
            if (current == taskId) {
                path.add(current)
                return true
            }
            if (current in visited) return false
            visited.add(current)
            path.add(current)

            val nextDeps = tasks[current]?.dependencies ?: emptyList()
            for (dep in nextDeps) {
                if (dfs(dep)) {
                    return true
                }
            }
            path.removeAt(path.size - 1)
            return false
        }

        for (dep in dependencies) {
            visited.clear()
            path.clear()
            path.add(taskId)
            if (dfs(dep)) {
                return path.toList()
            }
        }
        return null
    }

    /**
     * Registers a new task in the coordinator graph with DAG validation.
     */
    fun createTask(
        title: String,
        description: String,
        assignedRole: AgentRole? = null,
        priority: AgentTaskPriority = AgentTaskPriority.MEDIUM,
        dependencies: List<String> = emptyList(),
        inputData: Map<String, Any?> = emptyMap(),
        requiredCapabilities: List<String> = emptyList()
    ): AgentTask {
        val assignedAgent = assignedRole?.let { role ->
            selectAgentForRole(role)
        } ?: selectAgentForCapabilities(requiredCapabilities)

        val task = AgentTask(
            taskId = TaskId(),
            prompt = description,
            title = title,
            description = description,
            assignedRole = assignedRole ?: assignedAgent?.role,
            assignedAgentId = assignedAgent?.id,
            priority = priority,
            state = if (dependencies.isEmpty()) AgentTaskState.SCHEDULED else AgentTaskState.PENDING,
            dependencies = dependencies,
            inputData = inputData,
            requiredCapabilities = requiredCapabilities
        )

        // Check for DAG cycle
        val cycle = detectCycle(task.taskId.value, dependencies)
        if (cycle != null) {
            val cycleStr = cycle.joinToString(" -> ")
            val failedTask = task.copy(
                state = AgentTaskState.FAILED,
                updatedAt = System.currentTimeMillis()
            )
            tasks[failedTask.taskId.value] = failedTask
            val failureResult = AgentResult(
                taskId = failedTask.taskId.value,
                agentId = assignedAgent?.id ?: "unknown",
                role = failedTask.assignedRole ?: AgentRole.EXECUTIVE,
                state = AgentTaskState.FAILED,
                output = "Task creation rejected due to DAG cycle",
                evidence = "Cycle detected: $cycleStr",
                error = "DAG cycle detected involving task: $cycleStr"
            )
            results[failedTask.taskId.value] = failureResult
            sharedContext.appendLog("ERROR: DAG cycle detected for task '${task.title}': $cycleStr")
            return failedTask
        }

        tasks[task.taskId.value] = task
        sharedContext.appendLog("Created task '${task.title}' [${task.taskId.value}] assigned to ${task.assignedRole ?: "UNASSIGNED"}")
        return task
    }

    /**
     * Selects the most appropriate sub-agent for a given role.
     */
    fun selectAgentForRole(role: AgentRole): SubAgentDefinition? {
        return subAgents.find { it.role == role }
    }

    /**
     * Selects an agent whose capabilities match the required capability identifiers.
     */
    fun selectAgentForCapabilities(capabilities: List<String>): SubAgentDefinition? {
        if (capabilities.isEmpty()) return null
        return subAgents.maxByOrNull { agent ->
            capabilities.count { cap -> agent.capabilities.any { it.equals(cap, ignoreCase = true) } }
        }
    }

    /**
     * Retrieves the upstream delegation chain starting from a given task.
     */
    fun getDelegationChain(taskId: String?): List<AgentDelegation> {
        if (taskId == null) return emptyList()
        val chain = mutableListOf<AgentDelegation>()
        var currentTaskId: String? = taskId
        val visitedSubTasks = mutableSetOf<String>()
        while (currentTaskId != null && currentTaskId !in visitedSubTasks) {
            visitedSubTasks.add(currentTaskId)
            val del = delegations.find { it.subTaskId == currentTaskId }
            if (del != null) {
                chain.add(del)
                currentTaskId = del.parentTaskId
            } else {
                break
            }
        }
        return chain
    }

    /**
     * Delegates a sub-task from one agent to another with strict cycle, self-delegation, and depth guards.
     */
    fun delegateTask(
        parentTaskId: String?,
        subTask: AgentTask,
        sourceAgentId: String,
        targetRole: AgentRole,
        reason: String,
        sharedContextKeys: List<String> = emptyList()
    ): AgentDelegation {
        val targetAgent = selectAgentForRole(targetRole)
        val targetAgentId = targetAgent?.id ?: "unknown"

        // 1. Self-delegation guard
        if (sourceAgentId == targetAgentId) {
            val failedSubTask = subTask.copy(
                state = AgentTaskState.FAILED,
                updatedAt = System.currentTimeMillis()
            )
            tasks[failedSubTask.taskId.value] = failedSubTask
            val failureResult = AgentResult(
                taskId = failedSubTask.taskId.value,
                agentId = sourceAgentId,
                role = targetRole,
                state = AgentTaskState.FAILED,
                output = "Delegation rejected: Self-delegation is prohibited",
                evidence = "Source agent '$sourceAgentId' attempted self-delegation",
                error = "Self-delegation forbidden: source '$sourceAgentId' equals target '$targetAgentId'"
            )
            results[failedSubTask.taskId.value] = failureResult
            sharedContext.appendLog("ERROR: Self-delegation rejected for agent '$sourceAgentId'")
            return AgentDelegation(
                parentTaskId = parentTaskId,
                subTaskId = subTask.taskId.value,
                sourceAgentId = sourceAgentId,
                targetAgentId = targetAgentId,
                targetRole = targetRole,
                reason = "REJECTED: Self-delegation forbidden",
                depth = 1,
                sharedContextKeys = sharedContextKeys
            )
        }

        // 2. Recursive delegation loop guard
        val delegationChain = getDelegationChain(parentTaskId)
        val lineageAgentIds = delegationChain.map { it.sourceAgentId }.toMutableList()
        if (delegationChain.isNotEmpty()) {
            lineageAgentIds.addAll(delegationChain.map { it.targetAgentId })
        }
        lineageAgentIds.add(sourceAgentId)

        if (targetAgentId in lineageAgentIds) {
            val cyclePath = (lineageAgentIds.distinct() + targetAgentId).joinToString(" -> ")
            val failedSubTask = subTask.copy(
                state = AgentTaskState.FAILED,
                updatedAt = System.currentTimeMillis()
            )
            tasks[failedSubTask.taskId.value] = failedSubTask
            val failureResult = AgentResult(
                taskId = failedSubTask.taskId.value,
                agentId = sourceAgentId,
                role = targetRole,
                state = AgentTaskState.FAILED,
                output = "Delegation rejected: Recursive delegation loop detected",
                evidence = "Loop detected: $cyclePath",
                error = "Recursive delegation loop: $cyclePath"
            )
            results[failedSubTask.taskId.value] = failureResult
            sharedContext.appendLog("ERROR: Recursive delegation loop detected: $cyclePath")
            return AgentDelegation(
                parentTaskId = parentTaskId,
                subTaskId = subTask.taskId.value,
                sourceAgentId = sourceAgentId,
                targetAgentId = targetAgentId,
                targetRole = targetRole,
                reason = "REJECTED: Recursive delegation loop ($cyclePath)",
                depth = delegationChain.size + 1,
                sharedContextKeys = sharedContextKeys
            )
        }

        // 3. Maximum delegation depth guard
        val currentDepth = delegationChain.size + 1
        if (currentDepth > maxDelegationDepth) {
            val failedSubTask = subTask.copy(
                state = AgentTaskState.FAILED,
                updatedAt = System.currentTimeMillis()
            )
            tasks[failedSubTask.taskId.value] = failedSubTask
            val failureResult = AgentResult(
                taskId = failedSubTask.taskId.value,
                agentId = sourceAgentId,
                role = targetRole,
                state = AgentTaskState.FAILED,
                output = "Delegation rejected: Maximum delegation depth ($maxDelegationDepth) exceeded",
                evidence = "Current delegation depth is $currentDepth (limit: $maxDelegationDepth)",
                error = "Excessive delegation depth: current $currentDepth > max $maxDelegationDepth"
            )
            results[failedSubTask.taskId.value] = failureResult
            sharedContext.appendLog("ERROR: Max delegation depth ($maxDelegationDepth) exceeded at depth $currentDepth")
            return AgentDelegation(
                parentTaskId = parentTaskId,
                subTaskId = subTask.taskId.value,
                sourceAgentId = sourceAgentId,
                targetAgentId = targetAgentId,
                targetRole = targetRole,
                reason = "REJECTED: Max delegation depth exceeded",
                depth = currentDepth,
                sharedContextKeys = sharedContextKeys
            )
        }

        // Normal non-cyclic delegation
        val delegation = AgentDelegation(
            parentTaskId = parentTaskId,
            subTaskId = subTask.taskId.value,
            sourceAgentId = sourceAgentId,
            targetAgentId = targetAgentId,
            targetRole = targetRole,
            reason = reason,
            depth = currentDepth,
            sharedContextKeys = sharedContextKeys
        )

        delegations.add(delegation)
        tasks[subTask.taskId.value] = subTask.copy(
            assignedRole = targetRole,
            assignedAgentId = targetAgentId,
            state = if (subTask.dependencies.isEmpty()) AgentTaskState.SCHEDULED else AgentTaskState.PENDING,
            updatedAt = System.currentTimeMillis()
        )

        sharedContext.appendLog("Delegated subtask '${subTask.title}' [depth=$currentDepth] to $targetRole ($reason)")
        return delegation
    }

    /**
     * Evaluates ready tasks whose dependencies are fully completed.
     */
    fun getReadyTasks(): List<AgentTask> {
        return tasks.values.filter { task ->
            task.state == AgentTaskState.SCHEDULED || (task.state == AgentTaskState.PENDING && areDependenciesSatisfied(task))
        }.sortedByDescending { it.priority }
    }

    private fun areDependenciesSatisfied(task: AgentTask): Boolean {
        if (task.dependencies.isEmpty()) return true
        return task.dependencies.all { depId ->
            results[depId]?.state == AgentTaskState.COMPLETED
        }
    }

    /**
     * Records a result for an agent task and advances dependent tasks.
     */
    fun recordResult(result: AgentResult) {
        results[result.taskId] = result
        val currentTask = tasks[result.taskId]
        if (currentTask != null) {
            tasks[result.taskId] = currentTask.copy(
                state = result.state,
                updatedAt = System.currentTimeMillis()
            )
        }

        sharedContext.appendLog("Task [${result.taskId}] marked as ${result.state}. Output: ${result.output.take(80)}")

        // Check if pending tasks can now be scheduled
        if (result.state == AgentTaskState.COMPLETED) {
            tasks.values.filter { it.state == AgentTaskState.PENDING && areDependenciesSatisfied(it) }
                .forEach { readyTask ->
                    tasks[readyTask.taskId.value] = readyTask.copy(
                        state = AgentTaskState.SCHEDULED,
                        updatedAt = System.currentTimeMillis()
                    )
                }
        }
    }

    /**
     * Executes a task using the canonical UnifiedExecutionFabric.
     */
    suspend fun executeTask(taskId: String): AgentResult {
        val task = tasks[taskId] ?: return AgentResult(
            taskId = taskId,
            agentId = "unknown",
            role = AgentRole.EXECUTIVE,
            state = AgentTaskState.FAILED,
            output = "Task not found",
            error = "Task with ID '$taskId' does not exist"
        )

        if (task.state == AgentTaskState.CANCELLED || task.state == AgentTaskState.BLOCKED || task.state == AgentTaskState.FAILED) {
            return results[taskId] ?: AgentResult(
                taskId = taskId,
                agentId = task.assignedAgentId ?: "unknown",
                role = task.assignedRole ?: AgentRole.EXECUTIVE,
                state = task.state,
                output = "Task cannot execute in state ${task.state}",
                error = "Task state is ${task.state}"
            )
        }

        tasks[taskId] = task.copy(state = AgentTaskState.RUNNING, updatedAt = System.currentTimeMillis())
        sharedContext.appendLog("Executing task '${task.title}' via UnifiedExecutionFabric...")

        val startTime = System.currentTimeMillis()
        val primaryCapability = task.requiredCapabilities.firstOrNull() ?: "workspace_inspection"

        val request = UnifiedExecutionRequest(
            taskId = taskId,
            capabilityId = primaryCapability,
            parameters = mapOf("prompt" to task.prompt)
        )

        val execResult = fabric.execute(request)
        val duration = System.currentTimeMillis() - startTime

        val isSuccess = execResult.status == UnifiedExecutionStatus.COMPLETED ||
                execResult.status == UnifiedExecutionStatus.VERIFIED ||
                execResult.status == UnifiedExecutionStatus.EXECUTOR_COMPLETED ||
                execResult.status == UnifiedExecutionStatus.OBSERVED

        val agentResult = AgentResult(
            taskId = taskId,
            agentId = task.assignedAgentId ?: "wasti_executive_agent",
            role = task.assignedRole ?: AgentRole.EXECUTIVE,
            state = if (isSuccess) AgentTaskState.COMPLETED else AgentTaskState.FAILED,
            output = if (execResult.output.isNotBlank()) execResult.output else "Executed capability $primaryCapability",
            evidence = execResult.verificationEvidence ?: "Executed via UnifiedExecutionFabric (executor: ${execResult.executor})",
            executionTimeMs = duration,
            error = if (!isSuccess) (execResult.error ?: "Execution status: ${execResult.status}") else null
        )

        recordResult(agentResult)
        return agentResult
    }

    /**
     * Executes all currently ready tasks in priority order.
     */
    suspend fun executeReadyTasks(): List<AgentResult> {
        val ready = getReadyTasks()
        return ready.map { executeTask(it.taskId.value) }
    }

    /**
     * Cancels a task and marks all dependent tasks as BLOCKED.
     */
    fun cancelTask(taskId: String, reason: String) {
        val task = tasks[taskId] ?: return
        tasks[taskId] = task.copy(
            state = AgentTaskState.CANCELLED,
            updatedAt = System.currentTimeMillis()
        )
        sharedContext.appendLog("Task [$taskId] cancelled: $reason")

        // Block dependent tasks
        tasks.values.filter { it.dependencies.contains(taskId) }.forEach { depTask ->
            tasks[depTask.taskId.value] = depTask.copy(
                state = AgentTaskState.BLOCKED,
                updatedAt = System.currentTimeMillis()
            )
            sharedContext.appendLog("Task [${depTask.taskId.value}] blocked due to cancellation of dependency [$taskId]")
        }
    }

    fun getTask(taskId: String): AgentTask? = tasks[taskId]
    fun getResult(taskId: String): AgentResult? = results[taskId]
    fun getAllTasks(): List<AgentTask> = tasks.values.toList()
    fun getAllDelegations(): List<AgentDelegation> = delegations.toList()
    fun getSubAgents(): List<SubAgentDefinition> = subAgents
}
