package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.SchemaConstants
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.LanguageHandlerRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.MethodInfo
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.SuperMethodInfo
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.SuperMethodsResult
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Tool for finding super methods across multiple languages.
 *
 * Supports: Java, Kotlin, Python, JavaScript, TypeScript
 *
 * Delegates to language-specific handlers via [LanguageHandlerRegistry].
 */
class FindSuperMethodsTool : AbstractMcpTool() {

    override val name = ToolNames.FIND_SUPER_METHODS

    override val description = """
        Finds the complete inheritance hierarchy for a method - all parent methods it overrides or implements.

        SUPPORTED LANGUAGES: Java, Kotlin, Python, JavaScript, TypeScript

        Use this tool when you need to:
        - Find which interface method an implementation overrides
        - Navigate to the original method declaration in a parent class
        - Understand the full inheritance chain for a method with @Override
        - See all levels of method overriding (not just immediate parent)

        The position (line/column) can be anywhere within the method - on the name,
        inside the body, or on the @Override annotation. The tool automatically
        finds the enclosing method.

        Returns the full hierarchy chain ordered from immediate parent (depth=1) to root.

        EXAMPLE Java: {"file": "src/main/java/com/example/UserServiceImpl.java", "line": 25, "column": 10}
        EXAMPLE Python: {"file": "src/services/user_service.py", "line": 15, "column": 5}
        EXAMPLE TypeScript: {"file": "src/services/UserService.ts", "line": 20, "column": 10}
    """.trimIndent()

    override val inputSchema: JsonObject = buildJsonObject {
        put(SchemaConstants.TYPE, SchemaConstants.TYPE_OBJECT)
        putJsonObject(SchemaConstants.PROPERTIES) {
            putJsonObject(ParamNames.PROJECT_PATH) {
                put(SchemaConstants.TYPE, SchemaConstants.TYPE_STRING)
                put(SchemaConstants.DESCRIPTION, SchemaConstants.DESC_PROJECT_PATH)
            }
            putJsonObject(ParamNames.FILE) {
                put(SchemaConstants.TYPE, SchemaConstants.TYPE_STRING)
                put(SchemaConstants.DESCRIPTION, SchemaConstants.DESC_FILE)
            }
            putJsonObject(ParamNames.LINE) {
                put(SchemaConstants.TYPE, SchemaConstants.TYPE_INTEGER)
                put(SchemaConstants.DESCRIPTION, "1-based line number. Can be any line within the method.")
            }
            putJsonObject(ParamNames.COLUMN) {
                put(SchemaConstants.TYPE, SchemaConstants.TYPE_INTEGER)
                put(SchemaConstants.DESCRIPTION, "1-based column number. Can be any position within the method.")
            }
        }
        putJsonArray(SchemaConstants.REQUIRED) {
            add(JsonPrimitive(ParamNames.FILE))
            add(JsonPrimitive(ParamNames.LINE))
            add(JsonPrimitive(ParamNames.COLUMN))
        }
    }

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val file = arguments[ParamNames.FILE]?.jsonPrimitive?.content
            ?: return createErrorResult("Missing required parameter: ${ParamNames.FILE}")
        val line = arguments[ParamNames.LINE]?.jsonPrimitive?.int
            ?: return createErrorResult("Missing required parameter: ${ParamNames.LINE}")
        val column = arguments[ParamNames.COLUMN]?.jsonPrimitive?.int
            ?: return createErrorResult("Missing required parameter: ${ParamNames.COLUMN}")

        requireSmartMode(project)

        return readAction {
            val element = findPsiElement(project, file, line, column)
                ?: return@readAction createErrorResult("No element found at $file:$line:$column")

            // Find appropriate handler for this element's language
            val handler = LanguageHandlerRegistry.getSuperMethodsHandler(element)
            if (handler == null) {
                return@readAction createErrorResult(
                    "No super methods handler available for language: ${element.language.id}. " +
                    "Supported languages: ${LanguageHandlerRegistry.getSupportedLanguagesForSuperMethods()}"
                )
            }

            val superMethodsData = handler.findSuperMethods(element, project)
            if (superMethodsData == null) {
                return@readAction createErrorResult(
                    "No method found at position. Ensure the position is within a method declaration or body."
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
