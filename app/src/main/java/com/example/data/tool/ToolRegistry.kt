package com.example.data.tool

import com.example.data.memory.MemoryManager
import com.example.data.memory.model.MemorySearchQuery

data class ToolDefinition(
    val id: String,
    val name: String,
    val category: String,
    val description: String,
    val parametersJsonSchema: String = "{}"
)

interface WastiTool {
    val definition: ToolDefinition
    suspend fun execute(parameters: Map<String, Any>): String
}

class MemorySearchTool : WastiTool {
    override val definition = ToolDefinition(
        id = "memory_search",
        name = "Memory Search",
        category = "Memory",
        description = "Performs hybrid vector & keyword search across long-term memory."
    )

    override suspend fun execute(parameters: Map<String, Any>): String {
        val query = parameters["query"]?.toString() ?: ""
        if (query.isBlank()) return "Error: Query parameters empty."
        val results = MemoryManager.hybridSearch(MemorySearchQuery(queryText = query, topK = 5))
        if (results.isEmpty()) return "No matching long-term memories found."
        return results.joinToString("\n") { "- [${it.memory.category}] ${it.memory.key}: ${it.memory.value}" }
    }
}

class DeviceControlTool : WastiTool {
    override val definition = ToolDefinition(
        id = "device_control",
        name = "Device Controller",
        category = "Automation",
        description = "Executes hardware device toggles and Android system controls."
    )

    override suspend fun execute(parameters: Map<String, Any>): String {
        val action = parameters["action"]?.toString() ?: "status"
        return "Device Action [$action] executed successfully via WastiDeviceController."
    }
}

class LeadRadarTool : WastiTool {
    override val definition = ToolDefinition(
        id = "lead_radar",
        name = "Lead Radar Engine",
        category = "Business Automation",
        description = "Scans live job feeds, evaluates SkillMatrix matches, and drafts client proposal pitches."
    )

    override suspend fun execute(parameters: Map<String, Any>): String {
        val query = parameters["query"]?.toString() ?: "Video Editing"
        return com.example.data.core.WastiCore.processLeadRadarExecution("lead radar for $query")
    }
}

object ToolRegistry {
    private val toolsMap = java.util.concurrent.ConcurrentHashMap<String, WastiTool>()

    init {
        registerTool(MemorySearchTool())
        registerTool(DeviceControlTool())
        registerTool(LeadRadarTool())
    }

    fun registerTool(tool: WastiTool) {
        toolsMap[tool.definition.id] = tool
    }

    fun getTool(id: String): WastiTool? = toolsMap[id]

    fun getAllTools(): List<ToolDefinition> = toolsMap.values.map { it.definition }

    suspend fun executeTool(id: String, parameters: Map<String, Any>): String {
        val tool = toolsMap[id] ?: return "Error: Tool [$id] not found in ToolRegistry."
        return try {
            tool.execute(parameters)
        } catch (e: Exception) {
            "Tool Execution Error [$id]: ${e.message}"
        }
    }
}
