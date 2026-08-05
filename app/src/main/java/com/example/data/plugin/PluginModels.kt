package com.example.data.plugin

enum class PluginPermission {
    AI_CHAT,
    VOICE,
    FILES,
    NETWORK,
    CAMERA,
    CONTACTS,
    DEVICE_CONTROL,
    AUTOMATION,
    READ_MEMORY,
    WRITE_MEMORY,
    READ_PROJECTS,
    WRITE_PROJECTS,
    READ_ANALYTICS,
    READ_BUSINESS_DATA,
    SYNC,
    BACKGROUND_TASKS
}

data class PluginManifest(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val author: String,
    val requiredPermissions: Set<PluginPermission>,
    val entryPointClass: String,
    val isEnabled: Boolean = false
)

interface WastiPlugin {
    val manifest: PluginManifest
    suspend fun onInitialize(): Boolean
    suspend fun onTerminate()
}
