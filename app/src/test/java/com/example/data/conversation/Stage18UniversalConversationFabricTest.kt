package com.example.data.conversation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.agent.runtime.*
import com.example.data.core.CommandOrigin
import com.example.data.core.CommandSubmissionResult
import com.example.data.core.WastiOSRuntime
import com.example.data.di.WastiServiceLocator
import com.example.data.node.WastiNodeManager
import com.example.data.transport.WastiCommandTransport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class Stage18UniversalConversationFabricTest {

    private lateinit var context: Context
    private lateinit var fabric: UniversalConversationFabric
    private lateinit var eventBus: AgentEventBus
    private lateinit var emergencyStopController: WastiEmergencyStopController
    private lateinit var commandTransport: WastiCommandTransport
    private lateinit var runtime: WastiOSRuntime

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        WastiServiceLocator.init(context)
        
        eventBus = WastiServiceLocator.agentEventBus
        emergencyStopController = WastiServiceLocator.emergencyStopController
        commandTransport = WastiServiceLocator.commandTransport
        runtime = WastiServiceLocator.wastiOSRuntime

        // Reset emergency stop state
        emergencyStopController.resetEmergencyStop()

        fabric = UniversalConversationFabric.getInstance(context)
        fabric.clearAll()
    }

    @After
    fun tearDown() {
        fabric.clearAll()
        emergencyStopController.resetEmergencyStop()
    }

    @Test
    fun testRoomIdentityMapping() {
        assertEquals(RoomIdentity.CHAT, RoomIdentity.fromRoomId("CHAT"))
        assertEquals(RoomIdentity.TERMINAL, RoomIdentity.fromRoomId("TERMINAL"))
        assertEquals(RoomIdentity.FLOATING_BUBBLE, RoomIdentity.fromRoomId("FLOATING_BUBBLE"))
        assertEquals(RoomIdentity.DEV_ASSISTANT, RoomIdentity.fromRoomId("DEV_ASSISTANT"))
        assertEquals(RoomIdentity.WEB_COMPANION, RoomIdentity.fromRoomId("WEB_COMPANION"))
        assertEquals(RoomIdentity.DESKTOP_COMPANION, RoomIdentity.fromRoomId("DESKTOP_COMPANION"))
        assertEquals(RoomIdentity.CHAT, RoomIdentity.fromRoomId("UNKNOWN_ROOM"))
    }

    @Test
    fun testRoomSwitchingPreservesConversationContext() {
        val initial = fabric.activeContext.value
        assertEquals("CHAT", initial.currentRoom)

        val switched = fabric.switchRoom("TERMINAL")
        assertEquals("TERMINAL", switched.currentRoom)
        assertEquals("TERMINAL", fabric.activeContext.value.currentRoom)
        assertEquals(initial.conversationId, fabric.activeContext.value.conversationId)
    }

    @Test
    fun testSubmitTaskAcrossRooms() = runTest {
        val prompt = "Inspect repository health and list active nodes"
        val submission = fabric.submitTask(
            prompt = prompt,
            originRoom = "TERMINAL"
        )

        assertNotNull(submission)
        assertTrue(submission !is CommandSubmissionResult.Rejected)

        val ctx = fabric.activeContext.value
        assertEquals("TERMINAL", ctx.currentRoom)
        assertEquals(prompt, ctx.lastUserInteraction)
        assertNotNull(ctx.taskId)
        assertEquals(1, ctx.conversationHistory.size)
        assertEquals(prompt, ctx.conversationHistory.first().content)
        assertEquals("user", ctx.conversationHistory.first().role)
    }

    @Test
    fun testCrossRoomContinuationMaintainsContext() = runTest {
        // Step 1: Submit initial task in CHAT
        val initialPrompt = "Create a project workspace"
        val firstSub = fabric.submitTask(
            prompt = initialPrompt,
            originRoom = "CHAT"
        )
        assertTrue(firstSub !is CommandSubmissionResult.Rejected)
        val initialTaskId = fabric.activeContext.value.taskId
        val initialConvId = fabric.activeContext.value.conversationId

        // Step 2: Continue in DEV_ASSISTANT room
        val continuationPrompt = "Add Kotlin source files to the workspace"
        val contSub = fabric.continueConversation(
            prompt = continuationPrompt,
            originRoom = "DEV_ASSISTANT"
        )

        assertTrue(contSub !is CommandSubmissionResult.Rejected)
        val updatedCtx = fabric.activeContext.value

        assertEquals("DEV_ASSISTANT", updatedCtx.currentRoom)
        assertEquals(initialConvId, updatedCtx.conversationId)
        assertTrue(updatedCtx.continuationMetadata.containsKey("lastContinuationPrompt"))
        assertEquals(continuationPrompt, updatedCtx.continuationMetadata["lastContinuationPrompt"])
    }

    @Test
    fun testUserConfirmationRequestAndApproval() = runTest {
        val confirmation = fabric.requestConfirmation(
            actionTitle = "Delete Database Cache",
            actionDetails = "Purge all on-disk cache files",
            requiredPrivilege = "ADMIN",
            requestedByRoom = "TERMINAL"
        )

        assertNotNull(confirmation)
        assertEquals("Delete Database Cache", confirmation.actionTitle)
        assertEquals("TERMINAL", confirmation.requestedByRoom)
        assertFalse(confirmation.isResolved)

        val ctx = fabric.activeContext.value
        assertEquals(ConversationExecutionState.AWAITING_CONFIRMATION, ctx.activeExecutionState)
        assertEquals(1, ctx.activeConfirmations.size)

        // Resolve confirmation from CHAT room
        val resolved = fabric.resolveConfirmation(
            confirmationId = confirmation.confirmationId,
            approved = true,
            resolvedByRoom = "CHAT"
        )

        assertTrue(resolved)
        val resolvedCtx = fabric.activeContext.value
        assertEquals(ConversationExecutionState.EXECUTING, resolvedCtx.activeExecutionState)
        assertTrue(resolvedCtx.activeConfirmations.first().isResolved)
        assertTrue(resolvedCtx.activeConfirmations.first().approved)
        assertEquals("CHAT", resolvedCtx.activeConfirmations.first().resolvedByRoom)
    }

    @Test
    fun testUserConfirmationRejection() = runTest {
        val confirmation = fabric.requestConfirmation(
            actionTitle = "Format External Storage",
            actionDetails = "Wipe external storage volume",
            requiredPrivilege = "SUPERUSER",
            requestedByRoom = "SETTINGS"
        )

        val resolved = fabric.resolveConfirmation(
            confirmationId = confirmation.confirmationId,
            approved = false,
            resolvedByRoom = "FLOATING_BUBBLE",
            reason = "User denied action from floating overlay"
        )

        assertTrue(resolved)
        val resolvedCtx = fabric.activeContext.value
        assertEquals(ConversationExecutionState.CANCELLED, resolvedCtx.activeExecutionState)
        assertTrue(resolvedCtx.activeConfirmations.first().isResolved)
        assertFalse(resolvedCtx.activeConfirmations.first().approved)
        assertEquals("FLOATING_BUBBLE", resolvedCtx.activeConfirmations.first().resolvedByRoom)
    }

    @Test
    fun testSuspendForConfirmationFlow() = runTest {
        val confirmation = fabric.requestConfirmation(
            actionTitle = "Deploy Cloud Artifact",
            actionDetails = "Push binary to target server",
            requiredPrivilege = "DEPLOY",
            requestedByRoom = "DEV_ASSISTANT"
        )

        // Launch concurrent resolver
        val job = launch {
            fabric.resolveConfirmation(
                confirmationId = confirmation.confirmationId,
                approved = true,
                resolvedByRoom = "CHAT"
            )
        }

        val approved = fabric.suspendForConfirmation(confirmation.confirmationId, timeoutMs = 5000L)
        job.join()
        assertTrue(approved)
    }

    @Test
    fun testEmergencyStopPreventsTaskSubmission() {
        fabric.triggerEmergencyStop("Test Emergency Stop")
        assertEquals(ConversationExecutionState.EMERGENCY_STOPPED, fabric.activeContext.value.activeExecutionState)

        val rejected = fabric.submitTask("Try executing while stopped", "CHAT")
        assertTrue(rejected is CommandSubmissionResult.Rejected)
        assertTrue((rejected as CommandSubmissionResult.Rejected).reason.contains("EMERGENCY_STOP_ACTIVE"))
    }

    @Test
    fun testTaskLifecyclePauseResumeCancel() {
        fabric.submitTask("Long running background compilation", "CHAT")
        assertEquals(ConversationExecutionState.PLANNING, fabric.activeContext.value.activeExecutionState)

        fabric.pauseActiveTask("User paused")
        assertEquals(ConversationExecutionState.PAUSED, fabric.activeContext.value.activeExecutionState)

        fabric.resumeActiveTask()
        assertEquals(ConversationExecutionState.EXECUTING, fabric.activeContext.value.activeExecutionState)

        fabric.cancelActiveTask("Stop execution", "TERMINAL")
        assertEquals(ConversationExecutionState.CANCELLED, fabric.activeContext.value.activeExecutionState)
    }

    @Test
    fun testCompanionSnapshotGeneration() {
        fabric.submitTask("Generate dashboard metrics", "DASHBOARD")
        fabric.requestConfirmation("Export Report", "Export to PDF", "EXPORT", "DASHBOARD")

        val snapshot = fabric.getCompanionSnapshot()

        assertNotNull(snapshot)
        assertEquals("DASHBOARD", snapshot.conversationContext.currentRoom)
        assertEquals(1, snapshot.pendingConfirmations.size)
        assertTrue(snapshot.availableActions.contains("SUBMIT_TASK"))
        assertTrue(snapshot.availableActions.contains("CONFIRM"))
        assertTrue(snapshot.availableActions.contains("REJECT"))
        assertTrue(snapshot.availableActions.contains("EMERGENCY_STOP"))
    }
}
