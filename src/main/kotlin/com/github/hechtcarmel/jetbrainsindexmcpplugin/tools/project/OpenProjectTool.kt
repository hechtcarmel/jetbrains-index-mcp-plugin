package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.BuildSystemLinker
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.LinkResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ProjectUtils
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ex.ProjectManagerEx
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.VfsUtilCore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Path

class OpenProjectTool : AbstractMcpTool() {

    override val requiresPsiSync = false
    override val participatesInLifecycle = false

    override val name = "ide_open_project"

    override val description = """
        Open a project by filesystem path and wait until indexing is complete.

        Blocks until the IDE is ready for code intelligence on the opened project,
        so subsequent MCP tool calls against the new project will succeed immediately.
        If the project is already open, returns successfully right away.

        This tool does not enroll the project in lifecycle management. Lifecycle enrollment
        happens automatically on the first real semantic tool call (find references,
        diagnostics, refactoring, etc.) after the project is open — not on open/close itself.

        Requires at least one project to already be open (needed as the JSON-RPC context).

        Opening a project the IDE has not seen before may show the modal "Trust project?"
        dialog, which only a human can answer; the call fails after timeoutSeconds if the
        project has not opened by then.

        When invoked, this tool marks the target directory as trusted before opening,
        so the trust dialog does not appear. Build scripts (Maven, Gradle) may execute
        on import.

        Parameters:
        - path: absolute filesystem path of the project directory to open (required)
        - autoLink (optional): when true, automatically link an unlinked Maven/Gradle build system after opening. Default: false.
        - excludeDirectories (optional): array of directory names to exclude from indexing and refactoring scope. Useful for non-code directories (workspace docs, symlinks to markdown) that interfere with rename/move refactoring. Applied after autoLink completes. Each name must not be blank or contain '..'.
        - timeoutSeconds (optional): maximum seconds to wait for opening + indexing. Default: $DEFAULT_TIMEOUT_SECONDS.
        - project_path (optional): selects the JSON-RPC context project when multiple are open

        Example: { "path": "/Users/dev/myproject", "autoLink": true }
        Example: { "path": "/Users/dev/myproject", "excludeDirectories": ["wksp", ".claude", "node_modules"] }
    """.trimIndent()

    override val inputSchema: ToolSchema = SchemaBuilder.tool()
        .stringProperty("path", "Absolute filesystem path of the project directory to open.", required = true)
        .booleanProperty("autoLink", "Automatically link an unlinked Maven/Gradle build system after opening. Default: false.")
        .stringArrayProperty("excludeDirectories",
            "Directory names to exclude from indexing and refactoring scope " +
                "(e.g. [\"wksp\", \".claude\", \"node_modules\"]). " +
                "Excluded directories are ignored by code intelligence and refactoring tools. " +
                "Applied after autoLink completes."
        )
        .intProperty(
            ParamNames.TIMEOUT_SECONDS,
            "Maximum seconds to wait for the project to open and finish indexing. " +
                "Must be a positive integer. Default: $DEFAULT_TIMEOUT_SECONDS."
        )
        .projectPath()
        .build()

    private enum class OpenOutcome { OPEN_FAILED, CLOSED_WHILE_WAITING, READY }

