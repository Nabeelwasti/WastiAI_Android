package com.example.data.agent.runtime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Behavioural tests for the Stage 8 "one brain, many capabilities" coordinator.
 *
 * These tests use the real UnifiedExecutionFabric fixture for the public
 * execution contract. Graph, delegation, and failure-state tests stay fully
 * deterministic by exercising the coordinator's public result API directly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Stage8MultiAgentFoundationTest {

    private lateinit var context: Context
    private lateinit var realityRegistry: CapabilityRealityRegistry
    private lateinit var fabric: UnifiedExecutionFabric
    private lateinit var coordinator: AgentTaskCoordinator

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        realityRegistry = CapabilityRealityRegistry()
        fabric = UnifiedExecutionFabric(
            realityRegistry = realityRegistry,
            eventBus = AgentEventBus(),
            auditEngine = RealityAuditEngine(realityRegistry, WastiCredentialBroker()),
            appContext = context
        )
        coordinator = AgentTaskCoordinator(fabric)
    }

    @Test
    fun defaultCatalog_hasOneProfileForEachCoreRole() {
        val agents = coordinator.getSubAgents()

        assertTrue(agents.isNotEmpty())
        assertEquals("Coding Architect", coordinator.selectAgentForRole(AgentRole.CODING)?.name)
        assertEquals("Testing & Verification Specialist", coordinator.selectAgentForRole(AgentRole.TESTING)?.name)
        assertEquals(0.0f, coordinator.selectAgentForRole(AgentRole.SECURITY)?.temperature ?: -1f, 0.01f)
        assertEquals("wasti_executive_agent", coordinator.selectAgentForRole(AgentRole.EXECUTIVE)?.id)
    }

    @Test
    fun capabilitySelection_requiresAnActualMatch_andCanBeExtendedSafely() {
        assertEquals(
            "wasti_debugging_agent",
            coordinator.selectAgentForCapabilities(listOf("debug_project"))?.id
        )
        assertNull(coordinator.selectAgentForCapabilities(listOf("capability_that_does_not_exist")))

        val forensicsAgent = SubAgentDefinition(
            id = "wasti_forensics_agent",
            name = "Forensics Specialist",
            role = AgentRole.DEBUGGING,
            description = "Memory and artifact analysis.",
            capabilities = listOf("memory_forensics"),
            systemPrompt = "Return only evidenced forensic findings.",
            temperature = 0.1f
        )
        coordinator.registerSubAgent(forensicsAgent)

        assertEquals(forensicsAgent.id, coordinator.selectAgentForCapabilities(listOf("memory_forensics"))?.id)
        assertTrue(coordinator.getSubAgents().any { it.id == forensicsAgent.id })
    }

    @Test
    fun createTask_assignsMatchingAgent_andRejectsUnknownCapabilities() {
        val debuggingTask = coordinator.createTask(
            title = "Analyse compiler error",
            description = "Diagnose the reported Kotlin compiler error.",
            requiredCapabilities = listOf("debug_project")
        )
        assertEquals(AgentTaskState.SCHEDULED, debuggingTask.state)
        assertEquals(AgentRole.DEBUGGING, debuggingTask.assignedRole)
        assertEquals("wasti_debugging_agent", debuggingTask.assignedAgentId)

        val unsupportedTask = coordinator.createTask(
            title = "Unsupported capability",
            description = "This must not silently route to an unrelated agent.",
            requiredCapabilities = listOf("capability_that_does_not_exist")
        )
        assertEquals(AgentTaskState.FAILED, unsupportedTask.state)
        assertTrue(coordinator.getResult(unsupportedTask.taskId.value)?.error?.contains("No registered agent matches") == true)
    }

    @Test
    fun createTask_rejectsDependenciesThatAreNotAlreadyInTheGraph() {
        val task = coordinator.createTask(
            title = "Invalid dependency",
            description = "Dependencies must be registered before use.",
            dependencies = listOf("missing-task-id")
        )

        assertEquals(AgentTaskState.FAILED, task.state)
        assertTrue(coordinator.getResult(task.taskId.value)?.error?.contains("dependency must already exist") == true)
    }

    @Test
    fun readyTasks_followPriority_andWaitForEveryDependency() {
        val lowPriority = coordinator.createTask(
            title = "Low priority task",
            description = "Lower priority independent work.",
            priority = AgentTaskPriority.LOW
        )
        val firstDependency = coordinator.createTask(
            title = "Build",
            description = "Build the application.",
            assignedRole = AgentRole.CODING,
            priority = AgentTaskPriority.HIGH
        )
        val secondDependency = coordinator.createTask(
            title = "Review policy",
            description = "Review execution policy.",
            assignedRole = AgentRole.SECURITY,
            priority = AgentTaskPriority.HIGH
        )
        val dependent = coordinator.createTask(
            title = "Run integration tests",
            description = "Tests require both build and policy review.",
            assignedRole = AgentRole.TESTING,
            dependencies = listOf(firstDependency.taskId.value, secondDependency.taskId.value)
        )

        assertEquals(AgentTaskState.PENDING, dependent.state)
        assertTrue(coordinator.getReadyTasks().indexOfFirst { it.taskId == firstDependency.taskId } <
            coordinator.getReadyTasks().indexOfFirst { it.taskId == lowPriority.taskId })

        coordinator.recordResult(completedResult(firstDependency))
        assertEquals(AgentTaskState.PENDING, coordinator.getTask(dependent.taskId.value)?.state)

        coordinator.recordResult(completedResult(secondDependency))
        assertEquals(AgentTaskState.SCHEDULED, coordinator.getTask(dependent.taskId.value)?.state)
        assertTrue(coordinator.getReadyTasks().any { it.taskId == dependent.taskId })
    }

    @Test
    fun completionBeforeDependencies_isRejectedInsteadOfCorruptingTheGraph() {
        val prerequisite = coordinator.createTask(title = "Prerequisite", description = "Must finish first.")
        val dependent = coordinator.createTask(
            title = "Dependent",
            description = "Cannot complete first.",
            dependencies = listOf(prerequisite.taskId.value)
        )

        coordinator.recordResult(completedResult(dependent))

        assertEquals(AgentTaskState.FAILED, coordinator.getTask(dependent.taskId.value)?.state)
        assertEquals("Dependency invariant violated", coordinator.getResult(dependent.taskId.value)?.error)
    }

    @Test
    fun failedDependency_blocksEveryDescendant_andFutureDependents() {
        val root = coordinator.createTask(title = "Root", description = "Root task.")
        val child = coordinator.createTask(
            title = "Child",
            description = "Depends on root.",
            dependencies = listOf(root.taskId.value)
        )
        val grandchild = coordinator.createTask(
            title = "Grandchild",
            description = "Depends on child.",
            dependencies = listOf(child.taskId.value)
        )

        coordinator.recordResult(
            AgentResult(
                taskId = root.taskId.value,
                agentId = "wasti_executive_agent",
                role = AgentRole.EXECUTIVE,
                state = AgentTaskState.FAILED,
                output = "Root failure",
                error = "Deliberate failure for propagation test"
            )
        )

        assertEquals(AgentTaskState.FAILED, coordinator.getTask(root.taskId.value)?.state)
        assertEquals(AgentTaskState.BLOCKED, coordinator.getTask(child.taskId.value)?.state)
        assertEquals(AgentTaskState.BLOCKED, coordinator.getTask(grandchild.taskId.value)?.state)
        assertEquals(AgentTaskState.BLOCKED, coordinator.getResult(grandchild.taskId.value)?.state)

        val lateDependent = coordinator.createTask(
            title = "Late dependent",
            description = "Created after root failed.",
            dependencies = listOf(root.taskId.value)
        )
        assertEquals(AgentTaskState.BLOCKED, lateDependent.state)
    }

    @Test
    fun cancellation_blocksDescendants_andLateCompletionCannotReviveTask() {
        val root = coordinator.createTask(title = "Root", description = "Root task.")
        val child = coordinator.createTask(
            title = "Child",
            description = "Depends on root.",
            dependencies = listOf(root.taskId.value)
        )

        coordinator.cancelTask(root.taskId.value, "User aborted the operation")
        coordinator.recordResult(completedResult(root))

        assertEquals(AgentTaskState.CANCELLED, coordinator.getTask(root.taskId.value)?.state)
        assertEquals(AgentTaskState.CANCELLED, coordinator.getResult(root.taskId.value)?.state)
        assertEquals(AgentTaskState.BLOCKED, coordinator.getTask(child.taskId.value)?.state)
    }

    @Test
    fun detectCycle_returnsTheFullCyclePath() {
        val taskA = coordinator.createTask(title = "A", description = "First task.")
        val taskB = coordinator.createTask(
            title = "B",
            description = "Second task.",
            dependencies = listOf(taskA.taskId.value)
        )

        assertEquals(
            listOf(taskA.taskId.value, taskB.taskId.value, taskA.taskId.value),
            coordinator.detectCycle(taskA.taskId.value, listOf(taskB.taskId.value))
        )
        assertEquals(
            listOf("self", "self"),
            coordinator.detectCycle("self", listOf("self"))
        )
    }

    @Test
    fun acceptedDelegation_updatesRouting_andContextIsExplicitlyScoped() {
        val parent = coordinator.createTask(
            title = "Plan secure change",
            description = "Executive planning task.",
            assignedRole = AgentRole.EXECUTIVE
        )
        val subTask = coordinator.createTask(
            title = "Audit change",
            description = "Security review task.",
            assignedRole = AgentRole.CODING
        )
        coordinator.sharedContext.set("policy_level", "STRICT")
        coordinator.sharedContext.set("unrelated_secret", "must not be included")

        val delegation = coordinator.delegateTask(
            parentTaskId = parent.taskId.value,
            subTask = subTask,
            sourceAgentId = "wasti_executive_agent",
            targetRole = AgentRole.SECURITY,
            reason = "Privileged operation requires an audit.",
            sharedContextKeys = listOf("policy_level", "policy_level", " ")
        )

        assertFalse(delegation.reason.startsWith("REJECTED"))
        assertEquals(1, delegation.depth)
        assertEquals(listOf("policy_level"), delegation.sharedContextKeys)
        assertEquals(AgentRole.SECURITY, coordinator.getTask(subTask.taskId.value)?.assignedRole)
        assertEquals(mapOf("policy_level" to "STRICT"), coordinator.sharedContext.snapshot(delegation.sharedContextKeys))
        assertFalse(coordinator.sharedContext.snapshot(delegation.sharedContextKeys).containsKey("unrelated_secret"))
    }

    @Test
    fun delegation_rejectsUnknownSourceAndParentImpersonation() {
        val parent = coordinator.createTask(
            title = "Executive parent",
            description = "Owned by executive.",
            assignedRole = AgentRole.EXECUTIVE
        )
        val unknownSourceTask = coordinator.createTask(title = "Unknown source", description = "Reject unknown source.")
        val unknownSource = coordinator.delegateTask(
            parentTaskId = parent.taskId.value,
            subTask = unknownSourceTask,
            sourceAgentId = "unknown-agent",
            targetRole = AgentRole.RESEARCH,
            reason = "This source is not registered."
        )
        assertTrue(unknownSource.reason.startsWith("REJECTED"))
        assertTrue(coordinator.getResult(unknownSourceTask.taskId.value)?.error?.contains("Unknown source") == true)

        val impersonationTask = coordinator.createTask(title = "Impersonation", description = "Reject parent impersonation.")
        val impersonation = coordinator.delegateTask(
            parentTaskId = parent.taskId.value,
            subTask = impersonationTask,
            sourceAgentId = "wasti_coding_agent",
            targetRole = AgentRole.TESTING,
            reason = "Coding agent must not impersonate executive."
        )
        assertTrue(impersonation.reason.startsWith("REJECTED"))
        assertTrue(coordinator.getResult(impersonationTask.taskId.value)?.error?.contains("not assigned to the parent") == true)
    }

    @Test
    fun delegation_rejectsSelfLoopsRecursiveLoopsAndExcessiveDepth() {
        val selfTask = coordinator.createTask(
            title = "Self delegation",
            description = "Coding task.",
            assignedRole = AgentRole.CODING
        )
        val selfDelegation = coordinator.delegateTask(
            parentTaskId = null,
            subTask = selfTask,
            sourceAgentId = "wasti_coding_agent",
            targetRole = AgentRole.CODING,
            reason = "Must be rejected."
        )
        assertTrue(selfDelegation.reason.startsWith("REJECTED"))
        assertTrue(coordinator.getResult(selfTask.taskId.value)?.error?.contains("Self-delegation") == true)

        val root = coordinator.createTask(title = "Root", description = "Executive root.", assignedRole = AgentRole.EXECUTIVE)
        val coding = coordinator.createTask(title = "Code", description = "Coding step.")
        val testing = coordinator.createTask(title = "Test", description = "Testing step.")
        assertFalse(
            coordinator.delegateTask(root.taskId.value, coding, "wasti_executive_agent", AgentRole.CODING, "Implement.").reason
                .startsWith("REJECTED")
        )
        assertFalse(
            coordinator.delegateTask(coding.taskId.value, testing, "wasti_coding_agent", AgentRole.TESTING, "Verify.").reason
                .startsWith("REJECTED")
        )
        val loopTask = coordinator.createTask(title = "Loop", description = "Must not route back to coding.")
        val loop = coordinator.delegateTask(
            testing.taskId.value,
            loopTask,
            "wasti_testing_agent",
            AgentRole.CODING,
            "This would recreate a lineage."
        )
        assertTrue(loop.reason.startsWith("REJECTED"))
        assertTrue(coordinator.getResult(loopTask.taskId.value)?.error?.contains("Recursive delegation loop") == true)

        val shallow = AgentTaskCoordinator(fabric, maxDelegationDepth = 1)
        val shallowRoot = shallow.createTask(title = "Shallow root", description = "Root.", assignedRole = AgentRole.EXECUTIVE)
        val first = shallow.createTask(title = "First", description = "First level.")
        val second = shallow.createTask(title = "Second", description = "Second level.")
        assertFalse(
            shallow.delegateTask(shallowRoot.taskId.value, first, "wasti_executive_agent", AgentRole.RESEARCH, "Level one.").reason
                .startsWith("REJECTED")
        )
        val tooDeep = shallow.delegateTask(first.taskId.value, second, "wasti_research_agent", AgentRole.CODING, "Level two.")
        assertTrue(tooDeep.reason.startsWith("REJECTED"))
        assertTrue(shallow.getResult(second.taskId.value)?.error?.contains("Maximum delegation depth") == true)
    }

    @Test
    fun sharedContext_boundsItsAuditLog() {
        val boundedContext = SharedAgentContext(maxLogEntries = 2)
        boundedContext.appendLog("first")
        boundedContext.appendLog("second")
        boundedContext.appendLog("third")

        val logs = boundedContext.getLogs()
        assertEquals(2, logs.size)
        assertFalse(logs.any { it.endsWith("first") })
        assertTrue(logs.any { it.endsWith("second") })
        assertTrue(logs.any { it.endsWith("third") })
    }

    @Test
    fun execution_routesThroughUnifiedFabric_andRecordsTheTerminalResult() = runBlocking {
        val task = coordinator.createTask(
            title = "Inspect environment",
            description = "Inspect the runtime environment through the single fabric.",
            assignedRole = AgentRole.EXECUTIVE,
            requiredCapabilities = listOf("system_info"),
            inputData = mapOf("request_origin" to "Stage8MultiAgentFoundationTest")
        )

        val result = coordinator.executeTask(task.taskId.value, context)

        assertEquals(task.taskId.value, result.taskId)
        assertEquals(AgentTaskState.COMPLETED, result.state)
        assertEquals("system_info", result.structuredData["capabilityId"])
        assertTrue(result.output.isNotBlank())
        assertEquals(AgentTaskState.COMPLETED, coordinator.getTask(task.taskId.value)?.state)
        assertEquals(result, coordinator.getResult(task.taskId.value))
    }

    private fun completedResult(task: AgentTask): AgentResult = AgentResult(
        taskId = task.taskId.value,
        agentId = task.assignedAgentId ?: "wasti_executive_agent",
        role = task.assignedRole ?: AgentRole.EXECUTIVE,
        state = AgentTaskState.COMPLETED,
        output = "Completed by test"
    )
}
