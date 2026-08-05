package com.example.data.ai.engine

import com.example.data.ai.model.ToolCallDefinition
import com.example.data.ai.model.ToolCallResult
import java.util.concurrent.ConcurrentHashMap

class ToolCallingEngine {
    private val toolRegistry = ConcurrentHashMap<String, ToolCallDefinition>()
    private val toolExecutors = ConcurrentHashMap<String, suspend (String) -> String>()

    fun registerTool(
        definition: ToolCallDefinition,
        executor: suspend (paramsJson: String) -> String
    ) {
        toolRegistry[definition.name] = definition
        toolExecutors[definition.name] = executor
    }

    fun getRegisteredTools(): List<ToolCallDefinition> = toolRegistry.values.toList()

    suspend fun executeTool(toolName: String, paramsJson: String): ToolCallResult {
        val executor = toolExecutors[toolName]
        if (executor == null) {
            return ToolCallResult(toolName = toolName, success = false, resultJson = "{\"error\": \"Tool $toolName not registered\"}")
        }
        return try {
            val result = executor(paramsJson)
            ToolCallResult(toolName = toolName, success = true, resultJson = result)
        } catch (e: Exception) {
            ToolCallResult(toolName = toolName, success = false, resultJson = "{\"error\": \"${e.message}\"}")
        }
    }
}
