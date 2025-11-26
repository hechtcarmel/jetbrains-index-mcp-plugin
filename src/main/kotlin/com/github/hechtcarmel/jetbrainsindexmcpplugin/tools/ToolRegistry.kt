package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolDefinition
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.intelligence.ApplyQuickFixTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.intelligence.GetCompletionsTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.intelligence.GetInspectionsTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.intelligence.GetQuickFixesTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.intelligence.GetSymbolInfoTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.CallHierarchyTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.FindImplementationsTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.FindUsagesTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.FindDefinitionTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.TypeHierarchyTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project.GetDependenciesTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project.GetFileStructureTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project.GetIndexStatusTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project.GetProjectStructureTool
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
        register(FindDefinitionTool())
        register(TypeHierarchyTool())
        register(CallHierarchyTool())
        register(FindImplementationsTool())

        // Intelligence tools
        register(GetSymbolInfoTool())
        register(GetCompletionsTool())
        register(GetInspectionsTool())
        register(GetQuickFixesTool())
        register(ApplyQuickFixTool())

        // Project tools
        register(GetIndexStatusTool())
        register(GetFileStructureTool())
        register(GetProjectStructureTool())
        register(GetDependenciesTool())

        LOG.info("Registered ${tools.size} built-in MCP tools")
    }

    fun registerExtensionTools() {
        // This will be implemented to load tools from extension points
        // For now, it's a placeholder
    }
}
