package com.example.data.agent.runtime

import com.example.data.sandbox.WastiWasmRuntime
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class Stage21WasmAndActionFabricTest {

    @Test
    fun testWasmRuntimeBasicExecution() {
        val runtime = WastiWasmRuntime()
        val result = runtime.runSandboxedScript(
            toolName = "math_square",
            expression = "const x = 5; x * x;",
            params = mapOf("input" to "5")
        )

        assertTrue(result.isSuccess)
        assertNotNull(result.returnValue)
        assertTrue(result.fuelConsumed > 0)
        assertTrue(result.executionTimeMs >= 0)
    }

    @Test
    fun testWasmRuntimeStatusReporting() {
        val runtime = WastiWasmRuntime()
        val status = runtime.getRuntimeStatus()

        assertEquals("OPERATIONAL", status["status"])
        assertTrue(status.containsKey("loadedModulesCount"))
        assertTrue(status.containsKey("totalExecutions"))
        assertTrue(status.containsKey("totalFuelUsed"))
    }

    @Test
    fun testWasmSandboxIntegrationAdapter() {
        val adapter = WasmSandboxIntegrationAdapter()
        assertEquals("WASM_SANDBOX", adapter.capabilityId)
        assertEquals(CapabilityAuthStatus.AUTHENTICATED, adapter.getAuthState())
        assertEquals(LiveConnectionStatus.VERIFIED, adapter.getLiveVerificationState())

        val result = adapter.execute(
            action = "RUN_TOOL",
            params = mapOf("toolName" to "test_tool", "expression" to "return 42;")
        )

        assertEquals(ExternalActionResultStatus.SUCCESS, result.status)
        assertTrue(result.diagnosticMessage.contains("successfully"))
    }

    @Test
    fun testActionIntentEngineDynamicDispatch() {
        val engine = ActionIntentEngine()
        val wasmAdapter = WasmSandboxIntegrationAdapter()
        engine.registerAdapter(wasmAdapter)

        val intent = engine.prepareActionIntent(
            target = "WASM_SANDBOX",
            intent = "RUN_TOOL",
            payload = mapOf("toolName" to "calc", "expression" to "5+5"),
            previewText = "Run WASM Tool calc"
        )

        engine.authorizeAction(intent, userApproved = true)
        val res = engine.executeAction(intent, wasmAdapter)
        assertEquals(ActionAuthorizationState.SUCCEEDED, res.authorizationState)
        assertEquals(LiveConnectionStatus.VERIFIED, res.verificationState)
    }

    @Test
    fun testCapabilityRealityRegistryDefaults() {
        val registry = CapabilityRealityRegistry()
        val report = registry.getSystemRealityReport()

        assertTrue(report.isNotEmpty())
        val filesCap = registry.get("FILES")
        assertNotNull(filesCap)
        assertEquals(LiveConnectionStatus.NOT_VERIFIED, filesCap?.liveConnectionStatus)
        assertEquals(CapabilityRealityState.IMPLEMENTED_NOT_LIVE_VERIFIED, filesCap?.realityState)
    }
}
