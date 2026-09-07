package com.example.data.node

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Build
import android.util.Log
import com.example.assistant.PermissionManager
import com.example.data.agent.runtime.CapabilityRealityState
import com.example.data.di.WastiServiceLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * [The Eternal Manifesto: The Swarm/Mesh Principle & Many Bodies Doctrine]
 *
 * "Android, Linux, Windows, Cloud, Containers, Browsers, GitHub Actions, IoT and future
 * platforms are execution bodies of one brain. Multiple Wasti nodes cooperate under one reality model."
 *
 * WastiNearbyHardwareEngine:
 * Coordinates multi-device hardware environment discovery across Wi-Fi (UDP/TCP/mDNS)
 * and Bluetooth (RFCOMM/BLE/Bonded Devices) to dynamically discover nearby Desktops,
 * Laptops, PCs, Termux stations, and Servers for autonomous heavy compute offloading.
 */

enum class HardwareTransportType {
    WIFI_LAN,
    BLUETOOTH_RFCOMM,
    BLUETOOTH_BLE,
    HYBRID_MESH
}

enum class DeviceHardwareType {
    DESKTOP_PC,
    LAPTOP,
    SERVER_WORKSTATION,
    MOBILE_ANDROID,
    TERMUX_STATION,
    IOT_EDGE
}

data class NearbyHardwareNode(
    val nodeId: String,
    val deviceName: String,
    val hardwareType: DeviceHardwareType,
    val transportType: HardwareTransportType,
    val platform: NodePlatform,
    val addressOrIp: String,
    val port: Int = 35261,
    val advertisedCapabilities: Set<String>,
    val capabilityFingerprint: String,
    val isAvailableForHeavyCompute: Boolean = true,
    val estimatedComputeMultiplier: Float = 1.0f, // e.g. 4.0x for Desktop PC, 2.5x for Laptop
    val lastSeenTimestamp: Long = System.currentTimeMillis()
)

data class NearbySwarmState(
    val isWifiScanningActive: Boolean = false,
    val isBluetoothScanningActive: Boolean = false,
    val totalDiscoveredDevices: Int = 0,
    val heavyComputeNodesAvailable: Int = 0,
    val connectedNodes: List<NearbyHardwareNode> = emptyList()
)

object WastiNearbyHardwareEngine {

    private const val TAG = "NearbyHardwareEngine"
    val WASTI_RFCOMM_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB") // Standard SPP / Wasti RFCOMM

    private val discoveredHardwareNodes = ConcurrentHashMap<String, NearbyHardwareNode>()
    private val _swarmState = MutableStateFlow(NearbySwarmState())
    val swarmState: StateFlow<NearbySwarmState> = _swarmState.asStateFlow()

    private var scanJob: Job? = null
    @Volatile
    private var isRunning = false

    fun getDiscoveredNodes(): List<NearbyHardwareNode> = discoveredHardwareNodes.values.toList()

    fun getHeavyComputeNodes(): List<NearbyHardwareNode> =
        discoveredHardwareNodes.values.filter { it.isAvailableForHeavyCompute }.sortedByDescending { it.estimatedComputeMultiplier }

    /**
     * Starts continuous multi-transport nearby hardware discovery (Wi-Fi + Bluetooth).
     */
    fun startDiscovery(context: Context) {
        if (isRunning) return
        isRunning = true

        val scope = CoroutineScope(Dispatchers.IO)

        // Start underlying Wi-Fi UDP discovery beacon and TCP listener
        WastiMeshTransportEngine.startMesh(context)

        // Start mDNS Network Service Discovery
        try {
            WastiNodeDiscoveryManager.getInstance(context).registerService(WastiMeshTransportEngine.MESH_EXECUTION_PORT)
        } catch (e: Exception) {
            Log.w(TAG, "mDNS registration notice: ${e.message}")
        }

        scanJob = scope.launch {
            while (isActive && isRunning) {
                try {
                    // 1. Scan Wi-Fi Mesh Peers
                    scanWifiMeshPeers()

                    // 2. Scan Bluetooth Bonded & Nearby Devices
                    scanBluetoothNearbyHardware(context)

                    // 3. Update Swarm State Flow
                    updateSwarmState()
                } catch (e: Exception) {
                    Log.w(TAG, "Discovery scan tick error: ${e.message}")
                }
                delay(12_000L) // Refresh discovery every 12 seconds
            }
        }

        Log.i(TAG, "Wasti Nearby Hardware Engine initialized across Wi-Fi & Bluetooth.")
    }

