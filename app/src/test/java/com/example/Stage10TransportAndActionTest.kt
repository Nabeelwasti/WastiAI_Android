package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.action.WastiAppAction
import com.example.data.action.WastiAppActionBus
import com.example.data.agent.runtime.UnifiedExecutionFabric
import com.example.data.agent.runtime.UnifiedExecutionRequest
import com.example.data.agent.runtime.UnifiedExecutionStatus
import com.example.data.agent.runtime.UnifiedVerificationStatus
import com.example.data.bridge.WastiNativeBridgeManager
import com.example.data.memory.ExecutionMemoryRecorder
import com.example.data.memory.ExecutionRecord
import com.example.data.node.ExecutionDestination
import com.example.data.node.NodePlatform
import com.example.data.node.WastiNodeManager
import com.example.data.server.LocalServerState
import com.example.data.server.WastiLocalServerManager
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Stage10TransportAndActionTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun testActionBusDispatchAndReceive() = runBlocking {
        var receivedAction: WastiAppAction? = null
        val job = launch {
            WastiAppActionBus.actions.collect { action -> receivedAction = action }
        }
        WastiAppActionBus.dispatch(WastiAppAction.NavigateTo("projects"))
        kotlinx.coroutines.delay(50)
        assertNotNull(receivedAction)
        assertTrue(receivedAction is WastiAppAction.NavigateTo)
        assertEquals("projects", (receivedAction as WastiAppAction.NavigateTo).destinationId)
        job.cancel()
    }

    @Test
    fun testLocalServerLifecycle() {
        val serverManager = WastiLocalServerManager(context)
        val startResult = serverManager.startServer(18080)
        assertTrue(startResult.isSuccess)
        assertEquals(LocalServerState.RUNNING, serverManager.serverInfo.value.state)
        assertEquals(18080, serverManager.serverInfo.value.port)

        val reality = UnifiedExecutionFabric.instance.realityRegistry.getCapabilityReality("LOCAL_SERVER")
        assertNotNull(reality)
        assertEquals("LOCAL_SERVER", reality?.capabilityId)

        val healthConn = java.net.URL("http://127.0.0.1:18080/health").openConnection() as java.net.HttpURLConnection
        healthConn.requestMethod = "GET"
        assertEquals(200, healthConn.responseCode)
        val healthBody = healthConn.inputStream.bufferedReader().readText()
        assertTrue(healthBody.contains("\"status\":\"UP\""))
        assertTrue(healthBody.contains("\"brain\":\"OPERATIONAL\""))

        val statusConn = java.net.URL("http://127.0.0.1:18080/status").openConnection() as java.net.HttpURLConnection
        statusConn.requestMethod = "GET"
        assertEquals(200, statusConn.responseCode)
        assertTrue(statusConn.inputStream.bufferedReader().readText().contains("\"system\":\"WastiAI OS\""))

        val capConn = java.net.URL("http://127.0.0.1:18080/capabilities").openConnection() as java.net.HttpURLConnection
        capConn.requestMethod = "GET"
        assertEquals(200, capConn.responseCode)
        assertTrue(capConn.inputStream.bufferedReader().readText().contains("capabilities"))

        val execConn = java.net.URL("http://127.0.0.1:18080/execution").openConnection() as java.net.HttpURLConnection
        execConn.requestMethod = "GET"
        assertEquals(200, execConn.responseCode)
        assertTrue(execConn.inputStream.bufferedReader().readText().contains("isBusy"))

        val stopApiConn = java.net.URL("http://127.0.0.1:18080/emergency-stop").openConnection() as java.net.HttpURLConnection
        stopApiConn.requestMethod = "POST"
        stopApiConn.doOutput = true
        stopApiConn.outputStream.write("{\"reason\":\"Test stop\"}".toByteArray())
        assertEquals(200, stopApiConn.responseCode)
        assertTrue(stopApiConn.inputStream.bufferedReader().readText().contains("\"isEmergencyStopped\":true"))
        com.example.data.di.WastiServiceLocator.emergencyStopController.resetEmergencyStop()

        val stopResult = serverManager.stopServer("Test complete")
        assertTrue(stopResult.isSuccess)
        assertEquals(LocalServerState.STOPPED, serverManager.serverInfo.value.state)
    }

    @Test
    fun testNodeManagerRouting() {
        val nodeManager = WastiNodeManager.getInstance()
        val allNodes = nodeManager.getAllNodes()
        assertTrue(allNodes.isNotEmpty())
        assertNotNull(allNodes.find { it.platform == NodePlatform.ANDROID })
        assertEquals(ExecutionDestination.PYTHON_RUNTIME, nodeManager.routeTaskToOptimalNode("python_runtime"))
        assertEquals(ExecutionDestination.CLOUD, nodeManager.routeTaskToOptimalNode("deep_research"))
        assertEquals(ExecutionDestination.TERMUX, nodeManager.routeTaskToOptimalNode("termux_cli"))
        assertEquals(ExecutionDestination.SANDBOX, nodeManager.routeTaskToOptimalNode("wasti_sandbox"))
        assertEquals(ExecutionDestination.LOCAL_DEVICE, nodeManager.routeTaskToOptimalNode("system_info"))
    }

    @Test
    fun testNativeBridgeManagerExecution() = runBlocking {
        val bridgeManager = WastiNativeBridgeManager.getInstance(context)
        val pythonResult = bridgeManager.executePythonScript("print('Wasti AI Stage 10 Native Bridge')")
        assertNotNull(pythonResult)
        assertNotNull(pythonResult.bridgeType)
        val termuxResult = bridgeManager.executeTermuxCommand("echo 'Hello Termux'")
        assertNotNull(termuxResult)
        assertEquals("TERMUX_CLI_BRIDGE", termuxResult.bridgeType)
    }

    @Test
    fun testExecutionMemoryRecorder() = runBlocking {
        val record = ExecutionRecord("test_task_101", "Verify Stage 10 Architecture", "VERIFY_STAGE_10", "LOCAL_SERVER", true, "Test verified successfully")
        ExecutionMemoryRecorder.recordExecutionOutcome(record)
        val recent = ExecutionMemoryRecorder.getRecentExecutions(5)
        assertTrue(recent.isNotEmpty())
        val found = recent.find { it.taskId == "test_task_101" }
        assertNotNull(found)
        assertEquals("VERIFY_STAGE_10", found?.interpretedIntent)
    }

    @Test
    fun testUnifiedExecutionFabricNavigation() = runBlocking {
        val navRes = UnifiedExecutionFabric.instance.execute(
            UnifiedExecutionRequest(capabilityId = "navigate_to", parameters = mapOf("destination" to "terminal")),
            context
        )
        assertEquals("Nav execution failed: status=${navRes.status}, output=${navRes.output}, error=${navRes.error}", UnifiedExecutionStatus.COMPLETED, navRes.status)
        assertEquals(UnifiedVerificationStatus.UNVERIFIED, navRes.verificationStatus)
        assertTrue("Nav output missing terminal: ${navRes.output}", navRes.output.contains("terminal"))
    }

    @Test
    fun testUnifiedExecutionFabricLocalServer() = runBlocking {
        val manager = WastiLocalServerManager(context)
        val start = manager.startServer(18081)
        assertTrue(start.isSuccess)
        try {
            val serverRes = UnifiedExecutionFabric.instance.execute(
                UnifiedExecutionRequest(capabilityId = "local_server", parameters = mapOf("action" to "status")),
                context
            )
            assertEquals("Server status execution failed: status=${serverRes.status}, output=${serverRes.output}, error=${serverRes.error}", UnifiedExecutionStatus.COMPLETED, serverRes.status)
            assertEquals(UnifiedVerificationStatus.UNVERIFIED, serverRes.verificationStatus)
            assertTrue("Server output missing Local Server Status: ${serverRes.output}", serverRes.output.contains("Local Server Status"))
        } finally {
            com.example.data.di.WastiServiceLocator.emergencyStopController.resetEmergencyStop()
            manager.stopServer("Test complete")
        }
    }
}
