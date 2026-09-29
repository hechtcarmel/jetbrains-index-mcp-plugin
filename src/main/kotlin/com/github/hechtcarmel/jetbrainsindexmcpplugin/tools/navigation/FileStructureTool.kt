package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.LanguageHandlerRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.SymbolIdRegistry
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.FileStructureResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.StructureNode
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.TreeFormatter
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Tool for analyzing the hierarchical structure of source files.
 *
 * Provides a tree-formatted view of file structure similar to IDE's Structure view,
 * showing classes, methods, fields, Markdown headings, and their nesting relationships.
 *
 * Supports: Java, Kotlin, Python, JavaScript, TypeScript, PHP, Markdown, Scala
 */
class FileStructureTool : AbstractMcpTool() {

    override val name = "ide_file_structure"

    override val description = """
        Get the hierarchical structure of a source file (similar to IDE's Structure view).

        Shows classes, methods, fields, functions, PHP namespaces, constants, enum cases, Markdown headings, and their nesting relationships in a tree format.

        Supports: Java, Kotlin, Python, JavaScript, TypeScript, PHP, Markdown, Scala

        Returns: The legacy formatted tree string. Set includeNodes=true for structured nodes;
        set includeSymbolIds=true to bind exact handles for those nodes. Nodes and handles are
        opt-in to keep ordinary outline responses small and avoid registry churn.

        Parameters: file (required) - Path relative to project root

        Example: {"file": "src/main/java/com/example/MyClass.java", "includeNodes": true}
    """.trimIndent()

    override val inputSchema: ToolSchema = SchemaBuilder.tool()
        .projectPath()
        .file(description = "Path to file relative to project root (e.g., 'src/main/java/com/example/MyClass.java'). REQUIRED.")
        .booleanProperty(ParamNames.INCLUDE_NODES, "Include structured declaration nodes. Default: false.")
        .booleanProperty(ParamNames.INCLUDE_SYMBOL_IDS, "Bind exact symbolId handles for returned nodes; implies includeNodes. Default: false.")
        .intProperty(ParamNames.MAX_SYMBOL_IDS, "Maximum handles to allocate when includeSymbolIds=true (1–100). Default: 100.")
        .build()

    override suspend fun doExecute(project: Project, arguments: JsonObject): CallToolResult {
        val file = requiredStringArg(arguments, "file").getOrElse {
            return createErrorResult(it.message ?: "Missing required parameter: file")
        }

        return suspendingReadAction {
            val psiFile = getPsiFile(project, file)
                ?: return@suspendingReadAction createErrorResult("File not found: $file")

            // Get structure handler for this file's language
            val handler = LanguageHandlerRegistry.getStructureHandler(psiFile)
                ?: return@suspendingReadAction createErrorResult(
                    "Language not supported for file structure. " +
                    "Supported languages: ${LanguageHandlerRegistry.getSupportedLanguagesForStructure().joinToString(", ")}"
                )

            // Extract structure
            val nodes = handler.getFileStructure(psiFile, project)

            // Preserve the previous human-readable payload even when no nodes were found.
            val treeString = if (nodes.isEmpty()) {
                "File is empty or has no parseable structure.\n\n" +
                    "File: ${psiFile.name}\n" +
                    "Language: ${psiFile.language.id}"
            } else {
                TreeFormatter.format(nodes, psiFile.name, psiFile.language.id)
            }
            val includeSymbolIds = arguments[ParamNames.INCLUDE_SYMBOL_IDS]
                ?.jsonPrimitive?.booleanOrNull ?: false
            val includeNodes = includeSymbolIds || (arguments[ParamNames.INCLUDE_NODES]
                ?.jsonPrimitive?.booleanOrNull ?: false)
            val requestedBudget = if (includeSymbolIds) {
                arguments[ParamNames.MAX_SYMBOL_IDS]
                    ?.jsonPrimitive?.intOrNull ?: DEFAULT_HANDLE_BUDGET
            } else {
                DEFAULT_HANDLE_BUDGET
            }
            if (includeSymbolIds && requestedBudget !in 1..MAX_HANDLE_BUDGET) {
                return@suspendingReadAction createErrorResult(
                    "maxSymbolIds must be between 1 and $MAX_HANDLE_BUDGET"
                )
            }
            val bindingBudget = StructureBindingBudget(
                remaining = if (includeSymbolIds) requestedBudget else 0
            )
            val structuredNodes = if (includeNodes) {
                bindStructureNodes(project, nodes, bindingBudget)
            } else {
                emptyList()
            }

            createJsonResult(FileStructureResult(
                file = file,
                language = psiFile.language.id,
                structure = treeString,
                nodes = structuredNodes,
                symbolIdsTruncated = includeSymbolIds && bindingBudget.omitted > 0,
                symbolIdsOmitted = if (includeSymbolIds) bindingBudget.omitted else 0
            ))
        }
    }

    /**
     * Attaches handles while the exact PSI elements produced by the language handler are still
     * available. Re-resolving a node later by line would be ambiguous for overloads and nested
     * declarations that share a line.
     */
    private class StructureBindingBudget(var remaining: Int, var omitted: Int = 0)

    private fun bindStructureNodes(
        project: Project,
        nodes: List<StructureNode>,
        budget: StructureBindingBudget
    ): List<StructureNode> =
        nodes.map { node ->
            // Preorder gives containers a handle before their descendants. Keep the complete
            // outline, but never evict IDs from this same response by overfilling the registry.
            val target = node.pointerTarget?.takeIf { it.isValid }
            val symbolId = if (target == null) null else if (budget.remaining > 0) {
                budget.remaining--
                bindNavigationSymbolId(project, target)
            } else {
                budget.omitted++
                null
            }
            node.copy(
                children = bindStructureNodes(project, node.children, budget),
                symbolId = symbolId
            )
        }
}

private const val DEFAULT_HANDLE_BUDGET = 100
private const val MAX_HANDLE_BUDGET = 100
