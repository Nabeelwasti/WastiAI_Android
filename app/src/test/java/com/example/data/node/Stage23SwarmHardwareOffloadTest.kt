package com.example.data.node

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.core.TestCategory
import com.example.data.core.TestTier
import com.example.data.agent.runtime.UnifiedExecutionRequest
import com.example.data.agent.runtime.UnifiedExecutionStatus
import com.example.data.agent.runtime.UnifiedVerificationStatus
import com.example.data.di.WastiServiceLocator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Stage23SwarmHardwareOffloadTest
 *
 * Verifies the multi-device environment and hardware auto-offload capabilities
 * mandated by The Eternal Manifesto:
 * - Many Bodies Doctrine (Desktop, Laptop, PC, Termux swarm)
 * - Resource Intelligence Law (Dynamic compute placement based on device constraints)
 * - Wi-Fi & Bluetooth transport coordination
 */
@RunWith(RobolectricTestRunner::class)
@TestCategory(
    tier = TestTier.ROBOLECTRIC,
    description = "Tests for multi-device hardware discovery and autonomous heavy compute offloading"
)
class Stage23SwarmHardwareOffloadTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WastiNearbyHardwareEngine.clearForTesting()
    }

    @Test
    fun testHeavyWorkloadDetection() {
        // 1. Explicit heavy capability
        val heavyReq = UnifiedExecutionRequest(
            capabilityId = "matrix_transform",
            actionId = "compute_large_matrix"
        )
        assertTrue(AutonomousHardwareOffloader.isHeavyWorkload(heavyReq))

        // 2. High-dimensional parameters
        val paramReq = UnifiedExecutionRequest(
            capabilityId = "general_computation",
            actionId = "eval",
            parameters = mapOf("dim" to "1024")
        )
        assertTrue(AutonomousHardwareOffloader.isHeavyWorkload(paramReq))

        // 3. Lightweight request
        val lightReq = UnifiedExecutionRequest(
            capabilityId = "text_echo",
            actionId = "echo",
            parameters = mapOf("text" to "hello")
        )
        assertFalse(AutonomousHardwareOffloader.isHeavyWorkload(lightReq))
    }

    @Test
    fun testHeavyPromptDetection() {
        assertTrue(AutonomousHardwareOffloader.isHeavyPrompt("Please run heavy processing on my nearby PC"))
        assertTrue(AutonomousHardwareOffloader.isHeavyPrompt("Use my laptop hardware to compile this repository"))
        assertTrue(AutonomousHardwareOffloader.isHeavyPrompt("Offload to nearby desktop for multitasking"))
        assertFalse(AutonomousHardwareOffloader.isHeavyPrompt("What time is it in Tokyo?"))
    }

    @Test
    fun testNearbyHardwareRegistrationAndFederation() {
        val desktopNode = NearbyHardwareNode(
            nodeId = "test_pc_desktop_01",
            deviceName = "Main Workstation PC",
            hardwareType = DeviceHardwareType.DESKTOP_PC,
            transportType = HardwareTransportType.WIFI_LAN,
            platform = NodePlatform.DESKTOP,
            addressOrIp = "192.168.1.150",
            port = 35261,
            advertisedCapabilities = setOf("heavy_compute", "gpu_compute", "matrix_transform"),
            capabilityFingerprint = "fingerprint_pc_01",
            isAvailableForHeavyCompute = true,
            estimatedComputeMultiplier = 4.5f
        )

        val laptopNode = NearbyHardwareNode(
            nodeId = "test_laptop_mac_01",
            deviceName = "ThinkPad Linux Laptop",
            hardwareType = DeviceHardwareType.LAPTOP,
            transportType = HardwareTransportType.BLUETOOTH_RFCOMM,
            platform = NodePlatform.DESKTOP,
            addressOrIp = "AA:BB:CC:DD:EE:FF",
            port = 0,
            advertisedCapabilities = setOf("code_compilation", "heavy_compute"),
            capabilityFingerprint = "fingerprint_laptop_01",
            isAvailableForHeavyCompute = true,
            estimatedComputeMultiplier = 3.0f
        )

        WastiNearbyHardwareEngine.registerNodeForTesting(desktopNode)
        WastiNearbyHardwareEngine.registerNodeForTesting(laptopNode)

        val heavyNodes = WastiNearbyHardwareEngine.getHeavyComputeNodes()
        assertEquals(2, heavyNodes.size)
        // Desktop PC with 4.5x multiplier should be ranked first
        assertEquals(DeviceHardwareType.DESKTOP_PC, heavyNodes[0].hardwareType)
        assertEquals("Main Workstation PC", heavyNodes[0].deviceName)
        assertEquals(DeviceHardwareType.LAPTOP, heavyNodes[1].hardwareType)

        val state = WastiNearbyHardwareEngine.swarmState.value
        assertEquals(2, state.totalDiscoveredDevices)
        assertEquals(2, state.heavyComputeNodesAvailable)
    }

    @Test
    fun testAutonomousOffloadDecisionForHeavyTask() {
        val desktopNode = NearbyHardwareNode(
            nodeId = "desktop_node_01",
            deviceName = "High-End Linux Workstation",
            hardwareType = DeviceHardwareType.DESKTOP_PC,
            transportType = HardwareTransportType.WIFI_LAN,
            platform = NodePlatform.DESKTOP,
            addressOrIp = "192.168.1.200",
            port = 35261,
            advertisedCapabilities = setOf("gpu_compute", "matrix_transform"),
            capabilityFingerprint = "fp_linux_gpu",
            isAvailableForHeavyCompute = true,
            estimatedComputeMultiplier = 5.0f
        )
        WastiNearbyHardwareEngine.registerNodeForTesting(desktopNode)

        val heavyRequest = UnifiedExecutionRequest(
            taskId = "heavy_task_001",
            actionId = "matrix_transform",
            capabilityId = "gpu_compute",
            parameters = mapOf("dim" to "2048")
        )

        val decision = AutonomousHardwareOffloader.evaluateOffload(context, heavyRequest)

        assertTrue("Heavy compute task must trigger autonomous offload", decision.shouldOffload)
        assertNotNull("Target node must be selected", decision.targetNode)
        assertEquals("High-End Linux Workstation", decision.targetNode!!.deviceName)
        assertTrue(decision.reason.contains("Autonomous Swarm Offload"))
    }

    @Test
    fun testAutonomousOffloadExecutionAcrossMesh() = runBlocking {
        val desktopNode = NearbyHardwareNode(
            nodeId = "target_desktop_pc",
            deviceName = "Enterprise Ryzen Workstation",
            hardwareType = DeviceHardwareType.DESKTOP_PC,
            transportType = HardwareTransportType.WIFI_LAN,
            platform = NodePlatform.DESKTOP,
            addressOrIp = "127.0.0.1",
            port = 35261,
            advertisedCapabilities = setOf("matrix_transform"),
            capabilityFingerprint = "fp_ryzen_verified",
            isAvailableForHeavyCompute = true,
            estimatedComputeMultiplier = 4.5f
        )
        WastiNearbyHardwareEngine.registerNodeForTesting(desktopNode)

        // Also register in WastiMeshTransportEngine for loopback test
        WastiMeshTransportEngine.registerDiscoveredPeerForTesting(
            MeshDiscoveredNode(
                nodeId = "target_desktop_pc",
                nodeName = "Enterprise Ryzen Workstation",
                platform = NodePlatform.DESKTOP,
                ipAddress = "127.0.0.1",
                port = 35261,
                advertisedCapabilities = setOf("matrix_transform"),
                capabilityFingerprint = "fp_ryzen_verified"
            )
        )

        val request = UnifiedExecutionRequest(
            taskId = "mesh_offload_001",
            actionId = "matrix_transform",
            capabilityId = "matrix_transform",
            parameters = mapOf("matrix_size" to "4096")
        )

        val result = AutonomousHardwareOffloader.executeWithOffload(request, desktopNode, context)

        assertEquals(UnifiedExecutionStatus.COMPLETED, result.status)
        assertEquals(UnifiedVerificationStatus.VERIFIED, result.verificationStatus)
        assertTrue(result.output.contains("remote mesh body") || result.output.contains("Executed"))
        assertTrue(result.verificationEvidence!!.contains("fp_ryzen_verified"))
    }

    @Test
    fun testBluetoothOffloadExecution() = runBlocking {
        val btLaptopNode = NearbyHardwareNode(
            nodeId = "bt_laptop_01",
            deviceName = "ThinkPad P1 Gen 5",
            hardwareType = DeviceHardwareType.LAPTOP,
            transportType = HardwareTransportType.BLUETOOTH_RFCOMM,
            platform = NodePlatform.DESKTOP,
            addressOrIp = "00:11:22:33:44:55",
            port = 0,
            advertisedCapabilities = setOf("code_compilation"),
            capabilityFingerprint = "fp_bt_thinkpad",
            isAvailableForHeavyCompute = true,
            estimatedComputeMultiplier = 3.2f
        )
        WastiNearbyHardwareEngine.registerNodeForTesting(btLaptopNode)

        val request = UnifiedExecutionRequest(
            taskId = "bt_offload_001",
            actionId = "compile_kernel",
            capabilityId = "code_compilation",
            parameters = mapOf("target" to "arm64")
        )

        val result = AutonomousHardwareOffloader.executeWithOffload(request, btLaptopNode, context)

        assertEquals(UnifiedExecutionStatus.COMPLETED, result.status)
        assertEquals(UnifiedVerificationStatus.VERIFIED, result.verificationStatus)
        assertTrue(result.output.contains("Bluetooth") || result.output.contains("ThinkPad"))
        assertTrue(result.verificationEvidence!!.contains("fp_bt_thinkpad"))
    }
}
