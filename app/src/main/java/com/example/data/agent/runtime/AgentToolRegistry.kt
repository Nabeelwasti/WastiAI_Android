package com.example.data.agent.runtime

import java.util.concurrent.ConcurrentHashMap

enum class PermissionLevel {
    SAFE,
    CONTROLLED,
    PRIVILEGED
}

interface AgentTool {
    val name: String
    val description: String
    val permissionLevel: PermissionLevel
    suspend fun execute(input: Map<String, Any?>): Map<String, Any?>
}

/**
 * Registry catalog for available tools.
 * NOTE: Registry ONLY catalogizes tools; authorization belongs exclusively
 * to SecurityPolicy and PermissionModel.
 */
class AgentToolRegistry {

    private val tools = ConcurrentHashMap<String, AgentTool>()

    fun register(tool: AgentTool) {
        tools[tool.name] = tool
    }

    fun unregister(toolName: String) {
        tools.remove(toolName)
    }

    fun get(toolName: String): AgentTool? {
        return tools[toolName]
    }

    fun contains(toolName: String): Boolean {
        return tools.containsKey(toolName)
    }

    fun list(): List<AgentTool> {
        return tools.values.toList()
    }
}
