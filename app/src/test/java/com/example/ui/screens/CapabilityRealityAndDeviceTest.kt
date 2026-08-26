package com.example.ui.screens

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.agent.runtime.*
import com.example.data.device.WastiDeviceController
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.runBlocking

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class CapabilityRealityAndDeviceTest {

    private lateinit var context: Context
    private lateinit var fabric: UnifiedExecutionFabric
    private lateinit var registry: CapabilityRealityRegistry

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        registry = CapabilityRealityRegistry()
        fabric = UnifiedExecutionFabric(realityRegistry = registry, appContext = context)
    }

    @Test
    fun testCapabilityRealityRegistryReport() {
        val report = registry.getSystemRealityReport()
        assertNotNull(report)
        assertTrue(report.isNotEmpty())
        
        // Verify key capability categories are registered
        val categories = report.map { it.category }.distinct()
        assertTrue(categories.contains("EXECUTION"))
        assertTrue(categories.contains("STORAGE"))
        assertTrue(categories.contains("SECURITY"))
    }

    @Test
    fun testUnifiedExecutionFabricWasmSandboxedExecution() = runBlocking {
        val req = UnifiedExecutionRequest(
            capabilityId = "TERMINAL",
            parameters = mapOf("action" to "execute_code", "language" to "wasm")
        )
        val result = fabric.execute(req, context)
        assertNotNull(result)
        assertEquals(UnifiedVerificationStatus.VERIFIED, result.verificationStatus)
        assertEquals(UnifiedExecutionStatus.COMPLETED, result.status)
        assertTrue(result.output.contains("WASM") || result.output.contains("Fuel"))
    }

    @Test
    fun testUnifiedExecutionFabricMemorySearch() = runBlocking {
        val req = UnifiedExecutionRequest(
            capabilityId = "MEMORY_SEARCH",
            parameters = mapOf("query" to "Wasti AI")
        )
        val result = fabric.execute(req, context)
        assertNotNull(result)
        assertEquals(UnifiedVerificationStatus.VERIFIED, result.verificationStatus)
        assertEquals(UnifiedExecutionStatus.COMPLETED, result.status)
    }

    @Test
    fun testDeviceControllerTruthfulStateWhenInactive() {
        // Without active accessibility service instance attached, must truthfully report INACTIVE / FALSE
        val backResult = WastiDeviceController.performBack(context)
        assertFalse(backResult.success)
        assertEquals("SERVICE_INACTIVE", backResult.commandType)

        val homeResult = WastiDeviceController.performHome(context)
        assertFalse(homeResult.success)
        assertEquals("SERVICE_INACTIVE", homeResult.commandType)

        val typeResult = WastiDeviceController.typeText(context, "Test Message")
        assertFalse(typeResult.success)
        assertEquals("SERVICE_INACTIVE", typeResult.commandType)
    }
}