    override suspend fun doExecute(project: Project, arguments: JsonObject): CallToolResult {
        val path = requiredStringArg(arguments, "path").getOrElse {
            return createErrorResult(it.message ?: "Missing required parameter: path")
        }
        if (!File(path).isAbsolute) {
            return createErrorResult("path must be an absolute path, got: $path")
        }

        val autoLink = arguments["autoLink"]?.jsonPrimitive?.booleanOrNull ?: false
        val excludeDirs = parseExcludeDirectories(arguments)
            ?: return createErrorResult("excludeDirectories entries must not be blank or contain '..'.")
        val timeoutSeconds = arguments[ParamNames.TIMEOUT_SECONDS]?.jsonPrimitive?.intOrNull
            ?: DEFAULT_TIMEOUT_SECONDS
        if (timeoutSeconds <= 0) {
            return createErrorResult("timeoutSeconds must be a positive integer.")
        }

        ProjectUtils.findOpenProjectByPath(path)?.let { existing ->
            val actions = mutableListOf<String>()
            if (autoLink) {
                val linkMsg = tryAutoLink(existing, path)
                if (linkMsg != null) actions.add(linkMsg)
            }
            if (excludeDirs.isNotEmpty()) {
                val excludeMsg = applyExclusions(existing, path, excludeDirs)
                if (excludeMsg != null) actions.add(excludeMsg)
            }
            val msg = "Project '${existing.name}' is already open."
            return createSuccessResult(if (actions.isNotEmpty()) "$msg ${actions.joinToString(" ")}" else msg)
        }

        val dir = File(path)
        if (!dir.exists()) return createErrorResult("Path does not exist: $path")
        if (!dir.isDirectory) return createErrorResult("Path is not a directory: $path")

        TrustedProjects.setProjectTrusted(Path.of(path), true)

        var openedProject: Project? = null
        val setupActions = mutableListOf<String>()
        val outcome = withTimeoutOrNull(timeoutSeconds * 1000L) {
            val opened = ProjectManagerEx.getInstanceEx().openProjectAsync(Path.of(path), ProjectUtils.openTask())
                ?: return@withTimeoutOrNull OpenOutcome.OPEN_FAILED
            openedProject = opened
            if (!ProjectUtils.awaitSmartMode(opened)) return@withTimeoutOrNull OpenOutcome.CLOSED_WHILE_WAITING
            if (autoLink) {
                val linkMsg = tryAutoLink(opened, path)
                if (linkMsg != null) setupActions.add(linkMsg)
                ProjectUtils.awaitSmartMode(opened)
            }
            if (excludeDirs.isNotEmpty()) {
                val excludeMsg = applyExclusions(opened, path, excludeDirs)
                if (excludeMsg != null) setupActions.add(excludeMsg)
                ProjectUtils.awaitSmartMode(opened)
            }
            OpenOutcome.READY
        }

        return when (outcome) {
            OpenOutcome.READY -> {
                val msg = "Project '${openedProject!!.name}' is open and ready."
                if (setupActions.isNotEmpty()) createSuccessResult("$msg ${setupActions.joinToString(" ")}") else createSuccessResult(msg)
            }

            OpenOutcome.OPEN_FAILED ->
                createErrorResult("Failed to open project at: $path")

            OpenOutcome.CLOSED_WHILE_WAITING ->
                createErrorResult("Project at $path was closed while waiting for indexing to finish.")

            null -> {
                val opened = openedProject
                if (opened != null && !opened.isDisposed) {
                    createSuccessResult(
                        "Project '${opened.name}' is open but still indexing after ${timeoutSeconds}s. " +
                            "Index-dependent tools may fail until indexing completes — check ide_index_status."
                    )
                } else {
                    createErrorResult(
                        "Timed out after ${timeoutSeconds}s waiting for the project at $path to open. " +
                            "If the IDE is showing a 'Trust project?' dialog, a human must answer it; " +
                            "otherwise retry with a larger timeoutSeconds."
                    )
                }
            }
        }
    }

    private fun parseExcludeDirectories(arguments: JsonObject): List<String>? {
        val element = arguments["excludeDirectories"] ?: return emptyList()
        val names = element.jsonArray.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        for (name in names) {
            if (name.isBlank() || name.contains("..")) return null
        }
        return names
    }

