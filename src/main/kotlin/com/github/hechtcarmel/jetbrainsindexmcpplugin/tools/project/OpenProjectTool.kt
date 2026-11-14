package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.BuildSystemLinker
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.LinkResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ProjectUtils
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PlainDirectoryModules
import com.intellij.openapi.application.readAction
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
import kotlinx.serialization.json.JsonArray
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
        Open one repository by absolute path and wait for indexing (timeoutSeconds default 600).
        Already-open projects receive the same idempotent setup. Plain directories without content
        roots get a module inheriting the project SDK, with node_modules, dist and .next excluded
        by URL, even before they exist. Module files stay under .idea/modules. Existing models are
        preserved; recognized build projects are not converted into plain modules.
        Use autoLink=true for Maven/Gradle. Setup order: link, plain-root registration, explicit
        excludeDirectories; indexing is awaited after changes. Timeout reports incomplete setup/indexing;
        setup failures are errors. Modules persist; remove them in Project Structure to undo.
        For several repositories, call ide_open_workspace once with every Maven/plain directory.
        A repository already in an open workspace needs no new window: pass it as project_path.
        Requires an open project as MCP context. The directory is marked trusted before opening;
        build scripts may execute on import. Lifecycle enrollment waits for a semantic tool call.
        Parameters: path (required absolute directory), autoLink (default false), excludeDirectories
        (relative directory names; not blank or containing '..'), timeoutSeconds (positive integer),
        project_path (optional MCP context). Example: {"path": "/abs/repo", "autoLink": true}.
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
            ?: return createErrorResult("excludeDirectories entries must not be blank, absolute, or contain '..'.")
        val timeoutSeconds = arguments[ParamNames.TIMEOUT_SECONDS]?.jsonPrimitive?.intOrNull
            ?: DEFAULT_TIMEOUT_SECONDS
        if (timeoutSeconds <= 0) {
            return createErrorResult("timeoutSeconds must be a positive integer.")
        }

        val existing = ProjectUtils.findOpenProjectByPath(path)

        val dir = File(path)
        if (!dir.exists()) return createErrorResult("Path does not exist: $path")
        if (!dir.isDirectory) return createErrorResult("Path is not a directory: $path")

        if (existing == null) TrustedProjects.setProjectTrusted(Path.of(path), true)

        var openedProject: Project? = existing
        val setupActions = mutableListOf<String>()
        val outcome = try {
            withTimeoutOrNull(timeoutSeconds * 1000L) {
                val opened = existing ?: ProjectManagerEx.getInstanceEx()
                    .openProjectAsync(Path.of(path), ProjectUtils.openTask())
                    ?: return@withTimeoutOrNull OpenOutcome.OPEN_FAILED
                openedProject = opened
                if (configureOpenedProject(opened, path, autoLink, excludeDirs, setupActions)) {
                    OpenOutcome.READY
                } else OpenOutcome.CLOSED_WHILE_WAITING
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            return createErrorResult("Project setup failed: ${e.message}. Check Project Structure before retrying.")
        }

        return when (outcome) {
            OpenOutcome.READY -> {
                val msg = if (existing != null) "Project '${existing.name}' is already open and ready."
                    else "Project '${openedProject!!.name}' is open and ready."
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
                        "Project '${opened.name}' is open but setup/indexing did not finish after ${timeoutSeconds}s. " +
                            "Check ide_index_status and retry ide_open_project to finish any missing root registration."
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

    /** Kept shared by fresh and already-open paths so neither can bypass registration or indexing. */
    internal suspend fun configureOpenedProject(
        opened: Project, path: String, autoLink: Boolean, excludeDirs: List<String>, actions: MutableList<String>
    ): Boolean {
        if (!ProjectUtils.awaitSmartMode(opened)) return false
        if (autoLink) {
            tryAutoLink(opened, path)?.let(actions::add)
            if (!ProjectUtils.awaitSmartMode(opened)) return false
        }
        ensureContentRoot(opened, path)?.let(actions::add)
        if (!ProjectUtils.awaitSmartMode(opened)) return false
        if (excludeDirs.isNotEmpty()) {
            applyExclusions(opened, path, excludeDirs)?.let(actions::add)
            if (!ProjectUtils.awaitSmartMode(opened)) return false
        }
        return true
    }

    internal suspend fun ensureContentRoot(project: Project, path: String): String? {
        val hasRoots = readAction {
            ModuleManager.getInstance(project).modules.any { ModuleRootManager.getInstance(it).contentRoots.isNotEmpty() }
        }
        if (hasRoots) return null
        BuildSystemLinker.detectedBuildSystem(project, path)?.let { build ->
            return "$build build detected; no plain module was added. If files are not indexed, " +
                "use ide_link_build_system or reopen with autoLink=true."
        }
        return PlainDirectoryModules.register(project, listOf(File(path)), File(path, ".idea/modules"), onlyIfEmpty = true)
            .joinToString("\n") { it.describe() }.takeIf { it.isNotEmpty() }
    }

    private fun parseExcludeDirectories(arguments: JsonObject): List<String>? {
        val element = arguments["excludeDirectories"] ?: return emptyList()
        if (element !is JsonArray) return null
        val names = element.map {
            (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.contentOrNull ?: return null
        }
        for (name in names) {
            if (name.isBlank() || name.contains("..") || File(name).isAbsolute) return null
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