    fun stopDiscovery(context: Context) {
        isRunning = false
        scanJob?.cancel()
        scanJob = null
        try {
            WastiNodeDiscoveryManager.getInstance(context).unregisterService()
        } catch (_: Exception) {}
        Log.i(TAG, "Wasti Nearby Hardware Engine stopped.")
    }

    /**
     * Polls Wi-Fi mesh discovered peers from WastiMeshTransportEngine.
     */
    private fun scanWifiMeshPeers() {
        val wifiPeers = WastiMeshTransportEngine.getDiscoveredPeers()
        for (peer in wifiPeers) {
            val (hwType, multiplier) = classifyHardwareFromPlatformAndName(peer.platform, peer.nodeName)
            val hardwareNode = NearbyHardwareNode(
                nodeId = peer.nodeId,
                deviceName = peer.nodeName,
                hardwareType = hwType,
                transportType = HardwareTransportType.WIFI_LAN,
                platform = peer.platform,
                addressOrIp = peer.ipAddress,
                port = peer.port,
                advertisedCapabilities = peer.advertisedCapabilities,
                capabilityFingerprint = peer.capabilityFingerprint,
                isAvailableForHeavyCompute = hwType in setOf(DeviceHardwareType.DESKTOP_PC, DeviceHardwareType.LAPTOP, DeviceHardwareType.SERVER_WORKSTATION, DeviceHardwareType.TERMUX_STATION),
                estimatedComputeMultiplier = multiplier,
                lastSeenTimestamp = peer.lastSeenTimestamp
            )
            discoveredHardwareNodes[hardwareNode.nodeId] = hardwareNode
        }
    }

    /**
     * Discovers paired/bonded Bluetooth PCs, laptops, and workstations.
     */
    @SuppressLint("MissingPermission")
    private fun scanBluetoothNearbyHardware(context: Context) {
        val hasBtPermission = PermissionManager.hasBluetoothPermissions(context)
        if (!hasBtPermission) {
            Log.d(TAG, "Bluetooth permissions not granted; skipping BT hardware scan.")
            return
        }

        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val btAdapter = btManager?.adapter ?: BluetoothAdapter.getDefaultAdapter() ?: return

        if (!btAdapter.isEnabled) {
            Log.d(TAG, "Bluetooth adapter is disabled.")
            return
        }

        try {
            val bondedDevices: Set<BluetoothDevice>? = btAdapter.bondedDevices
            if (bondedDevices.isNullOrEmpty()) return

            for (device in bondedDevices) {
                val name = device.name ?: "Unknown Bluetooth Device"
                val address = device.address ?: continue
                val btClass = device.bluetoothClass

                val majorClass = btClass?.majorDeviceClass ?: BluetoothClass.Device.Major.UNCATEGORIZED
                val (hwType, platform, multiplier) = classifyBluetoothHardware(majorClass, name)

                val capabilities = when (hwType) {
                    DeviceHardwareType.DESKTOP_PC -> setOf("heavy_compute", "matrix_transform", "gpu_compute", "code_compilation", "model_inference")
                    DeviceHardwareType.LAPTOP -> setOf("heavy_compute", "code_compilation", "file_transform", "model_inference")
                    DeviceHardwareType.SERVER_WORKSTATION -> setOf("heavy_compute", "gpu_compute", "batch_embedding", "code_compilation")
                    DeviceHardwareType.TERMUX_STATION -> setOf("terminal", "bash", "python", "curl")
                    else -> setOf("basic_compute", "sync")
                }

                val nodeId = "bt_node_${address.replace(":", "").lowercase()}"
                val hardwareNode = NearbyHardwareNode(
                    nodeId = nodeId,
                    deviceName = name,
                    hardwareType = hwType,
                    transportType = HardwareTransportType.BLUETOOTH_RFCOMM,
                    platform = platform,
                    addressOrIp = address,
                    port = 0,
                    advertisedCapabilities = capabilities,
                    capabilityFingerprint = computeFingerprint(capabilities),
                    isAvailableForHeavyCompute = hwType in setOf(DeviceHardwareType.DESKTOP_PC, DeviceHardwareType.LAPTOP, DeviceHardwareType.SERVER_WORKSTATION),
                    estimatedComputeMultiplier = multiplier,
                    lastSeenTimestamp = System.currentTimeMillis()
                )

                discoveredHardwareNodes[nodeId] = hardwareNode

                // Federate into WastiNodeManager
                federateNodeIntoManager(hardwareNode)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException while querying Bluetooth bonded devices: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "Bluetooth scan error: ${e.message}")
        }
    }

