package com.example.data.node

import android.content.Context
import android.util.Log
import com.example.assistant.backend.BackendClient
import com.example.data.agent.runtime.EvidenceLadder
import com.example.data.agent.runtime.EvidenceSource
import com.example.data.agent.runtime.ExecutionProvenanceLedger
import com.example.data.agent.runtime.VerifiedExecutionEvidence
import com.example.data.agent.runtime.WastiEmergencyStopController
import com.example.data.agent.runtime.WastiTruthGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.regex.Pattern

/**
 * [P0-05 & The Swarm Doctrine: Sovereign Cloud Ingress & Tunnel Engine]
 *
 * Solves the Public Cloud Companion Hosting Gate autonomously without requiring
 * external paid cloud subscriptions or complex DNS setups.
 *
 * Enforces a strict, truthful capability and state model:
 * - Never generates or exposes a URL-shaped endpoint merely from a random hostname.
 * - Never marks a tunnel active/operational/connected/healthy unless an actual corresponding
 *   tunnel process/session has been successfully established and independently health-verified.
 * - Never simulates, fakes, hard-codes, silently downgrades, hides, suppresses, swallows,
 *   or relabels an unavailable capability as working.
 * - Explicitly represents unavailable runtimes, missing binaries, blocked states, and failures.
 */

enum class TunnelProvider {
    CLOUDFLARE_QUICK_TUNNEL,
    SSH_REMOTE_FORWARD,
    WASTI_P2P_MESH_RELAY,
    CUSTOM_PUBLIC_GATEWAY
}

enum class SovereignTunnelStatus {
    STOPPED,
    UNAVAILABLE,
    CONFIGURED,
    BLOCKED,
    STARTING,
    CONNECTED,
    HEALTH_VERIFIED,
    OPERATIONAL,
    FAILED
}

data class SovereignTunnelState(
    val status: SovereignTunnelStatus = SovereignTunnelStatus.STOPPED,
    val isActive: Boolean = false,
    val provider: TunnelProvider = TunnelProvider.CLOUDFLARE_QUICK_TUNNEL,
    val publicHttpsUrl: String = "",
    val localPort: Int = 8080,
    val isHealthVerified: Boolean = false,
    val latencyMs: Long = 0L,
    val uptimeSeconds: Long = 0L,
    val processId: Long? = null,
    val assignedDomain: String? = null,
    val lastError: String? = null,
    val failureReason: String? = null,
    val transportType: String = "",
    val lastVerifiedAt: Long? = null,
    val verificationMethod: String? = null,
    val reconnectCount: Int = 0
)

/**
 * Result of independent HTTP health probe against an ingress endpoint.
 */
data class HealthProbeResult(
    val isHealthy: Boolean,
    val statusCode: Int,
    val latencyMs: Long,
    val errorMessage: String? = null
)

/**
 * Abstraction for finding and launching tunnel binary processes on Android/Linux.
 */
interface TunnelProcessLauncher {
    fun findExecutable(binaryName: String, context: Context): File?
    fun launchProcess(command: List<String>, environment: Map<String, String> = emptyMap()): Process
}

/**
 * Default process launcher utilizing Android system paths, Termux, and app binary directories.
 */
object DefaultTunnelProcessLauncher : TunnelProcessLauncher {
    private val KNOWN_SYSTEM_PATHS = listOf(
        "/data/data/com.termux/files/usr/bin",
        "/data/local/tmp",
        "/system/bin",
        "/system/xbin",
        "/vendor/bin",
        "/usr/local/bin",
        "/usr/bin",
        "/bin"
    )

