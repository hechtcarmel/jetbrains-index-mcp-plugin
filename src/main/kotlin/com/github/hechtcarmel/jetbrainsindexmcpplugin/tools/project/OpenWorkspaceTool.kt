package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.ProjectResolver
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ProjectUtils
import com.intellij.ide.impl.OpenProjectTask
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.ex.ProjectManagerEx
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.coroutines.resume

class OpenWorkspaceTool : AbstractMcpTool() {

    override val requiresPsiSync = false
    override val participatesInLifecycle = false

    override val name = "ide_open_workspace"

    override val description = """
        Open multiple Maven projects from a root directory as a single IntelliJ workspace
        with full cross-project code intelligence.

        Scans the given directory for immediate subdirectories containing pom.xml files,
        generates a temporary Maven aggregator project, and opens it in IntelliJ. The
        result is a single project window where ide_find_references, ide_refactor_rename,
        and other tools work across all discovered modules.

        Use this when you have a multi-repo setup (e.g., a directory containing platform/,
        engine/, worker/ as separate repos) and want full cross-project refactoring.

        The workspace is created in the system temp directory and reuses the same location
        for repeated calls with the same root path. Requires the Maven plugin.

        Parameters:
        - path (required): absolute path to the root directory containing Maven projects
        - timeoutSeconds (optional): max seconds to wait for opening + indexing. Default: $DEFAULT_TIMEOUT_SECONDS.
        - project_path (optional): selects the JSON-RPC context project when multiple are open

        Example: { "path": "/Users/dev/casehub" }
    """.trimIndent()

    override val inputSchema: JsonObject = SchemaBuilder.tool()
        .stringProperty("path", "Absolute path to the root directory containing Maven project subdirectories.", required = true)
        .intProperty(
            ParamNames.TIMEOUT_SECONDS,
            "Maximum seconds to wait for opening + indexing. Default: $DEFAULT_TIMEOUT_SECONDS."
        )
        .projectPath()
        .build()

    private enum class OpenOutcome { OPEN_FAILED, CLOSED_WHILE_WAITING, READY }

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val rootPath = requiredStringArg(arguments, "path").getOrElse {
            return createErrorResult(it.message ?: "Missing required parameter: path")
        }
        if (!File(rootPath).isAbsolute) {
            return createErrorResult("path must be an absolute path, got: $rootPath")
        }
        val rootDir = File(rootPath)
        if (!rootDir.exists()) return createErrorResult("Path does not exist: $rootPath")
        if (!rootDir.isDirectory) return createErrorResult("Path is not a directory: $rootPath")

        val timeoutSeconds = arguments[ParamNames.TIMEOUT_SECONDS]?.jsonPrimitive?.intOrNull
            ?: DEFAULT_TIMEOUT_SECONDS
        if (timeoutSeconds <= 0) {
            return createErrorResult("timeoutSeconds must be a positive integer.")
        }

        val mavenProjects = rootDir.listFiles()
            ?.filter { it.isDirectory && File(it, "pom.xml").exists() }
            ?.sortedBy { it.name }
            ?: emptyList()

        if (mavenProjects.isEmpty()) {
            return createErrorResult("No Maven projects found in subdirectories of $rootPath")
        }

        val workspaceDir = createWorkspace(rootPath, mavenProjects)

        val existingProject = findOpenProjectByPath(workspaceDir.absolutePath)
        if (existingProject != null) {
            return createSuccessResult(
                "Workspace already open as '${existingProject.name}' with ${mavenProjects.size} modules:\n" +
                    mavenProjects.joinToString("\n") { "  - ${it.name}" }
            )
        }

        var openedProject: Project? = null
        val outcome = withTimeoutOrNull(timeoutSeconds * 1000L) {
            val opened = ProjectManagerEx.getInstanceEx()
                .openProjectAsync(workspaceDir.toPath(), openTask())
                ?: return@withTimeoutOrNull OpenOutcome.OPEN_FAILED
            openedProject = opened

            if (!awaitSmartMode(opened)) return@withTimeoutOrNull OpenOutcome.CLOSED_WHILE_WAITING

            val workspaceDirVf = LocalFileSystem.getInstance()
                .refreshAndFindFileByPath(workspaceDir.absolutePath)
            if (workspaceDirVf != null) {
                importMavenProject(opened, workspaceDirVf)
            }

            if (awaitSmartMode(opened)) OpenOutcome.READY else OpenOutcome.CLOSED_WHILE_WAITING
        }

