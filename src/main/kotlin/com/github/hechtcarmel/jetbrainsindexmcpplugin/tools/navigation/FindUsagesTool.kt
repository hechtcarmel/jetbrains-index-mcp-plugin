package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ErrorMessages
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.UsageTypes
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.BuiltInSearchScope
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.BuiltInSearchScopeResolver
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.PaginationService
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.ProjectResolver
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.FindUsagesResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.UsageLocation
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PsiUtils
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.util.Processor
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class FindUsagesTool : AbstractMcpTool() {

    companion object {
        private val LOG = logger<FindUsagesTool>()
        private const val DEFAULT_MAX_RESULTS = 100
        private const val MAX_PAGE_SIZE = PaginationService.MAX_PAGE_SIZE

        internal fun searchInfrastructureErrorMessage(error: Throwable): String {
            val detail = error.message?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""
            return "Reference search failed due to IDE/plugin API incompatibility (${error::class.simpleName}$detail). " +
                "Try ide_search_text as a fallback and check plugin compatibility against the current IDE build."
        }
    }

    override val name = ToolNames.FIND_REFERENCES

    override val description = """
        Find all references to a symbol across the project. Use when you need to understand how a class, method, field, or variable is used before modifying or removing it.

        Returns: file paths, line numbers, context snippets, and reference types (method_call, field_access, import, etc.).

        Supports pagination: first call returns results + nextCursor. Pass cursor to get the next page.

        Target (mutually exclusive):
        - file + line + column: position-based lookup (necessary for fresh search, ignored when cursor is provided)
        - language + symbol: fully qualified symbol reference (currently supported for Java only; necessary for fresh search, ignored when cursor is provided)
        - cursor: pagination cursor from a previous response

        Parameters: scope (optional, default: "project_files"; supported: project_files, project_and_libraries, project_production_files, project_test_files), pageSize (optional, default: 100, max: 500).

        Example: {"file": "src/UserService.java", "line": 25, "column": 18}
        Example: {"language": "Java", "symbol": "com.example.UserService#findUser(String)", "scope": "project_and_libraries"}
    """.trimIndent()

    override val inputSchema: JsonObject = SchemaBuilder.tool()
        .projectPath()
        .file(required = false, description = "Project-relative file path, or a dependency/library absolute path or jar:// URL previously returned by the plugin. Required for position-based lookup.")
        .lineAndColumn(required = false)
        .languageAndSymbol(required = false)
        .scopeProperty("Search scope. Default: project_files.")
        .intProperty("maxResults", "Maximum results per page (deprecated, use pageSize). Default: $DEFAULT_MAX_RESULTS, max: $MAX_PAGE_SIZE.")
        .stringProperty("cursor", "Pagination cursor from a previous response. When provided, returns the next page of results. Search parameters are ignored; project_path and pageSize may still be provided.")
        .intProperty("pageSize", "Results per page. Default: $DEFAULT_MAX_RESULTS, max: $MAX_PAGE_SIZE.")
        .build()

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val cursor = arguments["cursor"]?.jsonPrimitive?.content
        if (cursor != null) {
            val pageSize = resolveExplicitPageSize(arguments, aliases = arrayOf("maxResults"))
            return buildPaginatedResult<UsageLocation, FindUsagesResult>(getPageFromCache(cursor, pageSize, project)) { items, page ->
                FindUsagesResult(
                    usages = items,
                    totalCount = page.totalCollected,
                    truncated = page.hasMore,
                    nextCursor = page.nextCursor,
                    hasMore = page.hasMore,
                    totalCollected = page.totalCollected,
                    offset = page.offset,
                    pageSize = page.pageSize,
                    stale = page.stale
                )
            }
        }

        val pageSize = resolvePageSize(arguments, DEFAULT_MAX_RESULTS, aliases = arrayOf("maxResults"))
        val collectLimit = maxOf(PaginationService.DEFAULT_OVERCOLLECT, pageSize)
        val rawScope = rawScopeValue(arguments[ParamNames.SCOPE])
        val scope = try {
            BuiltInSearchScopeResolver.parse(arguments, BuiltInSearchScope.PROJECT_FILES)
        } catch (_: IllegalArgumentException) {
            return createInvalidScopeError(rawScope)
        } catch (_: IllegalStateException) {
            return createInvalidScopeError(rawScope)
        }
        requireSmartMode(project)

        val cursorToken = suspendingReadAction {
            val element = resolveElementFromArguments(project, arguments, allowLibraryFilesForPosition = true).getOrElse {
                return@suspendingReadAction null to createErrorResult(it.message ?: ErrorMessages.COULD_NOT_RESOLVE_SYMBOL)
            }

            // Symbol-based resolution returns the declaration directly (PsiNamedElement).
            // Position-based resolution returns a leaf token that needs reference resolution.
            val targetElement = element as? PsiNamedElement
                ?: (PsiUtils.resolveTargetElement(element)
                    ?: return@suspendingReadAction null to createErrorResult(ErrorMessages.NO_NAMED_ELEMENT))

            val usages = ConcurrentLinkedQueue<UsageLocation>()
            val totalFound = AtomicInteger(0)
            val totalCountLimit = collectLimit * 10
            val searchScope = BuiltInSearchScopeResolver.resolveGlobalScope(project, scope)

            try {
                ReferencesSearch.search(targetElement, searchScope).forEach(Processor { reference ->
                    ProgressManager.checkCanceled()

                    val refElement = reference.element
                    val refFile = refElement.containingFile?.virtualFile
                    if (refFile != null && searchScope.contains(refFile)) {
                        val total = totalFound.incrementAndGet()

                        if (total <= collectLimit) {
                            val document = PsiDocumentManager.getInstance(project)
                                .getDocument(refElement.containingFile)
                            if (document != null) {
                                val lineNumber = document.getLineNumber(refElement.textOffset) + 1
                                val columnNumber = refElement.textOffset -
                                    document.getLineStartOffset(lineNumber - 1) + 1

                                val lineText = document.getText(
                                    TextRange(
                                        document.getLineStartOffset(lineNumber - 1),
                                        document.getLineEndOffset(lineNumber - 1)
                                    )
                                ).trim()

                                usages.add(UsageLocation(
                                    file = getRelativePath(project, refFile),
                                    line = lineNumber,
                                    column = columnNumber,
                                    context = lineText,
                                    type = classifyUsage(refElement),
                                    astPath = PsiUtils.getAstPath(refElement)
                                ))
                            }
                        }

                        total < totalCountLimit
                    } else {
                        true
                    }
                })
            } catch (e: LinkageError) {
                LOG.warn("Reference search failed for ${targetElement.javaClass.name}", e)
                return@suspendingReadAction null to createErrorResult(searchInfrastructureErrorMessage(e))
            }

            val usagesList = usages.toList()
                .distinctBy { "${it.file}:${it.line}:${it.column}" }

            val smartPointer = SmartPointerManager.getInstance(project).createSmartPsiElementPointer(targetElement)

            val searchExtender: suspend (Set<String>, Int) -> List<PaginationService.SerializedResult> = { seenKeys, limit ->
                suspendingReadAction {
                    val el = smartPointer.element
                        ?: throw IllegalStateException("Target element no longer valid")
                    extendFindUsages(project, el, seenKeys, limit, scope)
                }
            }

            val serializedResults = usagesList.map { usage ->
                PaginationService.SerializedResult(
                    key = "${usage.file}:${usage.line}:${usage.column}",
                    data = json.encodeToJsonElement(usage)
                )
            }

            val paginationService = ApplicationManager.getApplication().getService(PaginationService::class.java)
            val token = paginationService.createCursor(
                toolName = name,
                results = serializedResults,
                seenKeys = serializedResults.map { it.key }.toSet(),
                searchExtender = searchExtender,
                psiModCount = PsiModificationTracker.getInstance(project).modificationCount,
                projectBasePath = ProjectResolver.normalizePath(project.basePath ?: "")
            )

            token to null
        }

        val (token, errorResult) = cursorToken
        if (errorResult != null) return errorResult

        return buildPaginatedResult<UsageLocation, FindUsagesResult>(getPageFromCache(token!!, pageSize, project)) { items, page ->
            FindUsagesResult(
                usages = items,
                totalCount = page.totalCollected,
                truncated = page.hasMore,
                nextCursor = page.nextCursor,
                hasMore = page.hasMore,
                totalCollected = page.totalCollected,
                offset = page.offset,
                pageSize = page.pageSize,
                stale = page.stale
            )
        }
    }

    /**
     * Re-executes the search to collect more results beyond the initial cache.
     * This re-scans from the beginning, skipping already-seen keys — O(total_results) per extension.
     * This is unavoidable: IntelliJ's search APIs (ReferencesSearch, PsiSearchHelper, etc.)
     * don't support offset-based iteration or resumption.
     */
    private fun extendFindUsages(
        project: Project,
        targetElement: PsiElement,
        seenKeys: Set<String>,
        limit: Int,
        scope: BuiltInSearchScope
    ): List<PaginationService.SerializedResult> {
        val newResults = ConcurrentLinkedQueue<PaginationService.SerializedResult>()
        val count = AtomicInteger(0)
        val searchScope = BuiltInSearchScopeResolver.resolveGlobalScope(project, scope)

        try {
            ReferencesSearch.search(targetElement, searchScope).forEach(Processor { reference ->
                ProgressManager.checkCanceled()
                val refElement = reference.element
                val refFile = refElement.containingFile?.virtualFile
                if (refFile != null && searchScope.contains(refFile)) {
                    val document = PsiDocumentManager.getInstance(project).getDocument(refElement.containingFile)
                    if (document != null) {
                        val lineNumber = document.getLineNumber(refElement.textOffset) + 1
                        val columnNumber = refElement.textOffset - document.getLineStartOffset(lineNumber - 1) + 1
                        val key = "${getRelativePath(project, refFile)}:$lineNumber:$columnNumber"

                        if (key !in seenKeys) {
                            val slot = count.incrementAndGet()
                            if (slot <= limit) {
                                val lineText = document.getText(
                                    TextRange(document.getLineStartOffset(lineNumber - 1), document.getLineEndOffset(lineNumber - 1))
                                ).trim()
                                val usage = UsageLocation(
                                    file = getRelativePath(project, refFile),
                                    line = lineNumber,
                                    column = columnNumber,
                                    context = lineText,
                                    type = classifyUsage(refElement),
                                    astPath = PsiUtils.getAstPath(refElement)
                                )
                                newResults.add(PaginationService.SerializedResult(key, json.encodeToJsonElement(usage)))
                            }
                            slot < limit
                        } else {
                            true
                        }
                    } else true
                } else true
            })
        } catch (e: LinkageError) {
            LOG.warn("Reference search pagination failed for ${targetElement.javaClass.name}", e)
            throw IllegalStateException(searchInfrastructureErrorMessage(e), e)
        }

        return newResults.toList()
    }

    private fun classifyUsage(element: PsiElement): String {
        val parent = element.parent
        val parentClass = parent?.javaClass?.simpleName ?: "Unknown"

        return when {
            parentClass.contains("MethodCall") -> UsageTypes.METHOD_CALL
            parentClass.contains("Reference") -> UsageTypes.REFERENCE
            parentClass.contains("Field") -> UsageTypes.FIELD_ACCESS
            parentClass.contains("Import") -> UsageTypes.IMPORT
            parentClass.contains("Parameter") -> UsageTypes.PARAMETER
            parentClass.contains("Variable") -> UsageTypes.VARIABLE
            else -> UsageTypes.REFERENCE
            }
    }

    private fun rawScopeValue(scopeElement: JsonElement?): String = when (scopeElement) {
        null -> ""
        is JsonPrimitive -> scopeElement.content
        else -> scopeElement.toString()
    }

    private fun createInvalidScopeError(provided: String): ToolCallResult =
        createStructuredErrorResult(buildJsonObject {
            put("error", JsonPrimitive("invalid_scope"))
            put("parameter", JsonPrimitive(ParamNames.SCOPE))
            put("provided", JsonPrimitive(provided))
            put("supportedValues", buildJsonArray {
                BuiltInSearchScope.supportedWireValues().forEach { add(JsonPrimitive(it)) }
            })
        })
}
