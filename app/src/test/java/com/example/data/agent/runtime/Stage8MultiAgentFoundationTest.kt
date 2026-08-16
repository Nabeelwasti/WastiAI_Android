package com.example.data.agent.runtime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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
    fun testSubAgentCatalogDefaults() {
        val agents = WastiSubAgentCatalog.defaultSubAgents
        assertTrue(agents.isNotEmpty())

        val codingAgent = coordinator.selectAgentForRole(AgentRole.CODING)
        assertNotNull(codingAgent)
        assertEquals("Coding Architect", codingAgent!!.name)

        val testingAgent = coordinator.selectAgentForRole(AgentRole.TESTING)
        assertNotNull(testingAgent)
        assertEquals("Testing & Verification Specialist", testingAgent!!.name)

        val securityAgent = coordinator.selectAgentForRole(AgentRole.SECURITY)
        assertNotNull(securityAgent)
        assertEquals(0.0f, securityAgent!!.temperature, 0.01f)
    }

    @Test
    fun testTaskCreationAndAgentSelection() {
        val task = coordinator.createTask(
            title = "Analyze Compiler Error",
            description = "Diagnose syntax error in workspace script",
            requiredCapabilities = listOf("debug_project")
        )

        assertNotNull(task)
        assertEquals(AgentRole.DEBUGGING, task.assignedRole)
        assertEquals("wasti_debugging_agent", task.assignedAgentId)
        assertEquals(AgentTaskState.SCHEDULED, task.state)
    }

    @Test
    fun testTaskDependencyResolutionAndScheduling() {
        val task1 = coordinator.createTask(
            title = "Build Project",
            description = "Build the workspace project",
            assignedRole = AgentRole.CODING,
            priority = AgentTaskPriority.HIGH
        )

        val task2 = coordinator.createTask(
            title = "Run Unit Tests",
            description = "Run tests on built artifact",
            assignedRole = AgentRole.TESTING,
            dependencies = listOf(task1.taskId.value),
            priority = AgentTaskPriority.MEDIUM
        )

        assertEquals(AgentTaskState.SCHEDULED, coordinator.getTask(task1.taskId.value)?.state)
        assertEquals(AgentTaskState.PENDING, coordinator.getTask(task2.taskId.value)?.state)

        // Ready tasks should only contain task1
        val readyBefore = coordinator.getReadyTasks()
        assertTrue(readyBefore.any { it.taskId == task1.taskId })
        assertFalse(readyBefore.any { it.taskId == task2.taskId })

        // Record completion for task 1
        coordinator.recordResult(
            AgentResult(
                taskId = task1.taskId.value,
                agentId = "wasti_coding_agent",
                role = AgentRole.CODING,
                state = AgentTaskState.COMPLETED,
                output = "Build verified successfully",
                executionTimeMs = 120L
            )
        )

        // Now task2 should be scheduled and ready
        assertEquals(AgentTaskState.SCHEDULED, coordinator.getTask(task2.taskId.value)?.state)
        val readyAfter = coordinator.getReadyTasks()
        assertTrue(readyAfter.any { it.taskId == task2.taskId })
    }

    @Test
    fun testDelegationAndSharedContext() {
        val parentTask = coordinator.createTask(
            title = "Implement Feature",
            description = "Full feature lifecycle",
            assignedRole = AgentRole.EXECUTIVE
        )

        val subTask = coordinator.createTask(
            title = "Security Audit",
            description = "Verify boundary constraints",
            assignedRole = AgentRole.CODING
        )

        val delegation = coordinator.delegateTask(
            parentTaskId = parentTask.taskId.value,
            subTask = subTask,
            sourceAgentId = "wasti_executive_agent",
            targetRole = AgentRole.SECURITY,
            reason = "Privileged operation requires security review",
            sharedContextKeys = listOf("policy_level")
        )

        assertEquals("wasti_executive_agent", delegation.sourceAgentId)
        assertEquals(AgentRole.SECURITY, delegation.targetRole)
        assertEquals(AgentRole.SECURITY, coordinator.getTask(subTask.taskId.value)?.assignedRole)

        // Shared context verification
        coordinator.sharedContext.set("policy_level", "STRICT")
        assertEquals("STRICT", coordinator.sharedContext.get("policy_level"))
        assertTrue(coordinator.sharedContext.getLogs().isNotEmpty())
    }

    @Test
    fun testTaskCancellationAndDependencyBlocking() {
        val taskA = coordinator.createTask(
            title = "Task A",
            description = "Root task"
        )
        val taskB = coordinator.createTask(
            title = "Task B",
            description = "Dependent on A",
            dependencies = listOf(taskA.taskId.value)
        )

        coordinator.cancelTask(taskA.taskId.value, "User aborted operation")

        assertEquals(AgentTaskState.CANCELLED, coordinator.getTask(taskA.taskId.value)?.state)
        assertEquals(AgentTaskState.BLOCKED, coordinator.getTask(taskB.taskId.value)?.state)
    }

    @Test
    fun testSelfCycleDetection() {
        val taskId = "task_self_cycle_test"
        val cycle = coordinator.detectCycle(taskId, listOf(taskId))
        assertNotNull(cycle)
        assertEquals(listOf(taskId, taskId), cycle)

        // When creating a task with self in dependencies
        val selfCycleTask = coordinator.createTask(
            title = "Self Cycle Task",
            description = "Depends on itself",
            dependencies = listOf("will_depend_on_self")
        )
        // Check cycle detection directly on coordinator
        val detected = coordinator.detectCycle(selfCycleTask.taskId.value, listOf(selfCycleTask.taskId.value))
        assertNotNull(detected)
        assertEquals(listOf(selfCycleTask.taskId.value, selfCycleTask.taskId.value), detected)
    }

    @Test
    fun testABACycleDetection() {
        val taskA = coordinator.createTask(
            title = "Task A",
            description = "First task"
        )
        val taskB = coordinator.createTask(
            title = "Task B",
            description = "Second task depending on A",
            dependencies = listOf(taskA.taskId.value)
        )

        // Now attempt to make taskA depend on taskB -> A -> B -> A cycle
        val cycle = coordinator.detectCycle(taskA.taskId.value, listOf(taskB.taskId.value))
        assertNotNull(cycle)
        assertTrue(cycle!!.contains(taskA.taskId.value))
        assertTrue(cycle.contains(taskB.taskId.value))
        assertEquals(taskA.taskId.value, cycle.first())
        assertEquals(taskA.taskId.value, cycle.last())

        // Creating a new task that introduces A -> B -> A cycle
        val cyclicTask = coordinator.createTask(
            title = "Task A Cyclic",
            description = "Cyclic update",
            dependencies = listOf(taskB.taskId.value)
        )
        // If taskB depends on taskA, creating task with taskId = taskA.taskId.value and dep = taskB creates cycle
        val cyclePath = coordinator.detectCycle(taskA.taskId.value, listOf(taskB.taskId.value))
        assertNotNull(cyclePath)
    }

    @Test
    fun testABCACycleDetection() {
        val taskA = coordinator.createTask(title = "Task A", description = "Root")
        val taskB = coordinator.createTask(title = "Task B", description = "Dep A", dependencies = listOf(taskA.taskId.value))
        val taskC = coordinator.createTask(title = "Task C", description = "Dep B", dependencies = listOf(taskB.taskId.value))

        // A depends on C -> A -> B -> C -> A
        val cycle = coordinator.detectCycle(taskA.taskId.value, listOf(taskC.taskId.value))
        assertNotNull(cycle)
        assertEquals(listOf(taskA.taskId.value, taskC.taskId.value, taskB.taskId.value, taskA.taskId.value), cycle)
    }

    @Test
    fun testSelfDelegationPrevention() {
        val task = coordinator.createTask(
            title = "Coding Task",
            description = "Write some code",
            assignedRole = AgentRole.CODING
        )

        // wasti_coding_agent attempts to delegate to CODING role (which resolves to wasti_coding_agent)
        val delegation = coordinator.delegateTask(
            parentTaskId = null,
            subTask = task,
            sourceAgentId = "wasti_coding_agent",
            targetRole = AgentRole.CODING,
            reason = "Delegate to self"
        )

        assertTrue(delegation.reason.startsWith("REJECTED"))
        val subTaskState = coordinator.getTask(task.taskId.value)?.state
        assertEquals(AgentTaskState.FAILED, subTaskState)

        val result = coordinator.getResult(task.taskId.value)
        assertNotNull(result)
        assertEquals(AgentTaskState.FAILED, result?.state)
        assertTrue(result?.error?.contains("Self-delegation forbidden") == true)
    }

    @Test
    fun testRecursiveDelegationLoopPrevention() {
        // Lineage: Executive -> Coding -> Testing -> Coding (Loop: Coding -> Testing -> Coding)
        val parentTask1 = coordinator.createTask(
            title = "Root Task",
            description = "Root",
            assignedRole = AgentRole.EXECUTIVE
        )

        val subTask1 = coordinator.createTask(
            title = "SubTask 1",
            description = "Coding step",
            assignedRole = AgentRole.CODING
        )
        val del1 = coordinator.delegateTask(
            parentTaskId = parentTask1.taskId.value,
            subTask = subTask1,
            sourceAgentId = "wasti_executive_agent",
            targetRole = AgentRole.CODING,
            reason = "First delegation"
        )
        assertFalse(del1.reason.startsWith("REJECTED"))

        val subTask2 = coordinator.createTask(
            title = "SubTask 2",
            description = "Testing step",
            assignedRole = AgentRole.TESTING
        )
        val del2 = coordinator.delegateTask(
            parentTaskId = subTask1.taskId.value,
            subTask = subTask2,
            sourceAgentId = "wasti_coding_agent",
            targetRole = AgentRole.TESTING,
            reason = "Second delegation"
        )
        assertFalse(del2.reason.startsWith("REJECTED"))

        // Now Testing attempts to delegate back to Coding (or Executive) -> Recursive loop
        val subTask3 = coordinator.createTask(
            title = "SubTask 3",
            description = "Loop back to coding",
            assignedRole = AgentRole.CODING
        )
        val del3 = coordinator.delegateTask(
            parentTaskId = subTask2.taskId.value,
            subTask = subTask3,
            sourceAgentId = "wasti_testing_agent",
            targetRole = AgentRole.CODING,
            reason = "Loop back delegation"
        )

        assertTrue(del3.reason.startsWith("REJECTED"))
        assertEquals(AgentTaskState.FAILED, coordinator.getTask(subTask3.taskId.value)?.state)
        val result = coordinator.getResult(subTask3.taskId.value)
        assertNotNull(result)
        assertTrue(result?.error?.contains("Recursive delegation loop") == true)
    }

    @Test
    fun testMaxDelegationDepthExceeded() {
        val shortDepthCoordinator = AgentTaskCoordinator(fabric, maxDelegationDepth = 2)

        val root = shortDepthCoordinator.createTask(title = "Root", description = "Root", assignedRole = AgentRole.EXECUTIVE)

        // Depth 1: Executive -> Research
        val task1 = shortDepthCoordinator.createTask(title = "Step 1", description = "Research")
        val del1 = shortDepthCoordinator.delegateTask(
            parentTaskId = root.taskId.value,
            subTask = task1,
            sourceAgentId = "wasti_executive_agent",
            targetRole = AgentRole.RESEARCH,
            reason = "Depth 1"
        )
        assertFalse(del1.reason.startsWith("REJECTED"))
        assertEquals(1, del1.depth)

        // Depth 2: Research -> Coding
        val task2 = shortDepthCoordinator.createTask(title = "Step 2", description = "Coding")
        val del2 = shortDepthCoordinator.delegateTask(
            parentTaskId = task1.taskId.value,
            subTask = task2,
            sourceAgentId = "wasti_research_agent",
            targetRole = AgentRole.CODING,
            reason = "Depth 2"
        )
        assertFalse(del2.reason.startsWith("REJECTED"))
        assertEquals(2, del2.depth)

        // Depth 3: Coding -> Security (Should EXCEED limit of 2)
        val task3 = shortDepthCoordinator.createTask(title = "Step 3", description = "Security")
        val del3 = shortDepthCoordinator.delegateTask(
            parentTaskId = task2.taskId.value,
            subTask = task3,
            sourceAgentId = "wasti_coding_agent",
            targetRole = AgentRole.SECURITY,
            reason = "Depth 3"
        )

        assertTrue(del3.reason.startsWith("REJECTED"))
        assertEquals(AgentTaskState.FAILED, shortDepthCoordinator.getTask(task3.taskId.value)?.state)
        val result = shortDepthCoordinator.getResult(task3.taskId.value)
        assertNotNull(result)
        assertTrue(result?.error?.contains("Maximum delegation depth") == true)
    }

    @Test
    fun testUnifiedExecutionFabricRouting() = kotlinx.coroutines.runBlocking {
        val task = coordinator.createTask(
            title = "Inspect Environment",
            description = "Run reality inspection",
            assignedRole = AgentRole.EXECUTIVE,
            requiredCapabilities = listOf("system_info")
        )

        val result = coordinator.executeTask(task.taskId.value)
        assertNotNull(result)
        assertEquals(task.taskId.value, result.taskId)
        assertEquals(AgentTaskState.COMPLETED, result.state)
        assertTrue(result.evidence?.contains("UnifiedExecutionFabric") == true)
        assertEquals(AgentTaskState.COMPLETED, coordinator.getTask(task.taskId.value)?.state)
    }
}
