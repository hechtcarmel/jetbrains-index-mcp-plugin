package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolDefinition
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.intelligence.GetDiagnosticsTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.CallHierarchyTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.FindImplementationsTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.FindUsagesTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.FindDefinitionTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.TypeHierarchyTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project.GetIndexStatusTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring.RenameSymbolTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring.SafeDeleteTool
import com.intellij.openapi.diagnostic.logger
import java.util.concurrent.ConcurrentHashMap

/**
 * Registry for MCP tools available to AI assistants.
 *
 * The registry manages the lifecycle of tools and provides thread-safe access
 * for tool lookup and definition generation.
 *
 * ## Built-in Tools
 *
 * The registry automatically registers 9 built-in tools in these categories:
 *
 * **Navigation:**
 * - `ide_find_references` - Find all usages of a symbol
 * - `ide_find_definition` - Find symbol definition location
 * - `ide_type_hierarchy` - Get class inheritance hierarchy
 * - `ide_call_hierarchy` - Analyze method call relationships
 * - `ide_find_implementations` - Find interface/method implementations
 *
 * **Intelligence:**
 * - `ide_diagnostics` - Analyze code for problems and available intentions
 *
 * **Project:**
 * - `ide_index_status` - Check indexing status
 *
 * **Refactoring:**
 * - `ide_refactor_rename` - Rename symbol
 * - `ide_refactor_safe_delete` - Safely delete element
 *
 * ## Custom Tool Registration
 *
 * Custom tools can be registered programmatically using [register].
 *
 * @see McpTool
 * @see McpServerService
 */
class ToolRegistry {

    companion object {
        private val LOG = logger<ToolRegistry>()
    }

    private val tools = ConcurrentHashMap<String, McpTool>()

    /**
     * Registers a tool with the registry.
     *
     * If a tool with the same name already exists, it will be replaced.
     *
     * @param tool The tool to register
     */
    fun register(tool: McpTool) {
        tools[tool.name] = tool
        LOG.info("Registered MCP tool: ${tool.name}")
    }

    /**
     * Removes a tool from the registry.
     *
     * @param toolName The name of the tool to remove
     */
    fun unregister(toolName: String) {
        tools.remove(toolName)
        LOG.info("Unregistered MCP tool: $toolName")
    }

    /**
     * Gets a tool by name.
     *
     * @param name The tool name (e.g., `ide_find_references`)
     * @return The tool, or null if not found
     */
    fun getTool(name: String): McpTool? {
        return tools[name]
    }

    /**
     * Returns all registered tools.
     *
     * @return List of all tools
     */
    fun getAllTools(): List<McpTool> {
        return tools.values.toList()
    }

    /**
     * Gets tool definitions for the MCP `tools/list` response.
     *
     * @return List of tool definitions with name, description, and schema
     */
    fun getToolDefinitions(): List<ToolDefinition> {
        return tools.values.map { tool ->
            ToolDefinition(
                name = tool.name,
                description = tool.description,
                inputSchema = tool.inputSchema
            )
        }
    }

    /**
     * Registers all built-in tools.
     *
     * This is called automatically during [McpServerService] initialization.
     */
    fun registerBuiltInTools() {
        // Navigation tools
        register(FindUsagesTool())
        register(FindDefinitionTool())
        register(TypeHierarchyTool())
        register(CallHierarchyTool())
        register(FindImplementationsTool())

        // Intelligence tools
        register(GetDiagnosticsTool())

        // Project tools
        register(GetIndexStatusTool())

        // Refactoring tools
        register(RenameSymbolTool())
        register(SafeDeleteTool())

        LOG.info("Registered ${tools.size} built-in MCP tools")
    }
}