    private suspend fun applyExclusions(
        project: Project,
        projectPath: String,
        dirNames: List<String>
    ): String? {
        val canonicalProjectPath = try {
            File(projectPath).canonicalPath
        } catch (_: Exception) {
            projectPath
        }
        val excluded = mutableListOf<String>()
        val alreadyExcluded = mutableListOf<String>()
        val notFound = mutableListOf<String>()
        val errors = mutableListOf<String>()
        var matchedAnyRoot = false

        edtAction {
            val moduleManager = ModuleManager.getInstance(project)
            for (module in moduleManager.modules) {
                val rootModel = ModuleRootManager.getInstance(module).modifiableModel
                try {
                    var modified = false
                    for (contentEntry in rootModel.contentEntries) {
                        val contentRoot = contentEntry.file ?: continue
                        val canonicalRoot = try {
                            File(contentRoot.path).canonicalPath
                        } catch (_: Exception) {
                            contentRoot.path
                        }
                        if (canonicalRoot != canonicalProjectPath && !canonicalRoot.startsWith(canonicalProjectPath + File.separator)) continue
                        matchedAnyRoot = true

                        for (dirName in dirNames) {
                            val excludeDir = contentRoot.findChild(dirName)
                            if (excludeDir == null) {
                                if (dirName !in notFound && dirName !in excluded && dirName !in alreadyExcluded) {
                                    notFound.add(dirName)
                                }
                                continue
                            }
                            val excludeUrl = VfsUtilCore.pathToUrl(excludeDir.path)
                            val isAlready = contentEntry.excludeFolderUrls.any { it == excludeUrl }
                            if (isAlready) {
                                notFound.remove(dirName)
                                if (dirName !in alreadyExcluded && dirName !in excluded) {
                                    alreadyExcluded.add(dirName)
                                }
                            } else {
                                contentEntry.addExcludeFolder(excludeUrl)
                                modified = true
                            }
                        }
                    }
                    if (modified) {
                        WriteAction.run<Exception> { rootModel.commit() }
                        for (contentEntry in ModuleRootManager.getInstance(module).contentEntries) {
                            for (dirName in dirNames) {
                                if (dirName in excluded || dirName in alreadyExcluded) continue
                                val contentRoot = contentEntry.file ?: continue
                                val excludeDir = contentRoot.findChild(dirName) ?: continue
                                val excludeUrl = VfsUtilCore.pathToUrl(excludeDir.path)
                                if (contentEntry.excludeFolderUrls.any { it == excludeUrl }) {
                                    notFound.remove(dirName)
                                    alreadyExcluded.remove(dirName)
                                    excluded.add(dirName)
                                }
                            }
                        }
                    } else {
                        rootModel.dispose()
                    }
                } catch (e: CancellationException) {
                    rootModel.dispose()
                    throw e
                } catch (e: ProcessCanceledException) {
                    rootModel.dispose()
                    throw e
                } catch (e: Exception) {
                    rootModel.dispose()
                    errors.add(e.message ?: "Unknown error modifying module '${module.name}'")
                }
            }
        }

        if (!matchedAnyRoot && errors.isEmpty()) {
            return "No content root matched project path '$projectPath'."
        }

        if (excluded.isEmpty() && alreadyExcluded.isEmpty() && notFound.isEmpty() && errors.isEmpty()) {
            return null
        }

        val parts = mutableListOf<String>()
        if (excluded.isNotEmpty()) parts.add("Excluded: ${excluded.joinToString(", ")}.")
        if (alreadyExcluded.isNotEmpty()) parts.add("Already excluded: ${alreadyExcluded.joinToString(", ")}.")
        if (notFound.isNotEmpty()) parts.add("Not found: ${notFound.joinToString(", ")}.")
        if (errors.isNotEmpty()) parts.add("Errors: ${errors.joinToString("; ")}.")
        return parts.joinToString(" ")
    }

    private suspend fun tryAutoLink(project: Project, path: String): String? {
        return try {
            when (val result = BuildSystemLinker.linkBuildSystem(project, path)) {
                is LinkResult.Linked -> "${result.systemName} project linked."
                is LinkResult.AlreadyLinked -> null
                is LinkResult.NoBuildFile -> null
                is LinkResult.PluginUnavailable -> "Auto-link skipped: ${result.systemName} plugin not available."
                is LinkResult.Failed -> "Auto-link failed: ${result.error}"
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            "Auto-link failed: ${e.message}"
        }
    }

    companion object {
        private const val DEFAULT_TIMEOUT_SECONDS = 600
    }
}