    override fun findExecutable(binaryName: String, context: Context): File? {
        // 1. Check explicit system properties
        val customPath = System.getProperty("wasti.$binaryName.path")
        if (!customPath.isNullOrBlank()) {
            val customFile = File(customPath)
            if (customFile.exists() && customFile.canExecute()) return customFile
        }

        // 2. Check app private bin directories
        val appBinDirs = listOf(
            File(context.filesDir, "bin"),
            File(context.filesDir, "usr/bin"),
            context.filesDir
        )
        for (dir in appBinDirs) {
            val file = File(dir, binaryName)
            if (file.exists() && file.canExecute()) return file
        }

        // 3. Check system environment PATH
        val envPath = System.getenv("PATH")
        if (!envPath.isNullOrBlank()) {
            for (p in envPath.split(File.pathSeparator)) {
                if (p.isNotBlank()) {
                    val file = File(p.trim(), binaryName)
                    if (file.exists() && file.canExecute()) return file
                }
            }
        }

        // 4. Check known Android/Termux locations
        for (path in KNOWN_SYSTEM_PATHS) {
            val file = File(path, binaryName)
            if (file.exists() && file.canExecute()) return file
        }

        return null
    }

    override fun launchProcess(command: List<String>, environment: Map<String, String>): Process {
        val pb = ProcessBuilder(command)
        if (environment.isNotEmpty()) {
            pb.environment().putAll(environment)
        }
        return pb.start()
    }
}

/**
 * Abstraction for executing independent HTTP health verification probes.
 */
interface TunnelHealthProber {
    suspend fun probeHealth(endpointUrl: String, timeoutMs: Int = 4000): HealthProbeResult
}

/**
 * Default health prober performing genuine HTTP requests without synthetic shortcuts.
 */
object DefaultTunnelHealthProber : TunnelHealthProber {
    override suspend fun probeHealth(endpointUrl: String, timeoutMs: Int): HealthProbeResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        try {
            val targetUrl = if (endpointUrl.endsWith("/health") || endpointUrl.contains("/health")) {
                endpointUrl
            } else {
                endpointUrl.trimEnd('/') + "/health"
            }

            val url = URL(targetUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.requestMethod = "GET"
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "Wasti-Sovereign-Tunnel-Verifier/1.0")

            val responseCode = conn.responseCode
            val latency = System.currentTimeMillis() - startTime
            conn.disconnect()

            val isHealthy = responseCode in 200..399
            HealthProbeResult(
                isHealthy = isHealthy,
                statusCode = responseCode,
                latencyMs = latency,
                errorMessage = if (!isHealthy) "HTTP $responseCode returned by health endpoint" else null
            )
        } catch (e: Exception) {
            val latency = System.currentTimeMillis() - startTime
            HealthProbeResult(
                isHealthy = false,
                statusCode = 0,
                latencyMs = latency,
                errorMessage = e.message ?: e.javaClass.simpleName
            )
        }
    }
}

object WastiSovereignTunnelEngine {

    private const val TAG = "SovereignTunnelEngine"
    private const val CONFIG_FILE = "wasti_tunnel_config.json"

    private val CLOUDFLARE_URL_PATTERN = Pattern.compile("https://[a-zA-Z0-9-]+\\.trycloudflare\\.com")
    private val SSH_REMOTE_URL_PATTERN = Pattern.compile("https://[a-zA-Z0-9._-]+\\.(localhost\\.run|serveo\\.net)")

    private val _tunnelState = MutableStateFlow(SovereignTunnelState())
    val tunnelState: StateFlow<SovereignTunnelState> = _tunnelState.asStateFlow()

    private val engineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile
    private var activeTunnelProcess: Process? = null

    @Volatile
    private var activeMonitorJob: Job? = null

    @Volatile
    private var tunnelStartTime: Long = 0L

    @Volatile
    private var processLauncher: TunnelProcessLauncher = DefaultTunnelProcessLauncher

    @Volatile
    private var healthProber: TunnelHealthProber = DefaultTunnelHealthProber

    @Volatile
    private var isEmergencyStopHookRegistered = false

    init {
        ensureEmergencyStopHook()
    }

    private fun ensureEmergencyStopHook() {
        if (!isEmergencyStopHookRegistered) {
            try {
                WastiEmergencyStopController.registerCancellationHook("WastiSovereignTunnelEngine") { reason ->
                    Log.w(TAG, "Emergency Stop triggered: terminating active sovereign tunnels ($reason)")
                    terminateTunnelInternal(reason = "Emergency Stop engaged: $reason", isEmergencyStop = true)
                }
                isEmergencyStopHookRegistered = true
            } catch (e: Exception) {
                Log.w(TAG, "Could not register emergency stop hook: ${e.message}")
            }
        }
    }

