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
        val req = com.example.data.agent.runtime.UnifiedExecutionRequest(
            capabilityId = "memory_search",
            parameters = parameters
        )
        val res = com.example.data.agent.runtime.UnifiedExecutionFabric.instance.execute(req)
        return if (res.status == com.example.data.agent.runtime.UnifiedExecutionStatus.COMPLETED || res.status == com.example.data.agent.runtime.UnifiedExecutionStatus.VERIFIED) {
            res.output
        } else {
            "Memory Search Execution Error [${res.status}]: ${res.error ?: res.output}"
        }
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
        val req = com.example.data.agent.runtime.UnifiedExecutionRequest(
            capabilityId = "device_control",
            parameters = parameters
        )
        val res = com.example.data.agent.runtime.UnifiedExecutionFabric.instance.execute(req)
        return if (res.status == com.example.data.agent.runtime.UnifiedExecutionStatus.COMPLETED || res.status == com.example.data.agent.runtime.UnifiedExecutionStatus.VERIFIED) {
            res.output
        } else {
            "Device Action Execution Error [${res.status}]: ${res.error ?: res.output}"
        }
    }
}

class TerminalTool : WastiTool {
    override val definition = ToolDefinition(
        id = "terminal",
        name = "Terminal & Process Executor",
        category = "Execution",
        description = "Executes process commands within the sandboxed Wasti workspace environment."
    )

    override suspend fun execute(parameters: Map<String, Any>): String {
        val req = com.example.data.agent.runtime.UnifiedExecutionRequest(
            capabilityId = "terminal",
            parameters = parameters
        )
        val res = com.example.data.agent.runtime.UnifiedExecutionFabric.instance.execute(req)
        return if (res.status == com.example.data.agent.runtime.UnifiedExecutionStatus.COMPLETED || res.status == com.example.data.agent.runtime.UnifiedExecutionStatus.VERIFIED) {
            res.output
        } else {
            "Terminal Execution Error [${res.status}]: ${res.error ?: res.output}"
        }
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
        val req = com.example.data.agent.runtime.UnifiedExecutionRequest(
            capabilityId = "search_web",
            parameters = mapOf("query" to query)
        )
        val res = com.example.data.agent.runtime.UnifiedExecutionFabric.instance.execute(req)
        return if (res.status == com.example.data.agent.runtime.UnifiedExecutionStatus.COMPLETED || res.status == com.example.data.agent.runtime.UnifiedExecutionStatus.VERIFIED) {
            res.output
        } else {
            com.example.data.core.WastiCore.processLeadRadarExecution("lead radar for $query")
        }
    }
}

object ToolRegistry {
    private val toolsMap = java.util.concurrent.ConcurrentHashMap<String, WastiTool>()

    init {
        registerTool(MemorySearchTool())
        registerTool(DeviceControlTool())
        registerTool(TerminalTool())
        registerTool(LeadRadarTool())
    }

    fun registerTool(tool: WastiTool) {
        toolsMap[tool.definition.id] = tool
    }

    fun unregisterTool(id: String) {
        toolsMap.remove(id)
    }

    fun getTool(id: String): WastiTool? = toolsMap[id]

    fun getAllTools(): List<ToolDefinition> = toolsMap.values.map { it.definition }

    fun getAllWastiTools(): List<WastiTool> = toolsMap.values.toList()

    suspend fun executeTool(id: String, parameters: Map<String, Any>): String {
        val tool = toolsMap[id] ?: return "Error: Tool [$id] not found in ToolRegistry."
        return try {
            tool.execute(parameters)
        } catch (e: Exception) {
            "Tool Execution Error [$id]: ${e.message}"
        }
    }
}
