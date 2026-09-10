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
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ex.ProjectManagerEx
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
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
        If the project is already open, applies any requested setup (autoLink,
        excludeDirectories) and returns with a summary of what was configured.

        The project directory is automatically trusted before opening — no manual
        trust dialog will appear. Build scripts (Maven, Gradle) may execute on import.

        For directories with no build system at root, the tool registers the directory
        as a content root so all files are indexed. For mixed repos (e.g., TypeScript
        at root with nested Maven modules), autoLink detects and imports nested
        Maven/Gradle subprojects automatically.

        Parameters:
        - path: absolute filesystem path of the project directory to open (required)
        - autoLink (optional): automatically detect and link build systems — root-level Maven/Gradle, or nested Maven/Gradle subprojects in immediate subdirectories. Default: true.
        - excludeDirectories (optional): array of directory names to exclude from indexing and refactoring scope. Useful for non-code directories (workspace docs, symlinks to markdown) that interfere with rename/move refactoring.
        - timeoutSeconds (optional): maximum seconds to wait for opening + indexing. Default: $DEFAULT_TIMEOUT_SECONDS.
        - project_path (optional): selects the JSON-RPC context project when multiple are open

        Example: { "path": "/Users/dev/myproject" }
        Example: { "path": "/Users/dev/mixed-repo", "excludeDirectories": ["wksp", ".claude", "node_modules"] }
    """.trimIndent()

    override val inputSchema: ToolSchema = SchemaBuilder.tool()
        .stringProperty("path", "Absolute filesystem path of the project directory to open.", required = true)
        .booleanProperty("autoLink", "Automatically detect and link build systems (root-level or nested Maven/Gradle). Default: true.")
        .property("excludeDirectories", kotlinx.serialization.json.buildJsonObject {
            put("type", kotlinx.serialization.json.JsonPrimitive("array"))
            put("description", kotlinx.serialization.json.JsonPrimitive(
                "Directory names to exclude from indexing and refactoring scope."
            ))
            put("items", kotlinx.serialization.json.buildJsonObject {
                put("type", kotlinx.serialization.json.JsonPrimitive("string"))
            })
        })
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

        val autoLink = arguments["autoLink"]?.jsonPrimitive?.booleanOrNull ?: true
        val excludeDirs = arguments["excludeDirectories"]?.let { elem ->
            if (elem is kotlinx.serialization.json.JsonArray) elem.map { it.jsonPrimitive.content } else null
        } ?: emptyList()
        val timeoutSeconds = arguments[ParamNames.TIMEOUT_SECONDS]?.jsonPrimitive?.intOrNull
            ?: DEFAULT_TIMEOUT_SECONDS
        if (timeoutSeconds <= 0) {
            return createErrorResult("timeoutSeconds must be a positive integer.")
        }

        ProjectUtils.findOpenProjectByPath(path)?.let { existing ->
            val setup = applySetup(existing, path, autoLink, excludeDirs)
            val msg = "Project '${existing.name}' is already open."
            return createSuccessResult(if (setup.isNotEmpty()) "$msg $setup" else msg)
        }

        val dir = File(path)
        if (!dir.exists()) return createErrorResult("Path does not exist: $path")
        if (!dir.isDirectory) return createErrorResult("Path is not a directory: $path")

        TrustedProjects.setProjectTrusted(Path.of(path), true)

        var openedProject: Project? = null
        var linkMsg: String? = null
        val outcome = withTimeoutOrNull(timeoutSeconds * 1000L) {
            val opened = ProjectManagerEx.getInstanceEx().openProjectAsync(Path.of(path), ProjectUtils.openTask())
                ?: return@withTimeoutOrNull OpenOutcome.OPEN_FAILED
            openedProject = opened
            if (!ProjectUtils.awaitSmartMode(opened)) return@withTimeoutOrNull OpenOutcome.CLOSED_WHILE_WAITING
            linkMsg = applySetup(opened, path, autoLink, excludeDirs)
            if (autoLink) ProjectUtils.awaitSmartMode(opened)
            OpenOutcome.READY
        }

        return when (outcome) {
            OpenOutcome.READY -> {
                val msg = "Project '${openedProject!!.name}' is open and ready."
                if (linkMsg != null) createSuccessResult("$msg $linkMsg") else createSuccessResult(msg)
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

    private suspend fun applySetup(
        project: Project,
        path: String,
        autoLink: Boolean,
        excludeDirs: List<String>
    ): String {
        val actions = mutableListOf<String>()

        val contentRootCreated = ensureContentRoot(project, path)
        if (contentRootCreated) actions.add("Content root registered.")

        if (excludeDirs.isNotEmpty()) {
            val excluded = excludeDirectories(project, path, excludeDirs)
            if (excluded > 0) actions.add("$excluded director${if (excluded == 1) "y" else "ies"} excluded.")
        }

        if (autoLink) {
            val linkMsg = tryAutoLink(project, path)
            if (linkMsg != null) actions.add(linkMsg)
        }

        return actions.joinToString(" ")
    }

    private suspend fun tryAutoLink(project: Project, path: String): String? {
        return try {
            when (val result = BuildSystemLinker.linkBuildSystem(project, path)) {
                is LinkResult.Linked -> "${result.systemName} project linked."
                is LinkResult.AlreadyLinked -> null
                is LinkResult.NoBuildFile -> tryImportNestedBuildModules(project, path)
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

    private suspend fun tryImportNestedBuildModules(project: Project, projectPath: String): String? {
        val projectDir = File(projectPath)
        val subdirs = projectDir.listFiles()?.filter { it.isDirectory } ?: return null

        val mavenDirs = subdirs.filter { File(it, "pom.xml").exists() }
        val gradleDirs = subdirs.filter { dir ->
            dir !in mavenDirs && (File(dir, "build.gradle").exists() || File(dir, "build.gradle.kts").exists())
        }

        val actions = mutableListOf<String>()

        if (mavenDirs.isNotEmpty()) {
            val mavenMsg = importNestedMavenModules(project, mavenDirs)
            if (mavenMsg != null) actions.add(mavenMsg)
        }

        if (gradleDirs.isNotEmpty()) {
            val gradleMsg = linkNestedGradleModules(project, gradleDirs)
            if (gradleMsg != null) actions.add(gradleMsg)
        }

        return actions.joinToString(" ").takeIf { it.isNotEmpty() }
    }

    private fun importNestedMavenModules(project: Project, mavenDirs: List<File>): String? {
        val imported = mutableListOf<String>()
        val failed = mutableListOf<String>()
        val existingRoots = ProjectUtils.getModuleContentRoots(project).map { it.trimEnd('/') }.toSet()

        for (dir in mavenDirs) {
            val modulePath = dir.absolutePath
            if (existingRoots.any { it == modulePath.trimEnd('/') }) continue
            val dirVf = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                .refreshAndFindFileByPath(modulePath) ?: continue
            when (ProjectUtils.importMavenModule(project, dirVf)) {
                is com.github.hechtcarmel.jetbrainsindexmcpplugin.util.MavenImportResult.Success ->
                    imported.add(dir.name)
                is com.github.hechtcarmel.jetbrainsindexmcpplugin.util.MavenImportResult.MavenUnavailable ->
                    return null
                is com.github.hechtcarmel.jetbrainsindexmcpplugin.util.MavenImportResult.Failed ->
                    failed.add(dir.name)
            }
        }

        if (imported.isEmpty()) return null
        val msg = "Imported ${imported.size} nested Maven module(s): ${imported.joinToString(", ")}."
        return if (failed.isNotEmpty()) "$msg Failed: ${failed.joinToString(", ")}." else msg
    }

    private suspend fun linkNestedGradleModules(project: Project, gradleDirs: List<File>): String? {
        val linked = mutableListOf<String>()
        for (dir in gradleDirs) {
            val result = BuildSystemLinker.linkBuildSystem(project, dir.absolutePath)
            if (result is LinkResult.Linked) linked.add(dir.name)
        }
        if (linked.isEmpty()) return null
        return "Linked ${linked.size} nested Gradle module(s): ${linked.joinToString(", ")}."
    }

    private suspend fun ensureContentRoot(project: Project, projectPath: String): Boolean {
        val hasContentRoots = com.intellij.openapi.roots.ProjectRootManager.getInstance(project)
            .contentRoots.any { it.path == projectPath || it.path.startsWith("$projectPath/") }
        if (hasContentRoots) return false

        val projectDir = com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(projectPath) ?: return false
        edtAction {
            com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
                val moduleManager = com.intellij.openapi.module.ModuleManager.getInstance(project)
                val modules = moduleManager.modules
                if (modules.isNotEmpty()) {
                    val rootModel = com.intellij.openapi.roots.ModuleRootManager.getInstance(modules[0]).modifiableModel
                    try {
                        rootModel.addContentEntry(projectDir)
                        rootModel.commit()
                    } catch (e: Exception) {
                        rootModel.dispose()
                    }
                } else {
                    val moduleName = project.name
                    val imlPath = "$projectPath/$moduleName.iml"
                    val module = moduleManager.newModule(imlPath, "WEB_MODULE")
                    val rootModel = com.intellij.openapi.roots.ModuleRootManager.getInstance(module).modifiableModel
                    try {
                        rootModel.addContentEntry(projectDir)
                        rootModel.commit()
                    } catch (e: Exception) {
                        rootModel.dispose()
                    }
                }
            }
        }
        return true
    }

    private suspend fun excludeDirectories(project: Project, projectPath: String, dirNames: List<String>): Int {
        var totalExcluded = 0
        edtAction {
            com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
                val moduleManager = com.intellij.openapi.module.ModuleManager.getInstance(project)
                for (module in moduleManager.modules) {
                    val rootModel = com.intellij.openapi.roots.ModuleRootManager.getInstance(module).modifiableModel
                    try {
                        var modified = false
                        for (contentEntry in rootModel.contentEntries) {
                            val contentRoot = contentEntry.file ?: continue
                            for (dirName in dirNames) {
                                val excludeDir = contentRoot.findChild(dirName) ?: continue
                                val alreadyExcluded = contentEntry.excludeFolders.any {
                                    it.url == excludeDir.url
                                }
                                if (!alreadyExcluded) {
                                    contentEntry.addExcludeFolder(excludeDir)
                                    modified = true
                                    totalExcluded++
                                }
                            }
                        }
                        if (modified) rootModel.commit() else rootModel.dispose()
                    } catch (e: Exception) {
                        rootModel.dispose()
                    }
                }
            }
        }
        return totalExcluded
    }

    companion object {
        private const val DEFAULT_TIMEOUT_SECONDS = 600
    }
}
