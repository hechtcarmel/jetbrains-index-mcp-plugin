package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ErrorMessages
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.LanguageHandlerRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.MethodInfo
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.SuperMethodInfo
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.SuperMethodsResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.JsonObject

/**
 * Tool for finding super methods across multiple languages.
 *
 * Supports: Java, Kotlin, Python, JavaScript, TypeScript, PHP, Rust
 *
 * Delegates to language-specific handlers via [LanguageHandlerRegistry].
 */
class FindSuperMethodsTool : AbstractMcpTool() {

    override val name = ToolNames.FIND_SUPER_METHODS

    override val description = """
        Find parent methods that a method overrides or implements. Use to navigate up the inheritance chain—from implementation to interface, or from override to original declaration.

        Languages: Java, Kotlin, Python, JavaScript, TypeScript, PHP.

        NOT supported for Rust: Rust uses trait implementations rather than classical inheritance, so there are no "super methods" in the traditional sense. Use ide_find_definition or ide_type_hierarchy instead.

        Returns: full hierarchy chain from immediate parent (depth=1) to root, with file locations (line/column) and containing class info.

        Target (mutually exclusive):
        - file + line + column: position-based lookup (position can be anywhere within the method body)
        - language + symbol: fully qualified symbol reference

        Example: {"file": "src/UserServiceImpl.java", "line": 25, "column": 10}
        Example: {"language": "Java", "symbol": "com.example.UserServiceImpl#getUser(String)"}
    """.trimIndent()

    override val inputSchema: JsonObject = SchemaBuilder.tool()
        .projectPath()
        .file(required = false)
        .lineAndColumn(required = false)
        .languageAndSymbol(required = false)
        .build()

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        requireSmartMode(project)

        return suspendingReadAction {
            val element = resolveElementFromArguments(project, arguments).getOrElse {
                return@suspendingReadAction createErrorResult(it.message ?: ErrorMessages.COULD_NOT_RESOLVE_SYMBOL)
            }

            // Find appropriate handler for this element's language
            val handler = LanguageHandlerRegistry.getSuperMethodsHandler(element)
            if (handler == null) {
                return@suspendingReadAction createErrorResult(
                    "No super methods handler available for language: ${element.language.id}. " +
                    "Supported languages: ${LanguageHandlerRegistry.getSupportedLanguagesForSuperMethods()}"
                )
            }

            val superMethodsData = handler.findSuperMethods(element, project)
            if (superMethodsData == null) {
                val isSymbolMode = arguments[ParamNames.LANGUAGE] != null
                return@suspendingReadAction createErrorResult(
                    if (isSymbolMode) "No method found for the specified symbol. Ensure the symbol refers to a method declaration."
                    else "No method found at position. Ensure the position is within a method declaration or body."
                )
            }

            // Convert handler result to tool result
            createJsonResult(SuperMethodsResult(
                method = MethodInfo(
                    name = superMethodsData.method.name,
                    signature = superMethodsData.method.signature,
                    containingClass = superMethodsData.method.containingClass,
                    file = superMethodsData.method.file,
                    line = superMethodsData.method.line,
                    column = superMethodsData.method.column,
                    language = superMethodsData.method.language
                ),
                hierarchy = superMethodsData.hierarchy.map { superMethod ->
                    SuperMethodInfo(
                        name = superMethod.name,
                        signature = superMethod.signature,
                        containingClass = superMethod.containingClass,
                        containingClassKind = superMethod.containingClassKind,
                        file = superMethod.file,
                        line = superMethod.line,
                        column = superMethod.column,
                        isInterface = superMethod.isInterface,
                        depth = superMethod.depth,
                        language = superMethod.language
                    )
                },
                totalCount = superMethodsData.hierarchy.size
            ))
        }
    }
}
