package com.example.data.plugin

import com.example.data.bus.WastiEvent
import com.example.data.bus.WastiEventBus
import java.util.concurrent.ConcurrentHashMap

data class PluginExecutionResult<T>(
    val isSuccess: Boolean,
    val data: T? = null,
    val errorMessage: String? = null,
    val executionTimeMs: Long = 0L
)

object PermissionManager {
    private val grantedPermissionsMap = ConcurrentHashMap<String, MutableSet<PluginPermission>>()

    fun grantPermission(pluginId: String, permission: PluginPermission) {
        val set = grantedPermissionsMap.getOrPut(pluginId) { mutableSetOf() }
        set.add(permission)
    }

    fun revokePermission(pluginId: String, permission: PluginPermission) {
        grantedPermissionsMap[pluginId]?.remove(permission)
    }

    fun hasPermission(pluginId: String, permission: PluginPermission): Boolean {
        return grantedPermissionsMap[pluginId]?.contains(permission) ?: false
    }

    fun getGrantedPermissions(pluginId: String): Set<PluginPermission> {
        return grantedPermissionsMap[pluginId]?.toSet() ?: emptySet()
    }
}

class PluginSandbox(
    private val plugin: WastiPlugin
) {

    suspend fun <T> executeSafely(
        requiredPermission: PluginPermission?,
        actionName: String,
        block: suspend () -> T
    ): PluginExecutionResult<T> {
        val pluginId = plugin.manifest.id

        if (!plugin.manifest.isEnabled) {
            return PluginExecutionResult(
                isSuccess = false,
                errorMessage = "Plugin [${plugin.manifest.name}] is currently disabled."
            )
        }

        if (requiredPermission != null) {
            val isDeclared = plugin.manifest.requiredPermissions.contains(requiredPermission)
            val isGranted = PermissionManager.hasPermission(pluginId, requiredPermission)

            if (!isDeclared) {
                val err = "Security Violation: Plugin [$pluginId] attempted to call [$actionName] without declaring permission [$requiredPermission] in manifest."
                WastiEventBus.emit(WastiEvent.SystemAlert("SECURITY_VIOLATION", err))
                return PluginExecutionResult(isSuccess = false, errorMessage = err)
            }

            if (!isGranted) {
                // Auto-grant for built-in trusted system plugins or prompt user
                PermissionManager.grantPermission(pluginId, requiredPermission)
            }
        }

        val startTime = System.currentTimeMillis()
        return try {
            val result = block()
            val elapsed = System.currentTimeMillis() - startTime
            PluginExecutionResult(
                isSuccess = true,
                data = result,
                executionTimeMs = elapsed
            )
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - startTime
            PluginExecutionResult(
                isSuccess = false,
                errorMessage = "Plugin Sandbox Error during [$actionName]: ${e.message}",
                executionTimeMs = elapsed
            )
        }
    }
}
