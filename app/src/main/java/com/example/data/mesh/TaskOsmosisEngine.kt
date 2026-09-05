package com.example.data.mesh

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

enum class MeshNodeBodyType {
    ANDROID_MOBILE,
    LINUX_DESKTOP,
    WINDOWS_WORKSTATION,
    CLOUD_VM,
    HEADLESS_SERVER,
    WRE_CONTAINER
}

data class MeshNodeDescriptor(
    val nodeId: String = UUID.randomUUID().toString(),
    val name: String,
    val bodyType: MeshNodeBodyType,
    val ipAddress: String,
    val availableComputeTflops: Float,
    val isNearby: Boolean = true,
    val isOnline: Boolean = true
)

data class TaskOsmosisMigrationSnapshot(
    val migrationId: String = UUID.randomUUID().toString(),
    val sourceNode: String,
    val targetNode: String,
    val taskDescription: String,
    val transferredMemoryBytes: Long,
    val isSeamlessTransferSuccessful: Boolean,
    val timestampMs: Long = System.currentTimeMillis()
)

object TaskOsmosisEngine {

    private val _registeredMeshNodes = MutableStateFlow(
        listOf(
            MeshNodeDescriptor(nodeId = "phone_01", name = "Wasti Primary Phone (Host)", bodyType = MeshNodeBodyType.ANDROID_MOBILE, ipAddress = "127.0.0.1", availableComputeTflops = 2.5f, isNearby = true),
            MeshNodeDescriptor(nodeId = "desktop_02", name = "Workstation Linux Node", bodyType = MeshNodeBodyType.LINUX_DESKTOP, ipAddress = "192.168.1.120", availableComputeTflops = 35.0f, isNearby = true),
            MeshNodeDescriptor(nodeId = "cloud_03", name = "Headless Cloud Compute Core", bodyType = MeshNodeBodyType.CLOUD_VM, ipAddress = "34.120.45.10", availableComputeTflops = 120.0f, isNearby = false)
        )
    )
    val registeredMeshNodes: StateFlow<List<MeshNodeDescriptor>> = _registeredMeshNodes.asStateFlow()

    private val _migrationHistory = MutableStateFlow<List<TaskOsmosisMigrationSnapshot>>(emptyList())
    val migrationHistory: StateFlow<List<TaskOsmosisMigrationSnapshot>> = _migrationHistory.asStateFlow()

    fun migrateTaskToOptimalNode(taskDescription: String, memorySnapshotBytes: Long): TaskOsmosisMigrationSnapshot {
        val nodes = _registeredMeshNodes.value
        val target = nodes.firstOrNull { it.nodeId != "phone_01" && it.isOnline } ?: nodes.first()

        val snapshot = TaskOsmosisMigrationSnapshot(
            sourceNode = "Wasti Primary Phone",
            targetNode = target.name,
            taskDescription = taskDescription,
            transferredMemoryBytes = memorySnapshotBytes,
            isSeamlessTransferSuccessful = true
        )

        val list = _migrationHistory.value.toMutableList()
        list.add(0, snapshot)
        _migrationHistory.value = list.take(20)
        return snapshot
    }
}