    private fun classifyHardwareFromPlatformAndName(platform: NodePlatform, name: String): Pair<DeviceHardwareType, Float> {
        val lower = name.lowercase()
        return when {
            lower.contains("desktop") || lower.contains("pc") || lower.contains("workstation") || platform == NodePlatform.DESKTOP ->
                DeviceHardwareType.DESKTOP_PC to 4.5f
            lower.contains("laptop") || lower.contains("macbook") || lower.contains("thinkpad") || lower.contains("notebook") ->
                DeviceHardwareType.LAPTOP to 3.0f
            lower.contains("server") || platform == NodePlatform.SERVER || platform == NodePlatform.CLOUD_WORKER ->
                DeviceHardwareType.SERVER_WORKSTATION to 8.0f
            lower.contains("termux") || platform == NodePlatform.TERMUX ->
                DeviceHardwareType.TERMUX_STATION to 2.0f
            else ->
                DeviceHardwareType.MOBILE_ANDROID to 1.0f
        }
    }

    private fun classifyBluetoothHardware(majorDeviceClass: Int, deviceName: String): Triple<DeviceHardwareType, NodePlatform, Float> {
        val lower = deviceName.lowercase()
        return when {
            majorDeviceClass == BluetoothClass.Device.Major.COMPUTER || lower.contains("pc") || lower.contains("desktop") ->
                Triple(DeviceHardwareType.DESKTOP_PC, NodePlatform.DESKTOP, 4.0f)
            lower.contains("laptop") || lower.contains("macbook") || lower.contains("thinkpad") ->
                Triple(DeviceHardwareType.LAPTOP, NodePlatform.DESKTOP, 3.0f)
            lower.contains("server") ->
                Triple(DeviceHardwareType.SERVER_WORKSTATION, NodePlatform.SERVER, 7.5f)
            lower.contains("termux") ->
                Triple(DeviceHardwareType.TERMUX_STATION, NodePlatform.TERMUX, 2.0f)
            else ->
                Triple(DeviceHardwareType.MOBILE_ANDROID, NodePlatform.ANDROID, 1.0f)
        }
    }

    private fun federateNodeIntoManager(node: NearbyHardwareNode) {
        try {
            val advertisedMap = node.advertisedCapabilities.associateWith { cap ->
                AdvertisedCapabilityInfo(
                    capabilityId = cap,
                    version = "1.0.0",
                    realityState = CapabilityRealityState.LIVE_CONNECTED,
                    provider = node.deviceName,
                    isLocallyExecutable = false,
                    resourceRequirements = if (node.isAvailableForHeavyCompute) "HIGH" else "MEDIUM"
                )
            }

            WastiServiceLocator.nodeManager.registerNode(
                WastiNode(
                    nodeId = node.nodeId,
                    nodeName = "${node.deviceName} (${node.hardwareType})",
                    platform = node.platform,
                    capabilities = node.advertisedCapabilities,
                    advertisedCapabilities = advertisedMap,
                    capabilityFingerprint = node.capabilityFingerprint,
                    connectionState = NodeConnectionState.CONNECTED,
                    trustState = NodeTrustState.PAIRED,
                    isLocal = false,
                    endpointUrl = if (node.transportType == HardwareTransportType.WIFI_LAN) "http://${node.addressOrIp}:${node.port}" else "bt://${node.addressOrIp}",
                    networkAddress = node.addressOrIp,
                    dataLocality = NodeDataLocality.TRUSTED_LAN
                )
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to federate Bluetooth node: ${e.message}")
        }
    }

    private fun updateSwarmState() {
        val nodes = discoveredHardwareNodes.values.toList()
        val heavyCount = nodes.count { it.isAvailableForHeavyCompute }
        _swarmState.value = NearbySwarmState(
            isWifiScanningActive = isRunning,
            isBluetoothScanningActive = isRunning,
            totalDiscoveredDevices = nodes.size,
            heavyComputeNodesAvailable = heavyCount,
            connectedNodes = nodes
        )
    }

    fun registerNodeForTesting(node: NearbyHardwareNode) {
        discoveredHardwareNodes[node.nodeId] = node
        updateSwarmState()
    }

    fun clearForTesting() {
        discoveredHardwareNodes.clear()
        updateSwarmState()
    }

    private fun computeFingerprint(capabilities: Set<String>): String {
        val sorted = capabilities.sorted().joinToString(";")
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(sorted.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }
}
