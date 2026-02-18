package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.LanguageHandlerRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.McpServerService
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolDefinition
import com.github.hechtcarmel.jetbrainsindexmcpplugin.settings.McpSettings
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.intelligence.GetDiagnosticsTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.FindClassTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.FindDefinitionTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.FindFileTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.FindUsagesTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.ReadFileTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.SearchTextTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project.GetIndexStatusTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring.RenameSymbolTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.JavaPluginDetector
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
 * The registry automatically registers built-in tools based on IDE capabilities.
 *
 * ### Universal Tools (All JetBrains IDEs)
 *
 * These tools work in all JetBrains IDEs (IntelliJ, PyCharm, WebStorm, GoLand, etc.):
 *
 * - `ide_find_references` - Find all usages of a symbol
 * - `ide_find_definition` - Find symbol definition location
 * - `ide_find_class` - Class search using CLASS_EP_NAME index
 * - `ide_find_file` - File search using FILE_EP_NAME index
 * - `ide_search_text` - Text search using word index
 * - `ide_diagnostics` - Analyze code for problems and available intentions
 * - `ide_index_status` - Check indexing status
 *
 * ### Language-Specific Navigation Tools
 *
 * These tools support multiple languages (Java, Kotlin, Python, JavaScript/TypeScript, PHP, Rust)
 * and are registered when at least one language handler is available:
 *
 * - `ide_type_hierarchy` - Get class inheritance hierarchy
 * - `ide_call_hierarchy` - Analyze method call relationships
 * - `ide_find_implementations` - Find interface/method implementations
 * - `ide_find_symbol` - Search for symbols by name
 * - `ide_find_super_methods` - Find methods that a method overrides
 *
 * ### Universal Refactoring Tools
 *
 * - `ide_refactor_rename` - Rename symbol (works across ALL languages via RenameProcessor)
 *
 * ### Java-Specific Refactoring Tools (IntelliJ IDEA & Android Studio Only)
 *
 * - `ide_refactor_safe_delete` - Safely delete element (requires Java plugin)
 *
 * ## Custom Tool Registration
 *
 * Custom tools can be registered programmatically using [register].
 *
 * @see McpTool
 * @see McpServerService
 * @see JavaPluginDetector
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
     * Respects user settings for disabled tools.
     *
     * @return List of enabled tool definitions with name, description, and schema
     */
    fun getToolDefinitions(): List<ToolDefinition> {
        val settings = McpSettings.getInstance()
        return tools.values
            .filter { settings.isToolEnabled(it.name) }
            .map { tool ->
                ToolDefinition(
                    name = tool.name,
                    description = tool.description,
                    inputSchema = tool.inputSchema
                )
            }
    }

    /**
     * Gets ALL tool definitions regardless of enabled/disabled state.
     * Used by settings UI to display all available tools.
     *
     * @return List of all tool definitions
     */
    fun getAllToolDefinitions(): List<ToolDefinition> {
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
     * Tools are registered conditionally based on IDE capabilities:
     * - Universal tools are always registered
     * - Language-specific navigation tools are registered when any language handler is available
     * - Refactoring tools are only registered when the Java plugin is available
     */
    fun registerBuiltInTools() {
        // Initialize language handlers first
        LanguageHandlerRegistry.registerHandlers()

        // Universal tools - work in all JetBrains IDEs
        registerUniversalTools()

        // Language-specific navigation tools - registered when handlers are available
        registerLanguageNavigationTools()

        // Java-specific refactoring tools - only available when Java plugin is present
        if (JavaPluginDetector.isJavaPluginAvailable) {
            registerJavaRefactoringTools()
        }

        LOG.info("Registered ${tools.size} built-in MCP tools")
        logAvailableLanguages()
    }

    private fun logAvailableLanguages() {
        val typeHierarchyLangs = LanguageHandlerRegistry.getSupportedLanguagesForTypeHierarchy()
        val implementationLangs = LanguageHandlerRegistry.getSupportedLanguagesForImplementations()
        val callHierarchyLangs = LanguageHandlerRegistry.getSupportedLanguagesForCallHierarchy()
        val symbolSearchLangs = LanguageHandlerRegistry.getSupportedLanguagesForSymbolSearch()
        val superMethodsLangs = LanguageHandlerRegistry.getSupportedLanguagesForSuperMethods()
        val structureLangs = LanguageHandlerRegistry.getSupportedLanguagesForStructure()

        LOG.info("Language support - TypeHierarchy: $typeHierarchyLangs, " +
            "Implementations: $implementationLangs, " +
            "CallHierarchy: $callHierarchyLangs, " +
            "SymbolSearch: $symbolSearchLangs, " +
            "SuperMethods: $superMethodsLangs, " +
            "Structure: $structureLangs")
    }

    /**
     * Registers universal tools that work in all JetBrains IDEs.
     *
     * These tools use only platform APIs (com.intellij.modules.platform)
     * and do not depend on Java-specific PSI classes.
     */
    private fun registerUniversalTools() {
        // Navigation tools (universal)
        register(FindUsagesTool())
        register(FindDefinitionTool())

        // Intelligence tools
        register(GetDiagnosticsTool())

        // Project tools
        register(GetIndexStatusTool())

        // Refactoring tools (universal - uses platform RenameProcessor)
        register(RenameSymbolTool())

        // Fast search tools (universal)
        register(FindClassTool())
        register(FindFileTool())
        register(SearchTextTool())
        register(ReadFileTool())

        LOG.info("Registered universal tools (available in all JetBrains IDEs)")
    }

    /**
     * Registers language-specific navigation tools.
     *
     * These tools delegate to language handlers and support multiple languages
     * (Java, Kotlin, Python, JavaScript/TypeScript, PHP, Rust).
     *
     * Tools are registered when at least one language handler is available
     * for the tool's functionality.
     */
    private fun registerLanguageNavigationTools() {
        try {
            // Type hierarchy - requires at least one TypeHierarchyHandler
            if (LanguageHandlerRegistry.hasTypeHierarchyHandlers()) {
                val toolClass = Class.forName("com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.TypeHierarchyTool")
                register(toolClass.getDeclaredConstructor().newInstance() as McpTool)
            }

            // Find implementations - requires at least one ImplementationsHandler
            if (LanguageHandlerRegistry.hasImplementationsHandlers()) {
                val toolClass = Class.forName("com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.FindImplementationsTool")
                register(toolClass.getDeclaredConstructor().newInstance() as McpTool)
            }

            // Call hierarchy - requires at least one CallHierarchyHandler
            if (LanguageHandlerRegistry.hasCallHierarchyHandlers()) {
                val toolClass = Class.forName("com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.CallHierarchyTool")
                register(toolClass.getDeclaredConstructor().newInstance() as McpTool)
            }

            // Find symbol - requires at least one SymbolSearchHandler
            if (LanguageHandlerRegistry.hasSymbolSearchHandlers()) {
                val toolClass = Class.forName("com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.FindSymbolTool")
                register(toolClass.getDeclaredConstructor().newInstance() as McpTool)
            }

            // Find super methods - requires at least one SuperMethodsHandler
            if (LanguageHandlerRegistry.hasSuperMethodsHandlers()) {
                val toolClass = Class.forName("com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.FindSuperMethodsTool")
                register(toolClass.getDeclaredConstructor().newInstance() as McpTool)
            }

            // File structure - requires at least one StructureHandler
            if (LanguageHandlerRegistry.hasStructureHandlers()) {
                val toolClass = Class.forName("com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.FileStructureTool")
                register(toolClass.getDeclaredConstructor().newInstance() as McpTool)
            }

            LOG.info("Registered language navigation tools")
        } catch (e: Exception) {
            LOG.warn("Failed to register language navigation tools: ${e.message}")
        }
    }

    /**
     * Registers Java-specific refactoring tools.
     *
     * These tools use Java-specific refactoring APIs and are only available
     * when the Java plugin is present (IntelliJ IDEA, Android Studio).
     *
     * Note: RenameSymbolTool has been moved to registerUniversalTools() as it
     * now uses the platform-level RenameProcessor which works across all languages.
     *
     * IMPORTANT: This method must only be called after checking [JavaPluginDetector.isJavaPluginAvailable]
     */
    private fun registerJavaRefactoringTools() {
        try {
            val refactoringToolClasses = listOf(
                // RenameSymbolTool moved to registerUniversalTools() - now language-agnostic
                "com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring.SafeDeleteTool"
            )

            for (className in refactoringToolClasses) {
                val toolClass = Class.forName(className)
                val tool = toolClass.getDeclaredConstructor().newInstance() as McpTool
                register(tool)
            }

            LOG.info("Registered Java-specific refactoring tools")
        } catch (e: Exception) {
            LOG.warn("Failed to register Java refactoring tools: ${e.message}")
        }
    }
}