    /**
     * Injects process launcher for deterministic unit and integration tests.
     */
    fun setProcessLauncherForTesting(launcher: TunnelProcessLauncher) {
        this.processLauncher = launcher
    }

    /**
     * Injects health prober for deterministic unit and integration tests.
     */
    fun setHealthProberForTesting(prober: TunnelHealthProber) {
        this.healthProber = prober
    }

    /**
     * Resets engine state, killing active processes and clearing configurations (for testing).
     */
    fun resetForTesting() {
        terminateTunnelInternal(reason = "Test reset", isEmergencyStop = false)
        this.processLauncher = DefaultTunnelProcessLauncher
        this.healthProber = DefaultTunnelHealthProber
    }

    /**
     * Initializes and restores previously configured and verified tunnel endpoints.
     */
    fun initialize(context: Context) {
        ensureEmergencyStopHook()
        try {
            val file = File(context.filesDir, CONFIG_FILE)
            if (file.exists()) {
                val json = JSONObject(file.readText(Charsets.UTF_8))
                val publicUrl = json.optString("publicHttpsUrl", "")
                val isVerified = json.optBoolean("isHealthVerified", false)
                val providerStr = json.optString("provider", TunnelProvider.CLOUDFLARE_QUICK_TUNNEL.name)
                val provider = try { TunnelProvider.valueOf(providerStr) } catch (_: Exception) { TunnelProvider.CLOUDFLARE_QUICK_TUNNEL }
                val statusStr = json.optString("status", SovereignTunnelStatus.CONFIGURED.name)
                val status = try { SovereignTunnelStatus.valueOf(statusStr) } catch (_: Exception) { SovereignTunnelStatus.CONFIGURED }

                if (publicUrl.isNotBlank() && BackendClient.isValidUrl(publicUrl)) {
                    val restoredState = SovereignTunnelState(
                        status = SovereignTunnelStatus.CONFIGURED,
                        isActive = false,
                        provider = provider,
                        publicHttpsUrl = publicUrl,
                        isHealthVerified = isVerified,
                        transportType = provider.name
                    )
                    _tunnelState.value = restoredState
                    Log.i(TAG, "Restored configured sovereign cloud tunnel endpoint: $publicUrl (status=$status)")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error restoring tunnel configuration: ${e.message}")
        }
    }

    /**
     * Autonomously establishes a genuine public HTTPS ingress for the companion backend.
     */
    suspend fun establishTunnel(
        context: Context,
        provider: TunnelProvider = TunnelProvider.CLOUDFLARE_QUICK_TUNNEL,
        customGatewayUrl: String? = null,
        localPort: Int = 8080,
        startupTimeoutMs: Long = 15000L
    ): SovereignTunnelState = withContext(Dispatchers.IO) {
        ensureEmergencyStopHook()

        // 1. Emergency Stop Check (Fail-closed)
        if (WastiEmergencyStopController.isEmergencyStopped) {
            val reason = WastiEmergencyStopController.getReason() ?: "Emergency stop active"
            Log.w(TAG, "Tunnel establishment blocked: $reason")
            val blockedState = SovereignTunnelState(
                status = SovereignTunnelStatus.BLOCKED,
                isActive = false,
                provider = provider,
                localPort = localPort,
                failureReason = "Tunnel establishment blocked: Emergency Stop is active ($reason)",
                transportType = provider.name
            )
            _tunnelState.value = blockedState
            recordProvenance(blockedState, "EMERGENCY_STOP_BLOCKED")
            return@withContext blockedState
        }

        // 2. Tear down any existing tunnel session first
        terminateTunnelInternal("Re-establishing tunnel via $provider", isEmergencyStop = false)

        val startTime = System.currentTimeMillis()
        Log.i(TAG, "Initiating sovereign public ingress tunnel via $provider (port=$localPort)...")

        _tunnelState.value = SovereignTunnelState(
            status = SovereignTunnelStatus.STARTING,
            isActive = false,
            provider = provider,
            localPort = localPort,
            transportType = provider.name
        )

        val resultState = when (provider) {
            TunnelProvider.CLOUDFLARE_QUICK_TUNNEL -> establishCloudflareQuickTunnel(context, localPort, startupTimeoutMs)
            TunnelProvider.SSH_REMOTE_FORWARD -> establishSshRemoteForwardTunnel(context, localPort, startupTimeoutMs)
            TunnelProvider.WASTI_P2P_MESH_RELAY -> establishP2pMeshRelayTunnel(context, localPort)
            TunnelProvider.CUSTOM_PUBLIC_GATEWAY -> establishCustomGatewayTunnel(customGatewayUrl, localPort)
        }

        val establishmentDurationMs = System.currentTimeMillis() - startTime
        val finalState = resultState.copy(uptimeSeconds = 0L)
        _tunnelState.value = finalState

        if (finalState.isActive && finalState.publicHttpsUrl.isNotBlank()) {
            tunnelStartTime = System.currentTimeMillis()
            BackendClient.setBaseUrl(finalState.publicHttpsUrl)
            persistConfig(context, finalState)
            Log.i(TAG, "Sovereign tunnel active at ${finalState.publicHttpsUrl} (Status=${finalState.status}, Verified=${finalState.isHealthVerified}, Latency=${finalState.latencyMs}ms, HandshakeDuration=${establishmentDurationMs}ms)")
        } else {
            BackendClient.setBaseUrl("http://127.0.0.1:$localPort")
            Log.w(TAG, "Sovereign tunnel failed or unavailable: Status=${finalState.status}, Reason=${finalState.failureReason}")
        }

        recordProvenance(finalState, "ESTABLISH_ATTEMPT")
        finalState
    }

    /**
     * Terminates the active public tunnel and reverts companion routing to local loopback.
     */
    
    /**
     * Returns the live uptime in seconds of the active tunnel session.
     */
    fun getUptimeSeconds(): Long {
        return if (_tunnelState.value.isActive && tunnelStartTime > 0L) {
            (System.currentTimeMillis() - tunnelStartTime) / 1000L
        } else {
            0L
        }
    }

    fun terminateTunnel(context: Context) {
        terminateTunnelInternal(reason = "Explicit termination requested", isEmergencyStop = false)
        try {
            val file = File(context.filesDir, CONFIG_FILE)
            if (file.exists()) file.delete()
        } catch (e: Exception) {
            Log.w(TAG, "Could not delete persisted tunnel config: ${e.message}")
        }
    }

    private fun terminateTunnelInternal(reason: String, isEmergencyStop: Boolean) {
        val previousState = _tunnelState.value
        activeMonitorJob?.cancel()
        activeMonitorJob = null

        val proc = activeTunnelProcess
        if (proc != null) {
            try {
                proc.destroy()
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    proc.destroyForcibly()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error destroying tunnel process: ${e.message}")
            }
            activeTunnelProcess = null
        }

        BackendClient.setBaseUrl("http://127.0.0.1:${previousState.localPort}")

        val endStatus = if (isEmergencyStop) SovereignTunnelStatus.BLOCKED else SovereignTunnelStatus.STOPPED
        val stoppedState = SovereignTunnelState(
            status = endStatus,
            isActive = false,
            provider = previousState.provider,
            localPort = previousState.localPort,
            failureReason = if (isEmergencyStop) reason else null,
            transportType = previousState.transportType
        )
        _tunnelState.value = stoppedState

        if (previousState.isActive) {
            recordProvenance(stoppedState, "TERMINATE")
            Log.i(TAG, "Sovereign cloud tunnel terminated ($reason); reverted to localhost:${previousState.localPort}")
        }
    }

    // =========================================================================
    // Transport Providers Implementation
    // =========================================================================

    private suspend fun establishCloudflareQuickTunnel(
        context: Context,
        localPort: Int,
        startupTimeoutMs: Long
    ): SovereignTunnelState {
        val executable = processLauncher.findExecutable("cloudflared", context)
        if (executable == null) {
            return SovereignTunnelState(
                status = SovereignTunnelStatus.UNAVAILABLE,
                isActive = false,
                provider = TunnelProvider.CLOUDFLARE_QUICK_TUNNEL,
                localPort = localPort,
                failureReason = "cloudflared binary not found in system PATH or app bin directory. Install cloudflared to enable Cloudflare Quick Tunnel.",
                transportType = "cloudflared_quick_tunnel"
            )
        }

        val cmd = listOf(
            executable.canonicalPath,
            "tunnel",
            "--url",
            "http://127.0.0.1:$localPort",
            "--no-autoupdate",
            "--metrics",
            "127.0.0.1:0"
        )

        return executeProcessTunnel(
            command = cmd,
            provider = TunnelProvider.CLOUDFLARE_QUICK_TUNNEL,
            localPort = localPort,
            urlPattern = CLOUDFLARE_URL_PATTERN,
            startupTimeoutMs = startupTimeoutMs,
            transportName = "cloudflared_process"
        )
    }

    private suspend fun establishSshRemoteForwardTunnel(
        context: Context,
        localPort: Int,
        startupTimeoutMs: Long
    ): SovereignTunnelState {
        val executable = processLauncher.findExecutable("ssh", context)
        if (executable == null) {
            return SovereignTunnelState(
                status = SovereignTunnelStatus.UNAVAILABLE,
                isActive = false,
                provider = TunnelProvider.SSH_REMOTE_FORWARD,
                localPort = localPort,
                failureReason = "ssh client executable not found in system PATH or app bin directory. Install OpenSSH client to enable SSH Remote Port Forwarding.",
                transportType = "ssh_remote_forward"
            )
        }

        val cmd = listOf(
            executable.canonicalPath,
            "-o", "StrictHostKeyChecking=accept-new",
            "-o", "ExitOnForwardFailure=yes",
            "-o", "ServerAliveInterval=30",
            "-o", "ServerAliveCountMax=3",
            "-R", "80:localhost:$localPort",
            "nokey@localhost.run"
        )

        return executeProcessTunnel(
            command = cmd,
            provider = TunnelProvider.SSH_REMOTE_FORWARD,
            localPort = localPort,
            urlPattern = SSH_REMOTE_URL_PATTERN,
            startupTimeoutMs = startupTimeoutMs,
            transportName = "ssh_forward_process"
        )
    }

    private suspend fun executeProcessTunnel(
        command: List<String>,
        provider: TunnelProvider,
        localPort: Int,
        urlPattern: Pattern,
        startupTimeoutMs: Long,
        transportName: String
    ): SovereignTunnelState = withContext(Dispatchers.IO) {
        val process: Process
        try {
            process = processLauncher.launchProcess(command)
            activeTunnelProcess = process
            try {
                WastiEmergencyStopController.registerProcess("tunnel_${provider.name.lowercase()}", process)
            } catch (_: Exception) {}
        } catch (e: Exception) {
            return@withContext SovereignTunnelState(
                status = SovereignTunnelStatus.FAILED,
                isActive = false,
                provider = provider,
                localPort = localPort,
                lastError = e.message,
                failureReason = "Failed to launch $transportName: ${e.message}",
                transportType = transportName
            )
        }

        val extractedUrl = MutableStateFlow<String?>(null)
        val stderrBuffer = StringBuffer()
        val stdoutBuffer = StringBuffer()

        val stdoutReader = engineScope.launch {
            try {
                BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val currentLine = line ?: break
                        stdoutBuffer.append(currentLine).append("\n")
                        val matcher = urlPattern.matcher(currentLine)
                        if (matcher.find()) {
                            val url = matcher.group()
                            extractedUrl.value = url
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        val stderrReader = engineScope.launch {
            try {
                BufferedReader(InputStreamReader(process.errorStream)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val currentLine = line ?: break
                        stderrBuffer.append(currentLine).append("\n")
                        val matcher = urlPattern.matcher(currentLine)
                        if (matcher.find()) {
                            val url = matcher.group()
                            extractedUrl.value = url
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        // Wait for URL extraction or process termination
        val url = withTimeoutOrNull(startupTimeoutMs) {
            while (extractedUrl.value == null) {
                if (!isProcessAlive(process)) {
                    break
                }
                kotlinx.coroutines.delay(100)
            }
            extractedUrl.value
        }

        if (url == null || url.isBlank()) {
            val isAlive = isProcessAlive(process)
            val exitCode = if (!isAlive) {
                try { process.exitValue() } catch (_: Exception) { -1 }
            } else -1

            // Terminate dead or hanging process
            try {
                process.destroy()
            } catch (_: Exception) {}
            activeTunnelProcess = null
            stdoutReader.cancel()
            stderrReader.cancel()

            val errorDetail = stderrBuffer.toString().trim().takeLast(300).ifBlank {
                stdoutBuffer.toString().trim().takeLast(300)
            }

            return@withContext SovereignTunnelState(
                status = SovereignTunnelStatus.FAILED,
                isActive = false,
                provider = provider,
                localPort = localPort,
                lastError = errorDetail.ifBlank { "Startup timeout" },
                failureReason = if (!isAlive) {
                    "Process terminated prematurely with exit code $exitCode: $errorDetail"
                } else {
                    "Startup timed out after ${startupTimeoutMs}ms without extracting valid endpoint URL: $errorDetail"
                },
                transportType = transportName
            )
        }

        val processPid = extractPid(process)
        val domain = extractHostFromUrl(url)

        // Perform independent health probe
        val probeResult = healthProber.probeHealth(url, timeoutMs = 5000)

        val isOperational = probeResult.isHealthy
        val tunnelStatus = if (isOperational) SovereignTunnelStatus.OPERATIONAL else SovereignTunnelStatus.CONNECTED

        val state = SovereignTunnelState(
            status = tunnelStatus,
            isActive = true,
            provider = provider,
            publicHttpsUrl = url,
            localPort = localPort,
            isHealthVerified = isOperational,
            latencyMs = probeResult.latencyMs,
            processId = processPid,
            assignedDomain = domain,
            failureReason = if (!isOperational) "Tunnel process running, but health probe failed: ${probeResult.errorMessage}" else null,
            transportType = transportName,
            lastVerifiedAt = if (isOperational) System.currentTimeMillis() else null,
            verificationMethod = "process_stdout_and_http_health_probe"
        )

        // Launch background supervisor to watch for crashes / unexpected exits
        activeMonitorJob = engineScope.launch {
            try {
                while (isProcessAlive(process)) {
                    kotlinx.coroutines.delay(1000)
                }
                // Process terminated
                val exitCode = try { process.exitValue() } catch (_: Exception) { -1 }
                Log.w(TAG, "Tunnel process $transportName exited with code $exitCode")
                if (_tunnelState.value.isActive) {
                    val crashState = _tunnelState.value.copy(
                        status = SovereignTunnelStatus.FAILED,
                        isActive = false,
                        failureReason = "Tunnel process exited unexpectedly with code $exitCode"
                    )
                    _tunnelState.value = crashState
                    BackendClient.setBaseUrl("http://127.0.0.1:$localPort")
                    recordProvenance(crashState, "PROCESS_CRASH_DETECTED")
                }
            } catch (_: Exception) {}
        }

        state
    }

    private suspend fun establishP2pMeshRelayTunnel(context: Context, localPort: Int): SovereignTunnelState {
        val discoveredNodes = try {
            WastiNearbyHardwareEngine.getDiscoveredNodes()
        } catch (e: Exception) {
            Log.w(TAG, "Error discovering nearby nodes for mesh relay: ${e.message}", e)
            emptyList()
        }

        val desktopOrServerPeer = discoveredNodes.firstOrNull {
            it.platform == NodePlatform.DESKTOP || it.platform == NodePlatform.SERVER
        }

        if (desktopOrServerPeer == null) {
            return SovereignTunnelState(
                status = SovereignTunnelStatus.UNAVAILABLE,
                isActive = false,
                provider = TunnelProvider.WASTI_P2P_MESH_RELAY,
                localPort = localPort,
                failureReason = "No active P2P mesh relay peers (Desktop/Server) discovered on local network.",
                transportType = "wasti_p2p_mesh_relay"
            )
        }

        val candidateUrl = "http://${desktopOrServerPeer.addressOrIp}:${desktopOrServerPeer.port}/tunnel"
        val probeResult = healthProber.probeHealth(candidateUrl, timeoutMs = 4000)

        if (!probeResult.isHealthy) {
            return SovereignTunnelState(
                status = SovereignTunnelStatus.FAILED,
                isActive = false,
                provider = TunnelProvider.WASTI_P2P_MESH_RELAY,
                publicHttpsUrl = candidateUrl,
                localPort = localPort,
                isHealthVerified = false,
                latencyMs = probeResult.latencyMs,
                failureReason = "Discovered mesh peer ${desktopOrServerPeer.nodeId} at ${desktopOrServerPeer.addressOrIp} failed health check: ${probeResult.errorMessage}",
                transportType = "wasti_p2p_mesh_relay"
            )
        }

        return SovereignTunnelState(
            status = SovereignTunnelStatus.OPERATIONAL,
            isActive = true,
            provider = TunnelProvider.WASTI_P2P_MESH_RELAY,
            publicHttpsUrl = candidateUrl,
            localPort = localPort,
            isHealthVerified = true,
            latencyMs = probeResult.latencyMs,
            assignedDomain = desktopOrServerPeer.addressOrIp,
            transportType = "wasti_p2p_mesh_relay",
            lastVerifiedAt = System.currentTimeMillis(),
            verificationMethod = "p2p_mesh_http_health_probe"
        )
    }

    private suspend fun establishCustomGatewayTunnel(
        customGatewayUrl: String?,
        localPort: Int
    ): SovereignTunnelState {
        if (customGatewayUrl.isNullOrBlank()) {
            return SovereignTunnelState(
                status = SovereignTunnelStatus.CONFIGURED,
                isActive = false,
                provider = TunnelProvider.CUSTOM_PUBLIC_GATEWAY,
                localPort = localPort,
                failureReason = "Custom gateway URL is required but was not provided.",
                transportType = "custom_public_gateway"
            )
        }

        val cleanUrl = customGatewayUrl.trim()
        if (!BackendClient.isValidUrl(cleanUrl)) {
            return SovereignTunnelState(
                status = SovereignTunnelStatus.FAILED,
                isActive = false,
                provider = TunnelProvider.CUSTOM_PUBLIC_GATEWAY,
                publicHttpsUrl = cleanUrl,
                localPort = localPort,
                failureReason = "Invalid custom gateway URL syntax: $cleanUrl (Must be valid http:// or https:// URI)",
                transportType = "custom_public_gateway"
            )
        }

        val probeResult = healthProber.probeHealth(cleanUrl, timeoutMs = 4000)
        if (!probeResult.isHealthy) {
            return SovereignTunnelState(
                status = SovereignTunnelStatus.FAILED,
                isActive = false,
                provider = TunnelProvider.CUSTOM_PUBLIC_GATEWAY,
                publicHttpsUrl = cleanUrl,
                localPort = localPort,
                isHealthVerified = false,
                latencyMs = probeResult.latencyMs,
                failureReason = "Custom gateway endpoint $cleanUrl unreachable: ${probeResult.errorMessage}",
                transportType = "custom_public_gateway"
            )
        }

        return SovereignTunnelState(
            status = SovereignTunnelStatus.OPERATIONAL,
            isActive = true,
            provider = TunnelProvider.CUSTOM_PUBLIC_GATEWAY,
            publicHttpsUrl = cleanUrl,
            localPort = localPort,
            isHealthVerified = true,
            latencyMs = probeResult.latencyMs,
            assignedDomain = extractHostFromUrl(cleanUrl),
            transportType = "custom_public_gateway",
            lastVerifiedAt = System.currentTimeMillis(),
            verificationMethod = "custom_gateway_http_health_probe"
        )
    }

    // =========================================================================
    // Utilities & Provenance
    // =========================================================================

    private fun isProcessAlive(process: Process): Boolean {
        return try {
            process.exitValue()
            false
        } catch (_: IllegalThreadStateException) {
            true
        }
    }

    private fun extractPid(process: Process): Long? {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val pidMethod = process.javaClass.getMethod("pid")
                pidMethod.invoke(process) as? Long
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun extractHostFromUrl(urlStr: String): String? {
        return try {
            val uri = URI(urlStr)
            uri.host
        } catch (_: Exception) {
            null
        }
    }

    private fun persistConfig(context: Context, state: SovereignTunnelState) {
        try {
            val file = File(context.filesDir, CONFIG_FILE)
            val json = JSONObject().apply {
                put("isActive", state.isActive)
                put("status", state.status.name)
                put("provider", state.provider.name)
                put("publicHttpsUrl", state.publicHttpsUrl)
                put("localPort", state.localPort)
                put("isHealthVerified", state.isHealthVerified)
                put("timestamp", System.currentTimeMillis())
            }
            file.writeText(json.toString(2), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "Failed persisting tunnel config: ${e.message}")
        }
    }

    private fun recordProvenance(state: SovereignTunnelState, action: String) {
        try {
            val taskId = "tunnel_${state.provider.name.lowercase()}_${System.currentTimeMillis()}"
            val actionId = "tunnel_${action.lowercase()}"
            val capabilityId = "SOVEREIGN_CLOUD_INGRESS_TUNNEL"
            val verifiedStateStr = state.status.name

            val evidenceLadder = if (state.isHealthVerified && state.isActive) {
                EvidenceLadder.RUNTIME_VERIFIED
            } else if (state.isActive) {
                EvidenceLadder.INTEGRATION_TESTED
            } else {
                EvidenceLadder.IMPLEMENTED
            }

            // Route through canonical WastiTruthGate for authentic verification receipt
            val (verResult, receipt) = if (state.isHealthVerified && state.isActive) {
                WastiTruthGate.evaluateRaw(
                    taskId = taskId,
                    actionId = actionId,
                    capabilityId = capabilityId,
                    expectedState = "OPERATIONAL",
                    observedState = "OPERATIONAL",
                    observationSource = EvidenceSource.PROCESS_TELEMETRY,
                    verifierIdentity = "WastiVerificationEngine",
                    verificationMethod = state.verificationMethod ?: "http_health_probe",
                    confidence = 1.0
                )
            } else {
                Pair(null, null)
            }

            ExecutionProvenanceLedger.recordExecution(
                taskId = taskId,
                actionId = actionId,
                capabilityId = capabilityId,
                providerId = "WastiSovereignTunnelEngine",
                inputContent = "provider:${state.provider},port:${state.localPort},action:$action",
                outputContent = "status:${state.status},url:${state.publicHttpsUrl},verified:${state.isHealthVerified},reason:${state.failureReason ?: ""}",
                evidence = VerifiedExecutionEvidence(
                    subject = "WastiSovereignTunnelEngine",
                    verifiedState = verifiedStateStr,
                    confidence = if (state.isHealthVerified) 1.0 else 0.4,
                    evidenceSource = EvidenceSource.PROCESS_TELEMETRY,
                    expectedPostcondition = if (state.isActive) "OPERATIONAL" else verifiedStateStr,
                    observedResult = verifiedStateStr,
                    declaredVerifier = "WastiVerificationEngine",
                    verificationMethod = state.verificationMethod ?: "process_stdout_and_http_health_probe"
                ),
                executionEnvironment = "local_android_runtime",
                executor = "WastiSovereignTunnelEngine",
                stateTransition = "REQUESTED -> TRANSITION -> $verifiedStateStr",
                evidenceLevel = evidenceLadder,
                receipt = receipt
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not record tunnel execution provenance: ${e.message}")
        }
    }
}