        val opened = openedProject
        return when (outcome) {
            OpenOutcome.READY -> {
                val roots = if (opened != null && !opened.isDisposed) {
                    ProjectUtils.getModuleContentRoots(opened)
                } else emptyList()
                createSuccessResult(
                    "Workspace open and ready with ${mavenProjects.size} modules " +
                        "(${roots.size} content roots resolved):\n" +
                        mavenProjects.joinToString("\n") { "  - ${it.name}" }
                )
            }
            OpenOutcome.OPEN_FAILED ->
                createErrorResult("Failed to open workspace at: ${workspaceDir.absolutePath}")
            OpenOutcome.CLOSED_WHILE_WAITING ->
                createErrorResult("Workspace was closed while waiting for indexing.")
            null -> {
                if (opened != null && !opened.isDisposed) {
                    createSuccessResult(
                        "Workspace open with ${mavenProjects.size} modules but still indexing " +
                            "after ${timeoutSeconds}s. Check ide_index_status.\n" +
                            mavenProjects.joinToString("\n") { "  - ${it.name}" }
                    )
                } else {
                    createErrorResult(
                        "Timed out after ${timeoutSeconds}s. If a 'Trust project?' dialog is showing, " +
                            "a human must answer it; otherwise retry with a larger timeoutSeconds."
                    )
                }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun importMavenProject(project: Project, workspaceDirVf: VirtualFile): List<Module>? {
        val builderClass = try {
            Class.forName("org.jetbrains.idea.maven.wizards.MavenProjectAsyncBuilder")
        } catch (_: ClassNotFoundException) {
            return null
        }
        val providerClass = Class.forName(
            "com.intellij.openapi.externalSystem.service.project.IdeModifiableModelsProvider"
        )
        val builder = builderClass.getDeclaredConstructor().newInstance()
        val commitSync = builderClass.getMethod(
            "commitSync",
            Project::class.java,
            VirtualFile::class.java,
            providerClass
        )
        return commitSync.invoke(builder, project, workspaceDirVf, null) as? List<Module>
    }

    private fun createWorkspace(rootPath: String, mavenProjects: List<File>): File {
        val hash = hashPath(rootPath)
        val workspaceDir = File(System.getProperty("java.io.tmpdir"), "ide-workspace-$hash")
        workspaceDir.mkdirs()

        val workspacePath = workspaceDir.toPath()
        val moduleEntries = mavenProjects.map { moduleDir ->
            val relativePath = try {
                workspacePath.relativize(moduleDir.toPath()).toString()
            } catch (e: IllegalArgumentException) {
                throw IllegalStateException(
                    "Cannot create workspace: module path ${moduleDir.absolutePath} cannot be " +
                        "relativized from workspace at ${workspaceDir.absolutePath}. " +
                        "Both paths must be on the same filesystem root."
                )
            }
            relativePath
        }

        val pomContent = generateAggregatorPom(hash, moduleEntries)
        File(workspaceDir, "pom.xml").writeText(pomContent)

        return workspaceDir
    }

    private fun generateAggregatorPom(hash: String, modulePaths: List<String>): String {
        val moduleEntries = modulePaths.joinToString("\n") { "    <module>${escapeXml(it)}</module>" }
        return """<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>workspace</groupId>
  <artifactId>ide-workspace-$hash</artifactId>
  <version>1.0</version>
  <packaging>pom</packaging>
  <name>ide-workspace-$hash</name>
  <modules>
$moduleEntries
  </modules>
</project>
"""
    }

    private fun escapeXml(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun hashPath(path: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(path.toByteArray())
        return bytes.take(8).joinToString("") { "%02x".format(it) }
    }

    private suspend fun awaitSmartMode(opened: Project): Boolean =
        suspendCancellableCoroutine { continuation ->
            ApplicationManager.getApplication().invokeLater({
                if (!opened.isDisposed) {
                    DumbService.getInstance(opened).runWhenSmart {
                        if (continuation.isActive) continuation.resume(true)
                    }
                } else {
                    if (continuation.isActive) continuation.resume(false)
                }
            }, ModalityState.nonModal())
        }

    private fun findOpenProjectByPath(path: String): Project? {
        val requested = canonicalNormalizedPath(path)
        return ProjectManager.getInstance().openProjects.firstOrNull { open ->
            !open.isDefault && open.basePath?.let { canonicalNormalizedPath(it) } == requested
        }
    }

    private fun canonicalNormalizedPath(path: String): String {
        val canonical = runCatching { File(path).canonicalPath }.getOrElse { File(path).absolutePath }
        return ProjectResolver.normalizePath(canonical)
    }

    private fun openTask(): OpenProjectTask =
        OpenProjectTask.build().withForceOpenInNewFrame(true)

    companion object {
        private const val DEFAULT_TIMEOUT_SECONDS = 600
    }
}
