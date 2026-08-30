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
        val direct = tools[toolName]
        if (direct != null) return direct

        val wastiTool = com.example.data.tool.ToolRegistry.getTool(toolName)
        if (wastiTool != null) {
            return object : AgentTool {
                override val name: String = wastiTool.definition.id
                override val description: String = wastiTool.definition.description
                override val permissionLevel: PermissionLevel = when {
                    wastiTool.definition.category.contains("PRIVILEGED", ignoreCase = true) -> PermissionLevel.PRIVILEGED
                    wastiTool.definition.category.contains("Dynamic", ignoreCase = true) -> PermissionLevel.CONTROLLED
                    else -> PermissionLevel.SAFE
                }

                override suspend fun execute(input: Map<String, Any?>): Map<String, Any?> {
                    val nonNullParams = input.filterValues { it != null }.mapValues { it.value!! }
                    val res = wastiTool.execute(nonNullParams)
                    val isError = res.startsWith("Error", ignoreCase = true) || 
                                  res.startsWith("Tool Execution Error", ignoreCase = true) ||
                                  res.startsWith("Security Violation", ignoreCase = true) ||
                                  res.startsWith("Permission Denied", ignoreCase = true)
                    return mapOf(
                        "success" to !isError,
                        "output" to res,
                        "error" to if (isError) res else null,
                        "verificationStatus" to if (!isError) "VERIFIED" else "FAILED"
                    )
                }
            }
        }
        return null
    }

    fun contains(toolName: String): Boolean {
        return tools.containsKey(toolName) || com.example.data.tool.ToolRegistry.getTool(toolName) != null
    }

    fun list(): List<AgentTool> {
        val list = tools.values.toMutableList()
        com.example.data.tool.ToolRegistry.getAllWastiTools().forEach { wastiTool ->
            if (list.none { it.name == wastiTool.definition.id }) {
                list.add(object : AgentTool {
                    override val name: String = wastiTool.definition.id
                    override val description: String = wastiTool.definition.description
                    override val permissionLevel: PermissionLevel = when {
                        wastiTool.definition.category.contains("PRIVILEGED", ignoreCase = true) -> PermissionLevel.PRIVILEGED
                        wastiTool.definition.category.contains("Dynamic", ignoreCase = true) -> PermissionLevel.CONTROLLED
                        else -> PermissionLevel.SAFE
                    }
                    override suspend fun execute(input: Map<String, Any?>): Map<String, Any?> {
                        val nonNullParams = input.filterValues { it != null }.mapValues { it.value!! }
                        val res = wastiTool.execute(nonNullParams)
                        val isError = res.startsWith("Error", ignoreCase = true) || 
                                      res.startsWith("Tool Execution Error", ignoreCase = true) ||
                                      res.startsWith("Security Violation", ignoreCase = true) ||
                                      res.startsWith("Permission Denied", ignoreCase = true)
                        return mapOf(
                            "success" to !isError,
                            "output" to res,
                            "error" to if (isError) res else null,
                            "verificationStatus" to if (!isError) "VERIFIED" else "FAILED"
                        )
                    }
                })
            }
        }
        return list
    }
}
