package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.SchemaConstants
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.FileMatch
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.FindFileResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.StringUtils
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
import kotlinx.serialization.json.boolean
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

        Parameters: query (required), includeLibraries (optional, default: false), limit (optional, default: 25, max: 100).

        Example: {"query": "UserService.java"} or {"query": "build.gradle", "includeLibraries": true}
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
            putJsonObject(ParamNames.INCLUDE_LIBRARIES) {
                put(SchemaConstants.TYPE, SchemaConstants.TYPE_BOOLEAN)
                put(SchemaConstants.DESCRIPTION, "Include files from library dependencies. Default: false.")
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
        val includeLibraries = arguments[ParamNames.INCLUDE_LIBRARIES]?.jsonPrimitive?.boolean ?: false
        val limit = (arguments[ParamNames.LIMIT]?.jsonPrimitive?.int ?: DEFAULT_LIMIT)
            .coerceIn(1, MAX_LIMIT)

        if (query.isBlank()) {
            return createErrorResult("Query cannot be empty")
        }

        requireSmartMode(project)

        return suspendingReadAction {
            val scope = if (includeLibraries) {
                GlobalSearchScope.allScope(project)
            } else {
                GlobalSearchScope.projectScope(project)
            }
            val files = searchFiles(project, query, scope, limit)

            // Sort by relevance
            val sortedFiles = files
                .distinctBy { it.path }
                .sortedWith(compareBy(
                    { !it.name.equals(query, ignoreCase = true) },
                    { !it.name.contains(query, ignoreCase = true) },
                    { StringUtils.levenshteinDistance(it.name.lowercase(), query.lowercase()) }
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
}
