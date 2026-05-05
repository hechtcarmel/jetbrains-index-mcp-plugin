package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.BuiltInSearchScope
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.BuiltInSearchScopeResolver
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.LanguageHandlerRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.TypeElementData
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.dotnet.RiderBackendSemanticService
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.TypeElement
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.TypeHierarchyResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

/**
 * Tool for retrieving type hierarchies across multiple languages.
 *
 * Supports: Java, Kotlin, Python, JavaScript, TypeScript, PHP, Rust, C#, F#
 *
 * Delegates to language-specific handlers via [LanguageHandlerRegistry].
 */
class TypeHierarchyTool : AbstractMcpTool() {

    companion object {
        private val LOG = logger<TypeHierarchyTool>()
        private val DEFAULT_RIDER_CLASSNAME_LANGUAGES = listOf("C#", "F#")

        /**
         * Keep the default C# -> F# fallback for backward compatibility.
         * We intentionally do NOT infer F# from naming heuristics here: F# type names can look identical
         * to C#/.NET qualified names, so guessing would risk skipping valid C# lookups.
         */
        internal fun riderClassNameCandidateLanguages(requestedLanguage: String?): List<String> = when {
            requestedLanguage.equals("C#", ignoreCase = true) -> listOf("C#")
            requestedLanguage.equals("F#", ignoreCase = true) -> listOf("F#")
            requestedLanguage == null -> DEFAULT_RIDER_CLASSNAME_LANGUAGES
            else -> emptyList()
        }

        internal fun riderQualifiedNameMatchesClassName(qualifiedName: String?, className: String): Boolean {
            if (qualifiedName == null) return false
            return qualifiedName.equals(className, ignoreCase = true) ||
                qualifiedName.replace('+', '.').equals(className, ignoreCase = true)
        }
    }

    override val name = "ide_type_hierarchy"

    override val description = """
        Get the complete inheritance hierarchy for a class or interface. Use when you need to understand class relationships, find parent classes, or discover all subclasses.

        Languages: Java, Kotlin, Python, JavaScript, TypeScript, PHP, Rust, C#, F#.

        Rust note: className parameter not supported for Rust; use file + line + column instead.
        Rider note: C#/F# hierarchy uses Rider's frontend navigation bridge to the ReSharper backend; subtype/supertype detail depends on the current Rider build.

        Returns: target class info, full supertype chain (recursive), and all subtypes in the project.

        Parameters: Either className (e.g., "com.example.MyClass") OR file + line + column. scope (optional, default: "project_files"; supported: project_files, project_and_libraries, project_production_files, project_test_files).

        Example: {"className": "com.example.UserService", "scope": "project_and_libraries"} or {"file": "src/MyClass.java", "line": 10, "column": 14}
    """.trimIndent()

    override val inputSchema: JsonObject = SchemaBuilder.tool()
        .projectPath()
        .stringProperty("className", "Fully qualified class name (e.g., 'com.example.MyClass' for Java, 'App\\\\Models\\\\User' for PHP, or 'My.Namespace.CustomerService' for C#). RECOMMENDED - use this if you know the class name.")
        .file(required = false, description = "Path to file relative to project root (e.g., 'src/main/java/com/example/MyClass.java'). Use with line and column.")
        .intProperty("line", "1-based line number where the class is defined. Required if using file parameter.")
        .intProperty("column", "1-based column number. Required if using file parameter.")
        .stringProperty(ParamNames.LANGUAGE, "Optional Rider className hint. Use 'C#' or 'F#' to force a single Rider lookup; when omitted, Rider className lookup preserves the legacy C# then F# fallback order.")
        .scopeProperty("Search scope. Default: project_files.")
        .build()

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        requireSmartMode(project)

        val className = arguments["className"]?.jsonPrimitive?.content
        val file = arguments["file"]?.jsonPrimitive?.content
        val requestedLanguage = optionalStringArg(arguments, ParamNames.LANGUAGE)
        val rawScope = rawScopeValue(arguments[ParamNames.SCOPE])
        val scope = try {
            BuiltInSearchScopeResolver.parse(arguments, BuiltInSearchScope.PROJECT_FILES)
        } catch (_: IllegalArgumentException) {
            return createInvalidScopeError(rawScope)
        } catch (_: IllegalStateException) {
            return createInvalidScopeError(rawScope)
        }
        val riderClassNameHierarchy = className?.let { tryResolveRiderClassNameHierarchy(project, it, scope, requestedLanguage) }
        if (riderClassNameHierarchy != null) return createJsonResult(riderClassNameHierarchy)

        return suspendingReadAction {
            ProgressManager.checkCanceled() // Allow cancellation

            val element = resolveTargetElement(project, arguments)
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

            val hierarchyData = handler.getTypeHierarchy(element, project, scope)
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



    private fun tryResolveRiderClassNameHierarchy(
        project: Project,
        className: String,
        scope: BuiltInSearchScope,
        requestedLanguage: String?
    ): TypeHierarchyResult? {
        val candidateLanguages = riderClassNameCandidateLanguages(requestedLanguage)
        LOG.debug("tool=ide_type_hierarchy className=$className requestedLanguage=${requestedLanguage ?: "<none>"} riderCandidates=${candidateLanguages.joinToString(",", prefix = "[", postfix = "]")}")

        for (language in candidateLanguages) {
            val findTypesStartedAt = System.nanoTime()
            val typeMatches = RiderBackendSemanticService.findTypes(
                project = project,
                query = className,
                matchMode = "exact",
                scope = scope,
                language = language,
                limit = 5
            )
            val findTypesDurationMs = (System.nanoTime() - findTypesStartedAt) / 1_000_000
            LOG.debug("tool=ide_type_hierarchy className=$className stage=findTypes language=$language durationMs=$findTypesDurationMs handled=${typeMatches.handled} matchCount=${typeMatches.value?.size ?: 0}")

            val match = typeMatches.value
                ?.firstOrNull { riderQualifiedNameMatchesClassName(it.qualifiedName, className) || it.name.equals(className, ignoreCase = true) }
            if (match == null) {
                LOG.debug("tool=ide_type_hierarchy className=$className stage=match language=$language durationMs=0 found=false")
                continue
            }

            val getHierarchyStartedAt = System.nanoTime()
            val hierarchy = RiderBackendSemanticService.getTypeHierarchy(
                project = project,
                file = match.file,
                line = match.line,
                column = match.column,
                scope = scope,
                language = language
            )
            val getHierarchyDurationMs = (System.nanoTime() - getHierarchyStartedAt) / 1_000_000
            LOG.debug("tool=ide_type_hierarchy className=$className stage=getTypeHierarchy language=$language durationMs=$getHierarchyDurationMs handled=${hierarchy.handled} found=${hierarchy.value != null}")

            val hierarchyValue = hierarchy.value ?: continue
            return TypeHierarchyResult(
                element = convertToTypeElement(hierarchyValue.element),
                supertypes = hierarchyValue.supertypes.map { convertToTypeElement(it) },
                subtypes = hierarchyValue.subtypes.map { convertToTypeElement(it) }
            )
        }

        LOG.debug("tool=ide_type_hierarchy className=$className stage=complete requestedLanguage=${requestedLanguage ?: "<none>"} result=not_found")
        return null
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
