package com.github.hechtcarmel.jetbrainsindexmcpplugin.server

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ErrorMessages
import com.github.hechtcarmel.jetbrainsindexmcpplugin.settings.McpSettings
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ContentBlock
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ResponseFormatter
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.roots.ModuleRootManager
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

internal data class AvailableProjectEntry(
    val name: String,
    val path: String,
    val workspace: String? = null
)

/**
 * Builds the `available_projects` JSON array from the given entries.
 * When [includeWorkspaceSubProjects] is false, sub-project entries
 * (those with a non-null `workspace`) are filtered out.
 *
 * Pure function — extracted so it can be unit-tested without the IntelliJ
 * Platform or `McpSettings`.
 */
internal fun buildAvailableProjectsJson(
    entries: List<AvailableProjectEntry>,
    includeWorkspaceSubProjects: Boolean
): JsonArray = buildJsonArray {
    for (entry in entries) {
        if (!includeWorkspaceSubProjects && entry.workspace != null) continue
        add(buildJsonObject {
            put("name", entry.name)
            put("path", entry.path)
            entry.workspace?.let { put("workspace", it) }
        })
    }
}

internal fun buildStructuredErrorResult(
    payload: JsonObject,
    format: McpSettings.ResponseFormat = McpSettings.ResponseFormat.JSON
): ToolCallResult {
    val json = Json { encodeDefaults = true; prettyPrint = false }
    return try {
        val jsonText = json.encodeToString(payload)
        ToolCallResult(
            content = listOf(
                ContentBlock.Text(
                    text = ResponseFormatter.formatStructuredPayload(jsonText, format)
                )
            ),
            isError = true
        )
    } catch (e: Exception) {
        val message = e.message?.takeIf { it.isNotBlank() } ?: "unknown error"
        ToolCallResult(
            content = listOf(ContentBlock.Text(text = "Response formatting failed: $message")),
            isError = true
        )
    }
}

object ProjectResolver {

    private val LOG = logger<ProjectResolver>()
    private val json = Json { encodeDefaults = true; prettyPrint = false }

    fun normalizePath(path: String): String {
        return path.trimEnd('/', '\\').replace('\\', '/')
    }

    data class Result(
        val project: Project? = null,
        val errorResult: ToolCallResult? = null,
        val isError: Boolean = false
    )

    fun resolve(projectPath: String?): Result {
        val openProjects = ProjectManager.getInstance().openProjects
            .filter { !it.isDefault }

        // No projects open
        if (openProjects.isEmpty()) {
            return Result(
                isError = true,
                errorResult = buildStructuredErrorResult(
                    payload = buildJsonObject {
                        put("error", ErrorMessages.ERROR_NO_PROJECT_OPEN)
                        put("message", ErrorMessages.MSG_NO_PROJECT_OPEN)
                    },
                    format = responseFormat()
                )
            )
        }

        // If project_path is provided, find matching project
        if (projectPath != null) {
            val normalizedPath = normalizePath(projectPath)

            // 1. Exact basePath match
            val exactMatch = openProjects.find { normalizePath(it.basePath ?: "") == normalizedPath }
            if (exactMatch != null) {
                return Result(project = exactMatch)
            }

            // 2. Match against module content roots (workspace support)
            val moduleMatch = findProjectByModuleContentRoot(openProjects, normalizedPath)
            if (moduleMatch != null) {
                return Result(project = moduleMatch)
            }

            // 3. Match if the given path is a subdirectory of an open project
            val parentMatch = openProjects.find { proj ->
                val basePath = normalizePath(proj.basePath ?: "")
                basePath.isNotEmpty() && normalizedPath.startsWith("$basePath/")
            }
            if (parentMatch != null) {
                return Result(project = parentMatch)
            }

            return Result(
                isError = true,
                errorResult = buildStructuredErrorResult(
                    payload = buildJsonObject {
                        put("error", ErrorMessages.ERROR_PROJECT_NOT_FOUND)
                        put("message", ErrorMessages.msgProjectNotFound(projectPath))
                        put("available_projects", buildAvailableProjectsArray(openProjects))
                    },
                    format = responseFormat()
                )
            )
        }

        // Only one project open - use it
        if (openProjects.size == 1) {
            return Result(project = openProjects.first())
        }

        // Multiple projects open, no path specified - return error with list
        return Result(
            isError = true,
            errorResult = buildStructuredErrorResult(
                payload = buildJsonObject {
                    put("error", ErrorMessages.ERROR_MULTIPLE_PROJECTS)
                    put("message", ErrorMessages.MSG_MULTIPLE_PROJECTS)
                    put("available_projects", buildAvailableProjectsArray(openProjects))
                },
                format = responseFormat()
            )
        )
    }

    /**
     * Finds a project by checking if any of its module content roots match the given path.
     * This supports workspace projects where sub-projects are represented as modules
     * with content roots in different directories.
     */
    private fun findProjectByModuleContentRoot(projects: List<Project>, normalizedPath: String): Project? {
        for (project in projects) {
            try {
                val modules = ModuleManager.getInstance(project).modules
                for (module in modules) {
                    val contentRoots = ModuleRootManager.getInstance(module).contentRoots
                    for (root in contentRoots) {
                        if (normalizePath(root.path) == normalizedPath) {
                            return project
                        }
                    }
                }
            } catch (e: Exception) {
                LOG.debug("Failed to check module content roots for project ${project.name}", e)
            }
        }
        return null
    }

    /**
     * Builds the available_projects JSON array, optionally including workspace
     * sub-project paths so AI agents can discover the correct paths to use.
     *
     * Collection of entries (touches the IntelliJ Platform) is separated from
     * JSON serialization (pure, unit-testable via [buildAvailableProjectsJson]).
     */
    private fun buildAvailableProjectsArray(openProjects: List<Project>): JsonArray {
        val includeWorkspaceSubProjects = isExpandedMode()
        val entries = collectAvailableProjectEntries(openProjects, includeWorkspaceSubProjects)
        return buildAvailableProjectsJson(entries, includeWorkspaceSubProjects)
    }

    private fun collectAvailableProjectEntries(
        openProjects: List<Project>,
        includeWorkspaceSubProjects: Boolean
    ): List<AvailableProjectEntry> {
        val entries = mutableListOf<AvailableProjectEntry>()
        for (proj in openProjects) {
            entries += AvailableProjectEntry(
                name = proj.name,
                path = proj.basePath ?: ""
            )

            if (!includeWorkspaceSubProjects) continue

            try {
                val modules = ModuleManager.getInstance(proj).modules
                for (module in modules) {
                    val contentRoots = ModuleRootManager.getInstance(module).contentRoots
                    for (root in contentRoots) {
                        val rootPath = root.path
                        if (rootPath != proj.basePath) {
                            entries += AvailableProjectEntry(
                                name = module.name,
                                path = rootPath,
                                workspace = proj.name
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                LOG.debug("Failed to list module content roots for project ${proj.name}", e)
            }
        }
        return entries
    }

    /**
     * Reads the `availableProjectsMode` setting defensively so callers don't
     * fail if the application service is unavailable (e.g. when invoked from
     * a unit-test context where settings aren't registered).
     */
    private fun isExpandedMode(): Boolean =
        runCatching { McpSettings.getInstance().availableProjectsMode }
            .getOrDefault(McpSettings.AvailableProjectsMode.EXPANDED) ==
            McpSettings.AvailableProjectsMode.EXPANDED

    private fun responseFormat(): McpSettings.ResponseFormat =
        runCatching { McpSettings.getInstance().responseFormat }
            .getOrDefault(McpSettings.ResponseFormat.JSON)
}
