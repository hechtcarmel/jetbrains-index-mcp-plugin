package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.BuiltInSearchScope
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.BuiltInSearchScopeResolver
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.LanguageHandlerRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.OptimizedSymbolSearch
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.TypeElementData
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.TypeElement
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.TypeHierarchyResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Tool for retrieving type hierarchies across multiple languages.
 *
 * Supports: Java, Kotlin, Python, JavaScript, TypeScript, PHP, Rust
 *
 * Delegates to language-specific handlers via [LanguageHandlerRegistry].
 */
class TypeHierarchyTool : AbstractMcpTool() {

    companion object {
        private val JS_TS_LANGUAGE_FILTER = setOf("JavaScript", "TypeScript")
        private val TYPE_SYMBOL_KINDS = setOf("CLASS", "INTERFACE")
    }

    override val name = "ide_type_hierarchy"

    override val description = """
        Get the complete inheritance hierarchy for a class or interface. Use when you need to understand class relationships, find parent classes, or discover all subclasses.

        Languages: Java, Kotlin, Python, JavaScript, TypeScript, PHP, Rust.

        Rust note: className parameter not supported for Rust; use file + line + column instead.

        Returns: target class info, full supertype chain (recursive), and all subtypes in the project.

        Parameters: Either className (e.g., "com.example.MyClass" for Java/PHP-style FQNs or "MyComponent" for JavaScript/TypeScript symbols) OR file + line + column. scope (optional, default: "project_files"; supported: project_files, project_and_libraries, project_production_files, project_test_files).

        Example: {"className": "com.example.UserService", "scope": "project_and_libraries"} or {"file": "src/MyClass.java", "line": 10, "column": 14}
    """.trimIndent()

    override val inputSchema: JsonObject = SchemaBuilder.tool()
        .projectPath()
        .stringProperty("className", "Fully qualified class name for JVM/PHP-style languages or simple class/interface name for JavaScript/TypeScript (e.g., 'com.example.MyClass', 'App\\\\Models\\\\User', or 'MyComponent').")
        .file(required = false, description = "Path to file relative to project root (e.g., 'src/main/java/com/example/MyClass.java'). Use with line and column.")
        .intProperty("line", "1-based line number where the class is defined. Required if using file parameter.")
        .intProperty("column", "1-based column number. Required if using file parameter.")
        .scopeProperty("Search scope. Default: project_files.")
        .booleanProperty(ParamNames.INCLUDE_GENERATED, "Include supertypes/subtypes defined in generated sources (KSP/Dagger/annotation-processor output). Default: true — keep generated types in the hierarchy.")
        .build()

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        requireSmartMode(project)

        val className = arguments["className"]?.jsonPrimitive?.content
        val file = arguments["file"]?.jsonPrimitive?.content
        val rawScope = rawScopeValue(arguments[ParamNames.SCOPE])
        val scope = try {
            BuiltInSearchScopeResolver.parse(arguments, BuiltInSearchScope.PROJECT_FILES)
        } catch (_: IllegalArgumentException) {
            return createInvalidScopeError(rawScope)
        } catch (_: IllegalStateException) {
            return createInvalidScopeError(rawScope)
        }
        val excludeGenerated = resolveExcludeGenerated(arguments, default = true)
        return suspendingReadAction {
            ProgressManager.checkCanceled() // Allow cancellation

            val element = resolveTargetElement(project, arguments, scope)
            if (element == null) {
                val errorMsg = when {
                    className != null -> "Class '$className' not found in project '${project.name}'. Verify the fully qualified name is correct and the class is part of this project."
                    file != null -> "No class found at the specified file/line/column position."
                    else -> "Provide either 'className' (e.g., 'com.example.MyClass') or 'file' + 'line' + 'column'."
                }
                return@suspendingReadAction createErrorResult(errorMsg)
            }

            // Find appropriate handler for this element's language
            val handler = LanguageHandlerRegistry.getTypeHierarchyHandler(element)
            if (handler == null) {
                return@suspendingReadAction createErrorResult(
                    "No type hierarchy handler available for language: ${element.language.id}. " +
                    "Supported languages: ${LanguageHandlerRegistry.getSupportedLanguagesForTypeHierarchy()}"
                )
            }

            ProgressManager.checkCanceled() // Allow cancellation before heavy operation

            val hierarchyData = handler.getTypeHierarchy(element, project, scope, excludeGenerated)
            if (hierarchyData == null) {
                return@suspendingReadAction createErrorResult("No class/type found at the specified position.")
            }

            // Convert handler result to tool result
            createJsonResult(TypeHierarchyResult(
                element = convertToTypeElement(hierarchyData.element),
                supertypes = hierarchyData.supertypes.map { convertToTypeElement(it) },
                subtypes = hierarchyData.subtypes.map { convertToTypeElement(it) }
            ))
        }
    }



    private fun resolveTargetElement(project: Project, arguments: JsonObject, scope: BuiltInSearchScope): PsiElement? {
        // Try className first. Direct class lookup covers JVM/PHP-style FQNs; symbol search
        // fills the same entry point for WebStorm JS/TS class and interface names.
        val className = arguments["className"]?.jsonPrimitive?.content
        if (className != null) {
            return findClassByName(project, className)
                ?: findJavaScriptOrTypeScriptClassByName(project, className, scope)
        }

        // Otherwise use file/line/column (works for all languages)
        val file = arguments["file"]?.jsonPrimitive?.content ?: return null
        val line = arguments["line"]?.jsonPrimitive?.int ?: return null
        val column = arguments["column"]?.jsonPrimitive?.int ?: return null

        return findPsiElement(project, file, line, column)
    }

    private fun findJavaScriptOrTypeScriptClassByName(
        project: Project,
        className: String,
        scope: BuiltInSearchScope
    ): PsiElement? {
        val simpleName = className.substringAfterLast('.').substringAfterLast('#')
        if (simpleName.isBlank()) return null
        val isQualifiedRequest = className.contains('.') || className.contains('#')

        val searchScope = BuiltInSearchScopeResolver.resolveGlobalScope(project, scope)
        val symbols = OptimizedSymbolSearch.search(
            project = project,
            pattern = simpleName,
            scope = searchScope,
            limit = 50,
            languageFilter = JS_TS_LANGUAGE_FILTER
        )

        val match = symbols
            .filter { it.name == simpleName && it.kind in TYPE_SYMBOL_KINDS }
            .firstOrNull { symbol ->
                val qualifiedName = symbol.qualifiedName
                (!isQualifiedRequest && symbol.qualifiedName == null) ||
                    qualifiedName == className ||
                    qualifiedName?.endsWith(".$simpleName") == true
            }
            ?: symbols.firstOrNull {
                !isQualifiedRequest && it.name == simpleName && it.kind in TYPE_SYMBOL_KINDS
            }

        return match?.let { findPsiElement(project, it.file, it.line, it.column) }
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
