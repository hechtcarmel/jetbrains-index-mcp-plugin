package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.JsonObject

/**
 * Interface for MCP (Model Context Protocol) tools that can be invoked by AI assistants.
 *
 * MCP tools provide specific IDE functionality to external clients through the MCP protocol.
 * Each tool has a unique name, description, input schema, and execution logic.
 *
 * ## Implementing a Custom Tool
 *
 * To create a custom tool:
 * 1. Extend [AbstractMcpTool] for common functionality
 * 2. Define the tool's [name], [description], and [inputSchema]
 * 3. Implement the [AbstractMcpTool.doExecute] method with your tool's logic
 *
 * **Important**: Do not override [execute] directly. The base class handles PSI synchronization
 * automatically before calling your [AbstractMcpTool.doExecute] implementation.
 *
 * Example:
 * ```kotlin
 * class MyCustomTool : AbstractMcpTool() {
 *     override val name = "ide_my_custom_tool"
 *     override val description = "Does something useful"
 *     override val inputSchema = buildJsonObject {
 *         put("type", "object")
 *         putJsonObject("properties") {
 *             putJsonObject("param1") {
 *                 put("type", "string")
 *                 put("description", "A required parameter")
 *             }
 *         }
 *         putJsonArray("required") { add(JsonPrimitive("param1")) }
 *     }
 *
 *     override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
 *         val param1 = arguments["param1"]?.jsonPrimitive?.content
 *             ?: return createErrorResult("Missing required parameter: param1")
 *         // ... tool logic
 *         return createSuccessResult("Operation completed")
 *     }
 * }
 * ```
 *
 * ## Tool Registration
 *
 * Tools are registered through [ToolRegistry.registerBuiltInTools] and then
 * adapted to JetBrains' built-in MCP server at runtime.
 *
 * @see AbstractMcpTool
 * @see AbstractMcpTool.doExecute
 * @see ToolRegistry
 * @see ToolCallResult
 */
interface McpTool {
    /**
     * The unique identifier for this tool.
     *
     * Tool names should follow the convention `ide_<category>_<action>` for consistency.
     * Examples: `ide_find_references`, `ide_refactor_rename`, `ide_get_completions`
     *
     * This name is used by MCP clients to invoke the tool.
     */
    val name: String

    /**
     * A human-readable description of what the tool does.
     *
     * This description is sent to MCP clients and helps AI assistants understand
     * when and how to use the tool. Include:
     * - What the tool does
     * - When to use it (use cases)
     * - What it returns
     *
     * Example:
     * ```
     * Finds all references to a symbol across the entire project.
     * Use when locating where a method, class, or variable is used.
     * Returns file locations with line numbers and context snippets.
     * ```
     */
    val description: String

    /**
     * JSON Schema defining the tool's input parameters.
     *
     * The schema should follow JSON Schema specification and define:
     * - Parameter types and descriptions
     * - Required vs optional parameters
     * - Validation constraints
     *
     * All tools should include `project_path` as an optional parameter
     * to support multi-project scenarios.
     *
     * @see com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
     */
    val inputSchema: JsonObject

    /**
     * Executes the tool with the given arguments.
     *
     * This method is called when an MCP client invokes the tool.
     *
     * **Implementation Note**: When extending [AbstractMcpTool], do not override this method.
     * Instead, override [AbstractMcpTool.doExecute]. The base class implements this method
     * to handle PSI synchronization automatically before delegating to `doExecute`.
     *
     * @param project The IntelliJ project context (already resolved from project_path if provided)
     * @param arguments The tool arguments as a JSON object matching [inputSchema]
     * @return A [ToolCallResult] containing the operation result or error information
     *
     * @see AbstractMcpTool.doExecute
     */
    suspend fun execute(project: Project, arguments: JsonObject): ToolCallResult
}
