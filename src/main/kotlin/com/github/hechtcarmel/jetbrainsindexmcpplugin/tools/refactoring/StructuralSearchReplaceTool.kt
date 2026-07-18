package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ProjectUtils
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.BuiltInSearchScope
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.BuiltInSearchScopeResolver
import com.intellij.psi.search.GlobalSearchScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class StructuralSearchReplaceTool : AbstractMcpTool() {

    companion object {
        private val LOG = logger<StructuralSearchReplaceTool>()
    }

    override val name = ToolNames.STRUCTURAL_SEARCH_REPLACE

    override val description = """
        Search for code patterns using IntelliJ's structural search, optionally replacing matches.

        Uses AST-aware pattern matching — not text regex. Patterns use ${'$'}variable${'$'} syntax
        for wildcards that match any expression, statement, or type.

        Search-only mode (no replacePattern): returns all matches with file locations.
        Replace mode: applies the replacement pattern to all matches and returns the count.

        Examples:
        - Search: {"searchPattern": "System.out.println(${'$'}arg${'$'})"}
        - Replace: {"searchPattern": "new Sync(${'$'}fn${'$'})", "replacePattern": "new Sync<>(Map.class, ${'$'}fn${'$'})", "filePattern": "*.java"}
    """.trimIndent()

    override val inputSchema: JsonObject = SchemaBuilder.tool()
        .projectPath()
        .stringProperty(ParamNames.SEARCH_PATTERN, "SSR search pattern. Use \$variable\$ for wildcards.", required = true)
        .stringProperty(ParamNames.REPLACE_PATTERN, "SSR replacement pattern. If omitted, returns matches without replacing.")
        .stringProperty(ParamNames.FILE_PATTERN, "File mask filter (e.g., '*.java', '*.kt'). Default: '*.java'.")
        .scopeProperty("Search scope. Default: project_files.")
        .build()

    @Serializable
    data class SsrMatch(
        val file: String,
        val line: Int,
        val matchedText: String
    )

    @Serializable
    data class SsrResult(
        val success: Boolean,
        val matchCount: Int,
        val replacedCount: Int,
        val matches: List<SsrMatch>? = null,
        val message: String
    )

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val searchPattern = arguments[ParamNames.SEARCH_PATTERN]?.jsonPrimitive?.content
            ?: return createErrorResult("Missing required parameter: searchPattern")
        if (searchPattern.isBlank()) {
            return createErrorResult("searchPattern must not be empty.")
        }
        val replacePattern = arguments[ParamNames.REPLACE_PATTERN]?.jsonPrimitive?.content
        val filePattern = arguments[ParamNames.FILE_PATTERN]?.jsonPrimitive?.content
        val scopeStr = arguments[ParamNames.SCOPE]?.jsonPrimitive?.content

        val matchOptionsClass = try {
            Class.forName("com.intellij.structuralsearch.MatchOptions")
        } catch (_: ClassNotFoundException) {
            return createErrorResult("Structural search not available — requires Java plugin.")
        }

        val matcherClass = try {
            Class.forName("com.intellij.structuralsearch.Matcher")
        } catch (_: ClassNotFoundException) {
            return createErrorResult("Structural search Matcher not available.")
        }

        val builtInScope = try {
            BuiltInSearchScopeResolver.parse(arguments, BuiltInSearchScope.PROJECT_FILES)
        } catch (_: IllegalArgumentException) {
            return createErrorResult("Unsupported scope '$scopeStr'. Supported values: ${BuiltInSearchScope.supportedWireValues().joinToString(", ")}")
        }
        val searchScope = BuiltInSearchScopeResolver.resolveGlobalScope(project, builtInScope)

        val matches = suspendingReadAction {
            executeSearch(project, matchOptionsClass, matcherClass, searchPattern, filePattern, searchScope)
        }

        return when {
            matches.isFailure -> createErrorResult(matches.exceptionOrNull()?.message ?: "Search failed")
            replacePattern == null -> {
                val matchList = matches.getOrThrow()
                createJsonResult(SsrResult(
                    success = true,
                    matchCount = matchList.size,
                    replacedCount = 0,
                    matches = matchList.take(100),
                    message = "Found ${matchList.size} match(es)"
                ))
            }
            else -> {
                val matchList = matches.getOrThrow()
                if (matchList.isEmpty()) {
                    return createJsonResult(SsrResult(
                        success = true,
                        matchCount = 0,
                        replacedCount = 0,
                        message = "No matches found — nothing to replace."
                    ))
                }

                val replaceResult = executeReplace(
                    project, matchOptionsClass, searchPattern, replacePattern, filePattern, searchScope
                )

                when {
                    replaceResult.isFailure -> createErrorResult(replaceResult.exceptionOrNull()?.message ?: "Replace failed")
                    else -> {
                        val count = replaceResult.getOrThrow()
                        createJsonResult(SsrResult(
                            success = true,
                            matchCount = matchList.size,
                            replacedCount = count,
                            message = "Replaced $count of ${matchList.size} match(es)"
                        ))
                    }
                }
            }
        }
    }

    private fun executeSearch(
        project: Project,
        matchOptionsClass: Class<*>,
        matcherClass: Class<*>,
        searchPattern: String,
        filePattern: String?,
        searchScope: GlobalSearchScope
    ): Result<List<SsrMatch>> {
        return try {
            val options = matchOptionsClass.getDeclaredConstructor().newInstance()

            val setSearchPattern = matchOptionsClass.getMethod("setSearchPattern", String::class.java)
            setSearchPattern.invoke(options, searchPattern)

            val setScope = matchOptionsClass.getMethod("setScope", com.intellij.psi.search.SearchScope::class.java)
            setScope.invoke(options, searchScope)

            val resolvedFileType = resolveFileType(filePattern)
            if (resolvedFileType != null) {
                try {
                    val setFileType = matchOptionsClass.getMethod("setFileType", com.intellij.openapi.fileTypes.LanguageFileType::class.java)
                    setFileType.invoke(options, resolvedFileType)
                } catch (e: Exception) {
                    LOG.debug("Could not set file type filter: ${e.message}")
                }
            }

            val matcher = try {
                matcherClass.getConstructor(Project::class.java, matchOptionsClass)
                    .newInstance(project, options)
            } catch (e: Exception) {
                val cause = if (e is java.lang.reflect.InvocationTargetException) e.cause ?: e else e
                return Result.failure(Exception("Failed to compile search pattern: ${cause.message}"))
            }

            val matchResultClass = Class.forName("com.intellij.structuralsearch.MatchResult")
            val matchResultSinkClass = Class.forName("com.intellij.structuralsearch.MatchResultSink")
            val collectedResults = mutableListOf<Any>()
            val maxResults = 5000

            val sink = java.lang.reflect.Proxy.newProxyInstance(
                matchResultSinkClass.classLoader,
                arrayOf(matchResultSinkClass)
            ) { proxyObj, method, args ->
                when (method.name) {
                    "equals" -> proxyObj === args?.get(0)
                    "hashCode" -> System.identityHashCode(proxyObj)
                    "toString" -> "MatchResultSink-proxy"
                    "newMatch" -> {
                        if (args != null && args.isNotEmpty() && collectedResults.size < maxResults) {
                            collectedResults.add(args[0])
                        }
                        null
                    }
                    "getProgressIndicator" -> com.intellij.openapi.progress.EmptyProgressIndicator()
                    else -> null
                }
            }

            val findMatches = matcherClass.getMethod("findMatches", matchResultSinkClass)
            findMatches.invoke(matcher, sink)
            val results = collectedResults

            val matches = results.mapNotNull { result ->
                try {
                    val getMatch = matchResultClass.getMethod("getMatch")
                    val match = getMatch.invoke(result) as? com.intellij.psi.PsiElement ?: return@mapNotNull null
                    val file = match.containingFile?.virtualFile ?: return@mapNotNull null
                    val document = FileDocumentManager.getInstance().getDocument(file) ?: return@mapNotNull null
                    val line = document.getLineNumber(match.textOffset) + 1
                    val text = match.text?.take(200) ?: ""
                    SsrMatch(
                        file = ProjectUtils.getToolFilePath(project, file),
                        line = line,
                        matchedText = text
                    )
                } catch (_: Exception) {
                    null
                }
            }

            Result.success(matches)
        } catch (e: com.intellij.openapi.progress.ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            val cause = if (e is java.lang.reflect.InvocationTargetException) e.cause ?: e else e
            Result.failure(Exception("Structural search failed: ${cause.message}"))
        }
    }

    private suspend fun executeReplace(
        project: Project,
        matchOptionsClass: Class<*>,
        searchPattern: String,
        replacePattern: String,
        filePattern: String?,
        searchScope: GlobalSearchScope
    ): Result<Int> {
        return try {
            val replaceOptionsClass = Class.forName("com.intellij.structuralsearch.plugin.replace.ReplaceOptions")
            val replaceOptions = replaceOptionsClass.getDeclaredConstructor().newInstance()

            val getMatchOptions = replaceOptionsClass.getMethod("getMatchOptions")
            val options = getMatchOptions.invoke(replaceOptions)

            val setSearchPattern = matchOptionsClass.getMethod("setSearchPattern", String::class.java)
            setSearchPattern.invoke(options, searchPattern)

            val setScope = matchOptionsClass.getMethod("setScope", com.intellij.psi.search.SearchScope::class.java)
            setScope.invoke(options, searchScope)

            val resolvedFileType = resolveFileType(filePattern)
            if (resolvedFileType != null) {
                try {
                    val setFileType = matchOptionsClass.getMethod("setFileType", com.intellij.openapi.fileTypes.LanguageFileType::class.java)
                    setFileType.invoke(options, resolvedFileType)
                } catch (e: Exception) {
                    LOG.debug("Could not set file type filter: ${e.message}")
                }
            }

            val setReplacement = replaceOptionsClass.getMethod("setReplacement", String::class.java)
            setReplacement.invoke(replaceOptions, replacePattern)

            val replacerClass = Class.forName("com.intellij.structuralsearch.plugin.replace.impl.Replacer")
            val replacer = replacerClass.getConstructor(Project::class.java, replaceOptionsClass)
                .newInstance(project, replaceOptions)

            val matcherClass = Class.forName("com.intellij.structuralsearch.Matcher")
            val matcher = matcherClass.getConstructor(Project::class.java, matchOptionsClass)
                .newInstance(project, options)

            val matchResultSinkClass = Class.forName("com.intellij.structuralsearch.MatchResultSink")
            val matchResults = mutableListOf<Any>()
            val sink = java.lang.reflect.Proxy.newProxyInstance(
                matchResultSinkClass.classLoader,
                arrayOf(matchResultSinkClass)
            ) { proxyObj, method, args ->
                when (method.name) {
                    "equals" -> proxyObj === args?.get(0)
                    "hashCode" -> System.identityHashCode(proxyObj)
                    "toString" -> "MatchResultSink-replace-proxy"
                    "newMatch" -> {
                        if (args != null && args.isNotEmpty()) {
                            matchResults.add(args[0])
                        }
                        null
                    }
                    "getProgressIndicator" -> com.intellij.openapi.progress.EmptyProgressIndicator()
                    else -> null
                }
            }

            suspendingReadAction {
                val findMatches = matcherClass.getMethod("findMatches", matchResultSinkClass)
                findMatches.invoke(matcher, sink)
            }

            if (matchResults.isEmpty()) return Result.success(0)

            val buildReplacement = replacerClass.getMethod("buildReplacement",
                Class.forName("com.intellij.structuralsearch.MatchResult"))

            val replacements = suspendingReadAction {
                matchResults.mapNotNull { matchResult ->
                    try { buildReplacement.invoke(replacer, matchResult) } catch (e: Exception) {
                        LOG.debug("Failed to build replacement: ${e.message}")
                        null
                    }
                }
            }

            var count = 0
            edtAction {
                val replaceAll = replacerClass.getMethod("replaceAll", List::class.java)
                replaceAll.invoke(replacer, replacements)
                count = replacements.size
                PsiDocumentManager.getInstance(project).commitAllDocuments()
                FileDocumentManager.getInstance().saveAllDocuments()
            }

            Result.success(count)
        } catch (e: com.intellij.openapi.progress.ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            val cause = if (e is java.lang.reflect.InvocationTargetException) e.cause ?: e else e
            Result.failure(Exception("Structural replace failed: ${cause.message}"))
        }
    }

    private fun resolveFileType(filePattern: String?): com.intellij.openapi.fileTypes.LanguageFileType? {
        val ext = if (filePattern != null) {
            filePattern.removePrefix("*.").substringAfterLast(".")
        } else {
            "java"
        }
        val fileType = com.intellij.openapi.fileTypes.FileTypeManager.getInstance().getFileTypeByExtension(ext)
        return fileType as? com.intellij.openapi.fileTypes.LanguageFileType
    }
}
