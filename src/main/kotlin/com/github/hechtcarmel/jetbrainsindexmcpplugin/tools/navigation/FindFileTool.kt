package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.SchemaConstants
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.FileMatch
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.FindFileResult
import com.intellij.navigation.ChooseByNameContributor
import com.intellij.navigation.ChooseByNameContributorEx
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.codeStyle.MinusculeMatcher
import com.intellij.psi.codeStyle.NameUtil
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.indexing.FindSymbolParameters
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Tool for searching files by name.
 *
 * Uses FILE_EP_NAME index for file lookups.
 *
 * Equivalent to IntelliJ's "Go to File" (Ctrl+Shift+N / Cmd+Shift+O).
 */
@Suppress("unused")
class FindFileTool : AbstractMcpTool() {

    companion object {
        private const val DEFAULT_LIMIT = 25
        private const val MAX_LIMIT = 100
    }

    override val name = ToolNames.FIND_FILE

    override val description = """
        Search for files by name. Very fast file lookup using IDE's file index.

        Matching: substring ("User" → "UserService.java") and fuzzy matching.

        Returns: matching files with name, path, and containing directory.

        Parameters: query (required), limit (optional, default: 25, max: 100).

        Example: {"query": "UserService.java"} or {"query": "build.gradle"}
    """.trimIndent()

    override val inputSchema: JsonObject = buildJsonObject {
        put(SchemaConstants.TYPE, SchemaConstants.TYPE_OBJECT)
        putJsonObject(SchemaConstants.PROPERTIES) {
            putJsonObject(ParamNames.PROJECT_PATH) {
                put(SchemaConstants.TYPE, SchemaConstants.TYPE_STRING)
                put(SchemaConstants.DESCRIPTION, SchemaConstants.DESC_PROJECT_PATH)
            }
            putJsonObject(ParamNames.QUERY) {
                put(SchemaConstants.TYPE, SchemaConstants.TYPE_STRING)
                put(SchemaConstants.DESCRIPTION, "File name pattern. Supports substring and fuzzy matching.")
            }
            putJsonObject(ParamNames.LIMIT) {
                put(SchemaConstants.TYPE, SchemaConstants.TYPE_INTEGER)
                put(SchemaConstants.DESCRIPTION, "Maximum results to return. Default: 25, Max: 100.")
            }
        }
        putJsonArray(SchemaConstants.REQUIRED) {
            add(JsonPrimitive(ParamNames.QUERY))
        }
    }

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val query = arguments[ParamNames.QUERY]?.jsonPrimitive?.content
            ?: return createErrorResult("Missing required parameter: ${ParamNames.QUERY}")
        val limit = (arguments[ParamNames.LIMIT]?.jsonPrimitive?.int ?: DEFAULT_LIMIT)
            .coerceIn(1, MAX_LIMIT)

        if (query.isBlank()) {
            return createErrorResult("Query cannot be empty")
        }

        requireSmartMode(project)

        return suspendingReadAction {
            val scope = GlobalSearchScope.projectScope(project)
            val files = searchFiles(project, query, scope, limit)

            // Sort by relevance
            val sortedFiles = files
                .distinctBy { it.path }
                .sortedWith(compareBy(
                    { !it.name.equals(query, ignoreCase = true) },
                    { !it.name.contains(query, ignoreCase = true) },
                    { levenshteinDistance(it.name.lowercase(), query.lowercase()) }
                ))
                .take(limit)

            createJsonResult(FindFileResult(
                files = sortedFiles,
                totalCount = sortedFiles.size,
                query = query
            ))
        }
    }

    /**
     * Search for files using FILE_EP_NAME index (optimized for file lookups).
     */
    private fun searchFiles(
        project: Project,
        pattern: String,
        scope: GlobalSearchScope,
        limit: Int
    ): List<FileMatch> {
        val results = mutableListOf<FileMatch>()
        val seen = mutableSetOf<String>()
        val matcher = createMatcher(pattern)

        // Use FILE_EP_NAME for file search
        val contributors = ChooseByNameContributor.FILE_EP_NAME.extensionList

        for (contributor in contributors) {
            if (results.size >= limit) break

            try {
                processContributor(contributor, project, pattern, scope, limit, matcher, results, seen)
            } catch (_: Exception) {
                // Continue to next contributor
            }
        }

        return results
    }

    private fun processContributor(
        contributor: ChooseByNameContributor,
        project: Project,
        pattern: String,
        scope: GlobalSearchScope,
        limit: Int,
        matcher: MinusculeMatcher,
        results: MutableList<FileMatch>,
        seen: MutableSet<String>
    ) {
        if (contributor is ChooseByNameContributorEx) {
            // Modern API with Processor pattern
            val matchingNames = mutableListOf<String>()

            contributor.processNames(
                { name ->
                    if (matcher.matches(name)) {
                        matchingNames.add(name)
                    }
                    matchingNames.size < limit * 3
                },
                scope,
                null
            )

            for (name in matchingNames) {
                if (results.size >= limit) break

                val params = FindSymbolParameters.wrap(pattern, scope)
                contributor.processElementsWithName(
                    name,
                    { item ->
                        if (results.size >= limit) return@processElementsWithName false

                        val fileMatch = convertToFileMatch(item, project)
                        if (fileMatch != null) {
                            val key = fileMatch.path
                            if (key !in seen) {
                                seen.add(key)
                                results.add(fileMatch)
                            }
                        }
                        true
                    },
                    params
                )
            }
        } else {
            // Legacy API
            val names = contributor.getNames(project, false)
            val matchingNames = names.filter { matcher.matches(it) }

            for (name in matchingNames) {
                if (results.size >= limit) break

                val items = contributor.getItemsByName(name, pattern, project, false)
                for (item in items) {
                    if (results.size >= limit) break

                    val fileMatch = convertToFileMatch(item, project)
                    if (fileMatch != null) {
                        val key = fileMatch.path
                        if (key !in seen) {
                            seen.add(key)
                            results.add(fileMatch)
                        }
                    }
                }
            }
        }
    }

    private fun convertToFileMatch(item: NavigationItem, project: Project): FileMatch? {
        val virtualFile: VirtualFile = when (item) {
            is PsiFile -> item.virtualFile
            else -> {
                try {
                    val method = item.javaClass.getMethod("getElement")
                    val element = method.invoke(item)
                    if (element is PsiFile) {
                        element.virtualFile
                    } else {
                        null
                    }
                } catch (_: Exception) {
                    null
                }
            }
        } ?: return null

        val basePath = project.basePath ?: ""
        val relativePath = virtualFile.path.removePrefix(basePath).removePrefix("/")
        val directory = virtualFile.parent?.path?.removePrefix(basePath)?.removePrefix("/") ?: ""

        return FileMatch(
            name = virtualFile.name,
            path = relativePath,
            directory = directory
        )
    }

    private fun createMatcher(pattern: String): MinusculeMatcher {
        return NameUtil.buildMatcher("*$pattern", NameUtil.MatchingCaseSensitivity.NONE)
    }

    private fun levenshteinDistance(s1: String, s2: String): Int {
        val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }
        for (i in 0..s1.length) dp[i][0] = i
        for (j in 0..s2.length) dp[0][j] = j
        for (i in 1..s1.length) {
            for (j in 1..s2.length) {
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,
                    dp[i][j - 1] + 1,
                    dp[i - 1][j - 1] + if (s1[i - 1] == s2[j - 1]) 0 else 1
                )
            }
        }
        return dp[s1.length][s2.length]
    }
}
