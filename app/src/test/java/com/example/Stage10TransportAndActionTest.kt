package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.action.WastiAppAction
import com.example.data.action.WastiAppActionBus
import com.example.data.agent.runtime.UnifiedExecutionFabric
import com.example.data.agent.runtime.UnifiedExecutionRequest
import com.example.data.agent.runtime.UnifiedExecutionStatus
import com.example.data.bridge.WastiNativeBridgeManager
import com.example.data.memory.ExecutionMemoryRecorder
import com.example.data.memory.ExecutionRecord
import com.example.data.node.ExecutionDestination
import com.example.data.node.NodePlatform
import com.example.data.node.WastiNode
import com.example.data.node.WastiNodeManager
import com.example.data.server.LocalServerState
import com.example.data.server.WastiLocalServerManager
import kotlinx.coroutines.flow.first
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
            WastiAppActionBus.actions.collect { action ->
                receivedAction = action
            }
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

        // 1. Test GET /health
        val healthUrl = java.net.URL("http://127.0.0.1:18080/health")
        val healthConn = healthUrl.openConnection() as java.net.HttpURLConnection
        healthConn.requestMethod = "GET"
        assertEquals(200, healthConn.responseCode)
        val healthBody = healthConn.inputStream.bufferedReader().readText()
        assertTrue(healthBody.contains("\"status\":\"UP\""))
        assertTrue(healthBody.contains("\"brain\":\"OPERATIONAL\""))

        // 2. Test GET /status
        val statusUrl = java.net.URL("http://127.0.0.1:18080/status")
        val statusConn = statusUrl.openConnection() as java.net.HttpURLConnection
        statusConn.requestMethod = "GET"
        assertEquals(200, statusConn.responseCode)
        val statusBody = statusConn.inputStream.bufferedReader().readText()
        assertTrue(statusBody.contains("\"system\":\"WastiAI OS\""))

        // 3. Test GET /capabilities
        val capUrl = java.net.URL("http://127.0.0.1:18080/capabilities")
        val capConn = capUrl.openConnection() as java.net.HttpURLConnection
        capConn.requestMethod = "GET"
        assertEquals(200, capConn.responseCode)
        val capBody = capConn.inputStream.bufferedReader().readText()
        assertTrue(capBody.contains("capabilities"))

        // 4. Test GET /execution
        val execUrl = java.net.URL("http://127.0.0.1:18080/execution")
        val execConn = execUrl.openConnection() as java.net.HttpURLConnection
        execConn.requestMethod = "GET"
        assertEquals(200, execConn.responseCode)
        val execBody = execConn.inputStream.bufferedReader().readText()
        assertTrue(execBody.contains("isBusy"))

        // 5. Test POST /emergency-stop
        val stopApiUrl = java.net.URL("http://127.0.0.1:18080/emergency-stop")
        val stopApiConn = stopApiUrl.openConnection() as java.net.HttpURLConnection
        stopApiConn.requestMethod = "POST"
        stopApiConn.doOutput = true
        stopApiConn.outputStream.write("{\"reason\":\"Test stop\"}".toByteArray())
        assertEquals(200, stopApiConn.responseCode)
        val stopApiBody = stopApiConn.inputStream.bufferedReader().readText()
        assertTrue(stopApiBody.contains("\"isEmergencyStopped\":true"))

        // Reset emergency stop for subsequent tests
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

        // Routing checks
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
        val record = ExecutionRecord(
            taskId = "test_task_101",
            goal = "Verify Stage 10 Architecture",
            interpretedIntent = "VERIFY_STAGE_10",
            selectedCapability = "LOCAL_SERVER",
            isSuccess = true,
            verificationEvidence = "Test verified successfully"
        )
        ExecutionMemoryRecorder.recordExecutionOutcome(record)

        val recent = ExecutionMemoryRecorder.getRecentExecutions(5)
        assertTrue(recent.isNotEmpty())
        val found = recent.find { it.taskId == "test_task_101" }
        assertNotNull(found)
        assertEquals("VERIFY_STAGE_10", found?.interpretedIntent)
    }

    @Test
    fun testUnifiedExecutionFabricNavigation() = runBlocking {
        val fabric = UnifiedExecutionFabric.instance

        val navReq = UnifiedExecutionRequest(
            capabilityId = "navigate_to",
            parameters = mapOf("destination" to "terminal")
        )
        val navRes = fabric.execute(navReq, context)
        assertEquals("Nav execution failed: status=${navRes.status}, output=${navRes.output}, error=${navRes.error}", UnifiedExecutionStatus.VERIFIED, navRes.status)
        assertTrue("Nav output missing terminal: ${navRes.output}", navRes.output.contains("terminal"))
    }

    @Test
    fun testUnifiedExecutionFabricLocalServer() = runBlocking {
        val fabric = UnifiedExecutionFabric.instance

        val serverReq = UnifiedExecutionRequest(
            capabilityId = "local_server",
            parameters = mapOf("action" to "status")
        )
        val serverRes = fabric.execute(serverReq, context)
        assertEquals("Server status execution failed: status=${serverRes.status}, output=${serverRes.output}, error=${serverRes.error}", UnifiedExecutionStatus.VERIFIED, serverRes.status)
        assertTrue("Server output missing Local Server Status: ${serverRes.output}", serverRes.output.contains("Local Server Status"))
    }
}
