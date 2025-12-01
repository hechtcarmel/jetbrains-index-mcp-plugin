package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.LanguageHandlerRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.TypeElementData
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.TypeElement
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.TypeHierarchyResult
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Tool for retrieving type hierarchies across multiple languages.
 *
 * Supports: Java, Kotlin, Python, JavaScript, TypeScript
 *
 * Delegates to language-specific handlers via [LanguageHandlerRegistry].
 */
class TypeHierarchyTool : AbstractMcpTool() {

    override val name = "ide_type_hierarchy"

    override val description = """
        Retrieves the complete type hierarchy for a class or interface, showing all inheritance relationships.

        SUPPORTED LANGUAGES: Java, Kotlin, Python, JavaScript, TypeScript

        INPUT OPTIONS (use one):
        - Option A: Provide className with the fully qualified class name (e.g., "com.example.MyClass")
        - Option B: Provide file + line + column to identify a class by source location

        RETURNS: The target class with full supertype chain (recursive) and all subtypes in the project.

        EXAMPLE with className: {"className": "com.example.service.UserService"}
        EXAMPLE with location: {"file": "src/main/java/com/example/MyClass.java", "line": 10, "column": 14}
        EXAMPLE Python: {"file": "src/services/user_service.py", "line": 5, "column": 7}
        EXAMPLE TypeScript: {"file": "src/components/Button.tsx", "line": 10, "column": 14}
    """.trimIndent()

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("project_path") {
                put("type", "string")
                put("description", "Absolute path to project root. Only needed when multiple projects are open in IDE.")
            }
            putJsonObject("className") {
                put("type", "string")
                put("description", "Fully qualified class name (e.g., 'com.example.MyClass'). RECOMMENDED - use this if you know the class name.")
            }
            putJsonObject("file") {
                put("type", "string")
                put("description", "Path to file relative to project root (e.g., 'src/main/java/com/example/MyClass.java'). Use with line and column.")
            }
            putJsonObject("line") {
                put("type", "integer")
                put("description", "1-based line number where the class is defined. Required if using file parameter.")
            }
            putJsonObject("column") {
                put("type", "integer")
                put("description", "1-based column number. Required if using file parameter.")
            }
        }
        putJsonArray("required") {
            // Empty because either className OR (file+line+column) must be provided
        }
    }

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        requireSmartMode(project)

        val className = arguments["className"]?.jsonPrimitive?.content
        val file = arguments["file"]?.jsonPrimitive?.content

        return readAction {
            ProgressManager.checkCanceled() // Allow cancellation

            val element = resolveTargetElement(project, arguments)
            if (element == null) {
                val errorMsg = when {
                    className != null -> "Class '$className' not found in project '${project.name}'. Verify the fully qualified name is correct and the class is part of this project."
                    file != null -> "No class found at the specified file/line/column position."
                    else -> "Provide either 'className' (e.g., 'com.example.MyClass') or 'file' + 'line' + 'column'."
                }
                return@readAction createErrorResult(errorMsg)
            }

            // Find appropriate handler for this element's language
            val handler = LanguageHandlerRegistry.getTypeHierarchyHandler(element)
            if (handler == null) {
                return@readAction createErrorResult(
                    "No type hierarchy handler available for language: ${element.language.id}. " +
                    "Supported languages: ${LanguageHandlerRegistry.getSupportedLanguagesForTypeHierarchy()}"
                )
            }

            ProgressManager.checkCanceled() // Allow cancellation before heavy operation

            val hierarchyData = handler.getTypeHierarchy(element, project)
            if (hierarchyData == null) {
                return@readAction createErrorResult("No class/type found at the specified position.")
            }

            // Convert handler result to tool result
            createJsonResult(TypeHierarchyResult(
                element = convertToTypeElement(hierarchyData.element),
                supertypes = hierarchyData.supertypes.map { convertToTypeElement(it) },
                subtypes = hierarchyData.subtypes.map { convertToTypeElement(it) }
            ))
        }
    }

    private fun resolveTargetElement(project: Project, arguments: JsonObject): PsiElement? {
        // Try className first (Java/Kotlin specific)
        val className = arguments["className"]?.jsonPrimitive?.content
        if (className != null) {
            return findClassByName(project, className)
        }

        // Otherwise use file/line/column (works for all languages)
        val file = arguments["file"]?.jsonPrimitive?.content ?: return null
        val line = arguments["line"]?.jsonPrimitive?.int ?: return null
        val column = arguments["column"]?.jsonPrimitive?.int ?: return null

        return findPsiElement(project, file, line, column)
    }

    /**
     * Converts handler TypeElementData to tool TypeElement.
     */
    private fun convertToTypeElement(data: TypeElementData): TypeElement {
        return TypeElement(
            name = data.name,
            file = data.file,
            kind = data.kind,
            language = data.language,
            supertypes = data.supertypes?.map { convertToTypeElement(it) }
        )
    }
}
