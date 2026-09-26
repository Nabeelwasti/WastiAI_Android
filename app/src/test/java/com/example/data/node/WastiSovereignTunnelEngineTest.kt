package com.example.data.node

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.assistant.backend.BackendClient
import com.example.data.agent.runtime.ExecutionProvenanceLedger
import com.example.data.agent.runtime.WastiEmergencyStopController
import com.example.data.agent.runtime.WastiTruthAuthority
import com.example.data.core.TestCategory
import com.example.data.core.TestTier
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@TestCategory(tier = TestTier.ROBOLECTRIC)
class WastiSovereignTunnelEngineTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WastiTruthAuthority.setTestAuthorityKeyForTesting()
        ExecutionProvenanceLedger.resetForTesting()
        WastiEmergencyStopController.resetEmergencyStop()
        WastiSovereignTunnelEngine.resetForTesting()
        WastiNearbyHardwareEngine.clearForTesting()
    }

    @After
    fun tearDown() {
        WastiSovereignTunnelEngine.terminateTunnel(context)
        WastiEmergencyStopController.resetEmergencyStop()
        WastiNearbyHardwareEngine.clearForTesting()
    }

    @Test
    fun testInitialStateIsStoppedAndInactive() {
        val state = WastiSovereignTunnelEngine.tunnelState.value
        assertFalse(state.isActive)
        assertEquals(SovereignTunnelStatus.STOPPED, state.status)
        assertEquals("", state.publicHttpsUrl)
        assertFalse(state.isHealthVerified)
    }

    @Test
    fun testCloudflareTunnelUnavailableWhenBinaryMissing() = runBlocking {
        val launcher = object : TunnelProcessLauncher {
            override fun findExecutable(binaryName: String, context: Context): File? = null
            override fun launchProcess(command: List<String>, environment: Map<String, String>): Process {
                throw UnsupportedOperationException("No binary")
            }
        }
        WastiSovereignTunnelEngine.setProcessLauncherForTesting(launcher)

        val state = WastiSovereignTunnelEngine.establishTunnel(context, TunnelProvider.CLOUDFLARE_QUICK_TUNNEL)
        assertFalse(state.isActive)
        assertEquals(SovereignTunnelStatus.UNAVAILABLE, state.status)
        assertTrue(state.failureReason!!.contains("cloudflared binary not found"))
    }

    @Test
    fun testSshTunnelUnavailableWhenBinaryMissing() = runBlocking {
        val launcher = object : TunnelProcessLauncher {
            override fun findExecutable(binaryName: String, context: Context): File? = null
            override fun launchProcess(command: List<String>, environment: Map<String, String>): Process {
                throw UnsupportedOperationException("No binary")
            }
        }
        WastiSovereignTunnelEngine.setProcessLauncherForTesting(launcher)

        val state = WastiSovereignTunnelEngine.establishTunnel(context, TunnelProvider.SSH_REMOTE_FORWARD)
        assertFalse(state.isActive)
        assertEquals(SovereignTunnelStatus.UNAVAILABLE, state.status)
        assertTrue(state.failureReason!!.contains("ssh client executable not found"))
    }

    @Test
    fun testMeshRelayUnavailableWhenNoPeersDiscovered() = runBlocking {
        WastiNearbyHardwareEngine.clearForTesting()

        val state = WastiSovereignTunnelEngine.establishTunnel(context, TunnelProvider.WASTI_P2P_MESH_RELAY)
        assertFalse(state.isActive)
        assertEquals(SovereignTunnelStatus.UNAVAILABLE, state.status)
        assertTrue(state.failureReason!!.contains("No active P2P mesh relay peers"))
    }

    @Test
    fun testMeshRelayFailedWhenPeerHealthProbeFails() = runBlocking {
        val desktopPeer = NearbyHardwareNode(
            nodeId = "mesh-server-01",
            deviceName = "Linux Server",
            hardwareType = DeviceHardwareType.SERVER_WORKSTATION,
            transportType = HardwareTransportType.WIFI_LAN,
            platform = NodePlatform.SERVER,
            addressOrIp = "192.168.1.150",
            port = 8080,
            advertisedCapabilities = setOf("compute_engine", "tunnel_ingress"),
            capabilityFingerprint = "fp-mesh-server-01"
        )
        WastiNearbyHardwareEngine.registerNodeForTesting(desktopPeer)

        val prober = object : TunnelHealthProber {
            override suspend fun probeHealth(endpointUrl: String, timeoutMs: Int): HealthProbeResult {
                return HealthProbeResult(isHealthy = false, statusCode = 502, latencyMs = 25L, errorMessage = "Bad Gateway")
            }
        }
        WastiSovereignTunnelEngine.setHealthProberForTesting(prober)

        val state = WastiSovereignTunnelEngine.establishTunnel(context, TunnelProvider.WASTI_P2P_MESH_RELAY)
        assertFalse(state.isActive)
        assertEquals(SovereignTunnelStatus.FAILED, state.status)
        assertTrue(state.failureReason!!.contains("failed health check"))
    }

    @Test
    fun testMeshRelayOperationalWhenPeerRespondsHealthy() = runBlocking {
        val desktopPeer = NearbyHardwareNode(
            nodeId = "mesh-server-02",
            deviceName = "Linux Server",
            hardwareType = DeviceHardwareType.SERVER_WORKSTATION,
            transportType = HardwareTransportType.WIFI_LAN,
            platform = NodePlatform.SERVER,
            addressOrIp = "192.168.1.200",
            port = 8080,
            advertisedCapabilities = setOf("compute_engine", "tunnel_ingress"),
            capabilityFingerprint = "fp-mesh-server-02"
        )
        WastiNearbyHardwareEngine.registerNodeForTesting(desktopPeer)

        val prober = object : TunnelHealthProber {
            override suspend fun probeHealth(endpointUrl: String, timeoutMs: Int): HealthProbeResult {
                return HealthProbeResult(isHealthy = true, statusCode = 200, latencyMs = 15L)
            }
        }
        WastiSovereignTunnelEngine.setHealthProberForTesting(prober)

        val state = WastiSovereignTunnelEngine.establishTunnel(context, TunnelProvider.WASTI_P2P_MESH_RELAY)
        assertTrue(state.isActive)
        assertEquals(SovereignTunnelStatus.OPERATIONAL, state.status)
        assertEquals("http://192.168.1.200:8080/tunnel", state.publicHttpsUrl)
        assertTrue(state.isHealthVerified)
        assertEquals("http://192.168.1.200:8080/tunnel", BackendClient.getBaseUrl())
    }

    @Test
    fun testCustomGatewayRejectsBlankOrMissingUrl() = runBlocking {
        val state = WastiSovereignTunnelEngine.establishTunnel(
            context = context,
            provider = TunnelProvider.CUSTOM_PUBLIC_GATEWAY,
            customGatewayUrl = ""
        )
        assertFalse(state.isActive)
        assertEquals(SovereignTunnelStatus.CONFIGURED, state.status)
        assertTrue(state.failureReason!!.contains("Custom gateway URL is required"))
    }

    @Test
    fun testCustomGatewayRejectsMalformedUrl() = runBlocking {
        val state = WastiSovereignTunnelEngine.establishTunnel(
            context = context,
            provider = TunnelProvider.CUSTOM_PUBLIC_GATEWAY,
            customGatewayUrl = "not-a-valid-url"
        )
        assertFalse(state.isActive)
        assertEquals(SovereignTunnelStatus.FAILED, state.status)
        assertTrue(state.failureReason!!.contains("Invalid custom gateway URL syntax"))
    }

    @Test
    fun testCustomGatewayFailsWhenUnreachable() = runBlocking {
        val prober = object : TunnelHealthProber {
            override suspend fun probeHealth(endpointUrl: String, timeoutMs: Int): HealthProbeResult {
                return HealthProbeResult(isHealthy = false, statusCode = 0, latencyMs = 50L, errorMessage = "Connection refused")
            }
        }
        WastiSovereignTunnelEngine.setHealthProberForTesting(prober)

        val state = WastiSovereignTunnelEngine.establishTunnel(
            context = context,
            provider = TunnelProvider.CUSTOM_PUBLIC_GATEWAY,
            customGatewayUrl = "https://broken-gateway.wastios.ai"
        )
        assertFalse(state.isActive)
        assertEquals(SovereignTunnelStatus.FAILED, state.status)
        assertTrue(state.failureReason!!.contains("unreachable"))
    }

    @Test
    fun testCustomGatewayOperationalWhenReachable() = runBlocking {
        val prober = object : TunnelHealthProber {
            override suspend fun probeHealth(endpointUrl: String, timeoutMs: Int): HealthProbeResult {
                return HealthProbeResult(isHealthy = true, statusCode = 200, latencyMs = 20L)
            }
        }
        WastiSovereignTunnelEngine.setHealthProberForTesting(prober)

        val state = WastiSovereignTunnelEngine.establishTunnel(
            context = context,
            provider = TunnelProvider.CUSTOM_PUBLIC_GATEWAY,
            customGatewayUrl = "https://gateway.wastios.ai"
        )
        assertTrue(state.isActive)
        assertEquals(SovereignTunnelStatus.OPERATIONAL, state.status)
        assertEquals("https://gateway.wastios.ai", state.publicHttpsUrl)
        assertTrue(state.isHealthVerified)
        assertEquals("https://gateway.wastios.ai", BackendClient.getBaseUrl())
    }

    @Test
    fun testCloudflareTunnelProcessSuccessAndHealthVerification() = runBlocking {
        val testUrl = "https://quick-wasti-alpha.trycloudflare.com"
        val isDestroyed = AtomicBoolean(false)

        val launcher = object : TunnelProcessLauncher {
            override fun findExecutable(binaryName: String, context: Context): File? = File(context.filesDir, "bin/$binaryName")
            override fun launchProcess(command: List<String>, environment: Map<String, String>): Process {
                return object : Process() {
                    private val stdout = "2026-09-26T21:00:00Z INF +--------------------------------------------------------------------------------------------+\n2026-09-26T21:00:00Z INF |  Your quick Tunnel has been created! Visit it at (it may take some time to be reachable):  |\n2026-09-26T21:00:00Z INF |  $testUrl                                               |\n2026-09-26T21:00:00Z INF +--------------------------------------------------------------------------------------------+\n".byteInputStream()
                    private val stderr = "".byteInputStream()
                    override fun getOutputStream() = ByteArrayOutputStream()
                    override fun getInputStream() = stdout
                    override fun getErrorStream() = stderr
                    override fun waitFor() = 0
                    override fun exitValue(): Int = if (isDestroyed.get()) 143 else throw IllegalThreadStateException("Process is alive")
                    override fun destroy() { isDestroyed.set(true) }
                    override fun destroyForcibly(): Process { isDestroyed.set(true); return this }
                }
            }
        }

        val prober = object : TunnelHealthProber {
            override suspend fun probeHealth(endpointUrl: String, timeoutMs: Int): HealthProbeResult {
                return HealthProbeResult(isHealthy = true, statusCode = 200, latencyMs = 18L)
            }
        }

        WastiSovereignTunnelEngine.setProcessLauncherForTesting(launcher)
        WastiSovereignTunnelEngine.setHealthProberForTesting(prober)

        val state = WastiSovereignTunnelEngine.establishTunnel(context, TunnelProvider.CLOUDFLARE_QUICK_TUNNEL)
        assertTrue(state.isActive)
        assertEquals(SovereignTunnelStatus.OPERATIONAL, state.status)
        assertEquals(testUrl, state.publicHttpsUrl)
        assertTrue(state.isHealthVerified)
        assertEquals(testUrl, BackendClient.getBaseUrl())

        // Terminate and verify cleanup
        WastiSovereignTunnelEngine.terminateTunnel(context)
        assertFalse(WastiSovereignTunnelEngine.tunnelState.value.isActive)
        assertEquals(SovereignTunnelStatus.STOPPED, WastiSovereignTunnelEngine.tunnelState.value.status)
        assertEquals("http://127.0.0.1:8080", BackendClient.getBaseUrl())
        assertTrue(isDestroyed.get())
    }

    @Test
    fun testCloudflareTunnelProcessPrematureExitReportsFailure() = runBlocking {
        val launcher = object : TunnelProcessLauncher {
            override fun findExecutable(binaryName: String, context: Context): File? = File(context.filesDir, "bin/$binaryName")
            override fun launchProcess(command: List<String>, environment: Map<String, String>): Process {
                return object : Process() {
                    private val stdout = "".byteInputStream()
                    private val stderr = "ERR connection to edge failed: bad handshake\n".byteInputStream()
                    override fun getOutputStream() = ByteArrayOutputStream()
                    override fun getInputStream() = stdout
                    override fun getErrorStream() = stderr
                    override fun waitFor() = 1
                    override fun exitValue(): Int = 1
                    override fun destroy() {}
                    override fun destroyForcibly(): Process { return this }
                }
            }
        }

        WastiSovereignTunnelEngine.setProcessLauncherForTesting(launcher)

        val state = WastiSovereignTunnelEngine.establishTunnel(
            context = context,
            provider = TunnelProvider.CLOUDFLARE_QUICK_TUNNEL,
            startupTimeoutMs = 1000L
        )
        assertFalse(state.isActive)
        assertEquals(SovereignTunnelStatus.FAILED, state.status)
        assertTrue(state.failureReason!!.contains("exit code 1"))
        assertTrue(state.failureReason!!.contains("bad handshake"))
    }

    @Test
    fun testEmergencyStopBlocksTunnelEstablishment() = runBlocking {
        WastiEmergencyStopController.triggerEmergencyStop("Test safety stop")

        val state = WastiSovereignTunnelEngine.establishTunnel(context, TunnelProvider.CLOUDFLARE_QUICK_TUNNEL)
        assertFalse(state.isActive)
        assertEquals(SovereignTunnelStatus.BLOCKED, state.status)
        assertTrue(state.failureReason!!.contains("Emergency Stop is active"))
    }

    @Test
    fun testEmergencyStopHookDestroysActiveTunnelProcess() = runBlocking {
        val testUrl = "https://active-tunnel.trycloudflare.com"
        val isDestroyed = AtomicBoolean(false)

        val launcher = object : TunnelProcessLauncher {
            override fun findExecutable(binaryName: String, context: Context): File? = File(context.filesDir, "bin/$binaryName")
            override fun launchProcess(command: List<String>, environment: Map<String, String>): Process {
                return object : Process() {
                    private val stdout = "$testUrl\n".byteInputStream()
                    private val stderr = "".byteInputStream()
                    override fun getOutputStream() = ByteArrayOutputStream()
                    override fun getInputStream() = stdout
                    override fun getErrorStream() = stderr
                    override fun waitFor() = 0
                    override fun exitValue(): Int = if (isDestroyed.get()) 143 else throw IllegalThreadStateException("Process is alive")
                    override fun destroy() { isDestroyed.set(true) }
                    override fun destroyForcibly(): Process { isDestroyed.set(true); return this }
                }
            }
        }

        val prober = object : TunnelHealthProber {
            override suspend fun probeHealth(endpointUrl: String, timeoutMs: Int): HealthProbeResult {
                return HealthProbeResult(isHealthy = true, statusCode = 200, latencyMs = 10L)
            }
        }

        WastiSovereignTunnelEngine.setProcessLauncherForTesting(launcher)
        WastiSovereignTunnelEngine.setHealthProberForTesting(prober)

        val state = WastiSovereignTunnelEngine.establishTunnel(context, TunnelProvider.CLOUDFLARE_QUICK_TUNNEL)
        assertTrue(state.isActive)

        // Trigger Emergency Stop
        WastiEmergencyStopController.triggerEmergencyStop("Unauthorized access detected")

        val stoppedState = WastiSovereignTunnelEngine.tunnelState.value
        assertFalse(stoppedState.isActive)
        assertEquals(SovereignTunnelStatus.BLOCKED, stoppedState.status)
        assertTrue(stoppedState.failureReason!!.contains("Emergency Stop"))
        assertEquals("http://127.0.0.1:8080", BackendClient.getBaseUrl())
        assertTrue(isDestroyed.get())
    }

    @Test
    fun testProvenanceRecordedOnTunnelOperations() = runBlocking {
        val prober = object : TunnelHealthProber {
            override suspend fun probeHealth(endpointUrl: String, timeoutMs: Int): HealthProbeResult {
                return HealthProbeResult(isHealthy = true, statusCode = 200, latencyMs = 10L)
            }
        }
        WastiSovereignTunnelEngine.setHealthProberForTesting(prober)

        val state = WastiSovereignTunnelEngine.establishTunnel(
            context = context,
            provider = TunnelProvider.CUSTOM_PUBLIC_GATEWAY,
            customGatewayUrl = "https://provenance-test.wastios.ai"
        )
        assertTrue(state.isActive)

        val entries = ExecutionProvenanceLedger.entries.value
        val tunnelEntry = entries.firstOrNull { it.capabilityId == "SOVEREIGN_CLOUD_INGRESS_TUNNEL" }
        assertNotNull("Provenance entry must be recorded for tunnel activation", tunnelEntry)
        assertEquals("WastiSovereignTunnelEngine", tunnelEntry?.providerId)
        assertTrue(tunnelEntry?.outputHash?.isNotEmpty() == true)
        assertTrue(ExecutionProvenanceLedger.verifyLedgerIntegrity())
    }
}
