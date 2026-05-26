package com.github.hechtcarmel.jetbrainsindexmcpplugin.server

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ErrorMessages
import com.github.hechtcarmel.jetbrainsindexmcpplugin.lifecycle.ProjectMode
import com.github.hechtcarmel.jetbrainsindexmcpplugin.lifecycle.ProjectModeService
import com.github.hechtcarmel.jetbrainsindexmcpplugin.settings.McpSettings
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ContentBlock
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ResponseFormatter
import com.intellij.ide.impl.OpenProjectTask
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.ex.ProjectManagerEx
import com.intellij.openapi.roots.ModuleRootManager
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.nio.file.Path
import kotlin.coroutines.resume

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
            val closedManaged = runCatching {
                ProjectModeService.getInstance().getAllManagedModes()
                    .filterValues { it == ProjectMode.CLOSED }
                    .keys.toList()
            }.getOrDefault(emptyList())
            return Result(
                isError = true,
                errorResult = buildStructuredErrorResult(
                    payload = buildJsonObject {
                        put("error", ErrorMessages.ERROR_NO_PROJECT_OPEN)
                        put("message", ErrorMessages.MSG_NO_PROJECT_OPEN)
                        if (closedManaged.isNotEmpty()) {
                            put("hint", "These projects were closed by the lifecycle manager and will auto-reopen if you retry with project_path set to one of them.")
                            put("managed_closed_projects", buildJsonArray {
                                closedManaged.forEach { add(it) }
                            })
                        }
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
                        put("hint", diagnoseProjectPath(normalizedPath))
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

    /**
     * Like [resolve], but also handles auto-opening projects that were closed by the
     * MCP lifecycle manager. When a CLOSED managed project is targeted by project_path,
     * this function reopens it, waits for indexing to complete, and returns it.
     *
     * Falls back to [resolve] for all other cases.
     */
    suspend fun resolveOrOpen(projectPath: String?): Result {
        // Fast path: project is already open
        val standard = resolve(projectPath)
        if (!standard.isError) return standard

        // Check if the path matches a project we intentionally closed
        if (projectPath != null) {
            val normalizedPath = normalizePath(projectPath)
            val modeService = runCatching { ProjectModeService.getInstance() }.getOrNull()
            if (modeService != null && modeService.wasClosedByUs(normalizedPath)) {
                LOG.info("Auto-opening closed managed project: $normalizedPath")
                val project = reopenAndAwaitSmartMode(normalizedPath)
                    ?: return buildErrorResult("Failed to reopen managed project: $normalizedPath", projectPath)
                modeService.markReopened(normalizedPath)
                modeService.resetInactivityTimer(project)
                return Result(project = project)
            }

            // Path given but not resolvable — upgrade the error with path diagnosis so the
            // caller knows whether the path doesn't exist, isn't an IntelliJ project, or is
            // simply not open yet.
            return buildErrorResult(ErrorMessages.msgProjectNotFound(projectPath), projectPath)
        }

        // No project_path given and no projects open — if any managed projects were closed
        // (by the lifecycle manager or otherwise tracked), auto-open one to restore MCP
        // routing. The caller can then retry with a specific project_path if needed.
        val modeService = runCatching { ProjectModeService.getInstance() }.getOrNull()
        val fallbackPath = modeService?.getAllManagedModes()
            ?.filterValues { it == com.github.hechtcarmel.jetbrainsindexmcpplugin.lifecycle.ProjectMode.CLOSED }
            ?.keys?.firstOrNull()
        if (fallbackPath != null) {
            LOG.info("No open projects and no project_path — auto-opening managed project: $fallbackPath")
            val project = reopenAndAwaitSmartMode(fallbackPath)
            if (project != null) {
                modeService?.markReopened(fallbackPath)
                modeService?.resetInactivityTimer(project)
                return Result(project = project)
            }
        }

        return standard
    }

    private suspend fun reopenAndAwaitSmartMode(path: String): Project? {
        // NonCancellable: the open operation must not be abandoned mid-flight if the HTTP
        // request times out. If cancelled partway, IntelliJ ends up with an orphaned project
        // that is "already opened" on the next attempt.
        val project = withContext(NonCancellable) {
            try {
                ProjectManagerEx.getInstanceEx().openProjectAsync(Path.of(path), openTask())
            } catch (e: Throwable) {
                if (e.message?.contains("already opened") == true) {
                    // A previous (timed-out) attempt opened it — find the existing Project object.
                    ProjectManager.getInstance().openProjects
                        .find { normalizePath(it.basePath ?: "") == normalizePath(path) }
                } else {
                    LOG.warn("Failed to reopen managed project at $path: ${e.message}")
                    null
                }
            }
        } ?: return null

        // Wait for indexing. If the HTTP caller cancels (timeout), still return the project
        // so markReopened is called and the next request succeeds without re-opening.
        runCatching {
            suspendCancellableCoroutine { continuation ->
                DumbService.getInstance(project).runWhenSmart { continuation.resume(Unit) }
            }
        }

        return project
    }

    private fun openTask(): OpenProjectTask {
        val task = OpenProjectTask()
        // @Internal API: set forceOpenInNewFrame via reflection to avoid binary incompatibility
        // when OpenProjectTask constructor changes across IntelliJ versions.
        runCatching {
            task.javaClass.getDeclaredField("forceOpenInNewFrame")
                .apply { isAccessible = true; setBoolean(task, true) }
            task.javaClass.getDeclaredField("showWelcomeScreen")
                .apply { isAccessible = true; setBoolean(task, false) }
        }
        return task
    }

    private fun buildErrorResult(message: String, projectPath: String): Result = Result(
        isError = true,
        errorResult = buildStructuredErrorResult(
            payload = buildJsonObject {
                put("error", ErrorMessages.ERROR_PROJECT_NOT_FOUND)
                put("message", message)
                put("hint", diagnoseProjectPath(normalizePath(projectPath)))
                put("requested_path", projectPath)
            },
            format = responseFormat()
        )
    )

    private fun diagnoseProjectPath(normalizedPath: String): String {
        val dir = java.io.File(normalizedPath)
        return when {
            !dir.exists() -> "Path does not exist on disk."
            !dir.isDirectory -> "Path is a file, not a directory — project_path must point to a project root directory."
            !java.io.File(dir, ".idea").exists() ->
                "Path exists but has no .idea directory — not an IntelliJ project. " +
                "IntelliJ can only open projects initialised with a .idea directory. " +
                "If this is a JavaScript, Python, or other non-JVM project without IntelliJ project files, " +
                "use language-appropriate tools (Node, npm, etc.) instead of IDE MCP tools."
            else ->
                "Path has a .idea directory but is not open in IntelliJ. " +
                "Use ide_open_project to open it, or check ide_project_status to see if it is managed and closed."
        }
    }

    private fun responseFormat(): McpSettings.ResponseFormat =
        runCatching { McpSettings.getInstance().responseFormat }
            .getOrDefault(McpSettings.ResponseFormat.JSON)
}
