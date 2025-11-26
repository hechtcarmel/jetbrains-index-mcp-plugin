package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolDefinition
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.intelligence.GetSymbolInfoTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.FindUsagesTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.GoToDefinitionTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project.GetIndexStatusTool
import com.intellij.openapi.diagnostic.logger
import java.util.concurrent.ConcurrentHashMap

class ToolRegistry {

    companion object {
        private val LOG = logger<ToolRegistry>()
    }

    private val tools = ConcurrentHashMap<String, McpTool>()

    fun register(tool: McpTool) {
        tools[tool.name] = tool
        LOG.info("Registered MCP tool: ${tool.name}")
    }

    fun unregister(toolName: String) {
        tools.remove(toolName)
        LOG.info("Unregistered MCP tool: $toolName")
    }

    fun getTool(name: String): McpTool? {
        return tools[name]
    }

    fun getAllTools(): List<McpTool> {
        return tools.values.toList()
    }

    fun getToolDefinitions(): List<ToolDefinition> {
        return tools.values.map { tool ->
            ToolDefinition(
                name = tool.name,
                description = tool.description,
                inputSchema = tool.inputSchema
            )
        }
    }

    fun registerBuiltInTools() {
        // Navigation tools
        register(FindUsagesTool())
        register(GoToDefinitionTool())

        // Intelligence tools
        register(GetSymbolInfoTool())

        // Project tools
        register(GetIndexStatusTool())

        LOG.info("Registered ${tools.size} built-in MCP tools")
    }

    fun registerExtensionTools() {
        // This will be implemented to load tools from extension points
        // For now, it's a placeholder
    }
}
