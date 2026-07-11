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
import com.intellij.openapi.application.PathManager
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
import kotlinx.serialization.json.jsonArray
import java.io.File
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.coroutines.resume

class OpenWorkspaceTool : AbstractMcpTool() {

    override val requiresPsiSync = false
    override val participatesInLifecycle = false

    override val name = "ide_open_workspace"

    override val description = """
        Open multiple Maven projects as a single IntelliJ workspace with full cross-project
        code intelligence. Two modes:

        1. Directory scan: provide `path` to scan for immediate subdirectories containing pom.xml.
        2. Explicit modules: provide `modules` array of absolute paths to specific Maven projects.

        The `path` and `modules` parameters are mutually exclusive.

        Generates a temporary Maven aggregator project and opens it in IntelliJ. The result
        is a single project window where ide_find_references, ide_refactor_rename, and other
        tools work across all modules.

        Workspaces are cached: same directory or same module combination (in any order) reuses
        the existing workspace without reimporting. Requires the Maven plugin.

        Examples:
        - {"path": "/Users/dev/casehub"}
        - {"modules": ["/Users/dev/casehub/platform", "/Users/dev/casehub/engine", "/Users/dev/casehub/worker"]}
    """.trimIndent()

    override val inputSchema: JsonObject = SchemaBuilder.tool()
        .stringProperty("path", "Absolute path to the root directory containing Maven project subdirectories. Mutually exclusive with 'modules'.")
        .property("modules", kotlinx.serialization.json.buildJsonObject {
            put("type", kotlinx.serialization.json.JsonPrimitive("array"))
            put("description", kotlinx.serialization.json.JsonPrimitive("Array of absolute paths to Maven project directories. Mutually exclusive with 'path'. Same modules in any order reuse the cached workspace."))
            put("items", kotlinx.serialization.json.buildJsonObject { put("type", kotlinx.serialization.json.JsonPrimitive("string")) })
            put("minItems", kotlinx.serialization.json.JsonPrimitive(1))
        })
        .intProperty(
            ParamNames.TIMEOUT_SECONDS,
            "Maximum seconds to wait for opening + indexing. Default: $DEFAULT_TIMEOUT_SECONDS."
        )
        .projectPath()
        .build()

    private enum class OpenOutcome { OPEN_FAILED, CLOSED_WHILE_WAITING, MAVEN_UNAVAILABLE, IMPORT_INCOMPLETE, STALE_MODULES, READY }

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val pathArg = arguments["path"]?.jsonPrimitive?.content
        val modulesArg = arguments["modules"]?.jsonArray?.map { it.jsonPrimitive.content }

        if (pathArg != null && modulesArg != null) {
            return createErrorResult("'path' and 'modules' are mutually exclusive — provide one or the other.")
        }
        if (pathArg == null && modulesArg == null) {
            return createErrorResult("Either 'path' or 'modules' is required.")
        }

        val timeoutSeconds = arguments[ParamNames.TIMEOUT_SECONDS]?.jsonPrimitive?.intOrNull
            ?: DEFAULT_TIMEOUT_SECONDS
        if (timeoutSeconds <= 0) {
            return createErrorResult("timeoutSeconds must be a positive integer.")
        }

        val mavenProjects: List<File>
        val workspaceHashKey: String

        if (modulesArg != null) {
            val errors = mutableListOf<String>()
            val validated = mutableListOf<File>()
            for (path in modulesArg) {
                val f = File(path)
                if (!f.isAbsolute) { errors.add("Not absolute: $path"); continue }
                if (!f.exists()) { errors.add("Does not exist: $path"); continue }
                if (!f.isDirectory) { errors.add("Not a directory: $path"); continue }
                if (!File(f, "pom.xml").exists()) { errors.add("No pom.xml in: $path"); continue }
                validated.add(f)
            }
            if (errors.isNotEmpty()) {
                return createErrorResult("Invalid module paths:\n" + errors.joinToString("\n") { "  - $it" })
            }
            if (validated.isEmpty()) {
                return createErrorResult("No valid Maven projects in the provided modules list.")
            }
            mavenProjects = validated
                .sortedBy { canonicalNormalizedPath(it.absolutePath) }
                .distinctBy { canonicalNormalizedPath(it.absolutePath) }
            workspaceHashKey = mavenProjects.joinToString("\n") { canonicalNormalizedPath(it.absolutePath) }
        } else {
            val rootPath = pathArg!!
            if (!File(rootPath).isAbsolute) {
                return createErrorResult("path must be an absolute path, got: $rootPath")
            }
            val rootDir = File(rootPath).canonicalFile
            if (!rootDir.exists()) return createErrorResult("Path does not exist: $rootPath")
            if (!rootDir.isDirectory) return createErrorResult("Path is not a directory: $rootPath")

            mavenProjects = rootDir.listFiles()
                ?.filter { it.isDirectory && File(it, "pom.xml").exists() }
                ?.sortedBy { it.name }
                ?: emptyList()

            if (mavenProjects.isEmpty()) {
                return createErrorResult("No Maven projects found in subdirectories of $rootPath")
            }
            workspaceHashKey = canonicalNormalizedPath(rootPath)
        }

        val workspaceDir = try {
            createWorkspace(workspaceHashKey, mavenProjects)
        } catch (e: Exception) {
            return createErrorResult(
                "Cannot create workspace: ${e.message}. " +
                    "Both workspace and module paths must be on the same filesystem root."
            )
        }

        var openedProject: Project? = findOpenProjectByPath(workspaceDir.absolutePath)
        val alreadyOpen = openedProject != null
        val expectedRoots = mavenProjects.map { canonicalNormalizedPath(it.absolutePath) }.toSet()

        val outcome = withTimeoutOrNull(timeoutSeconds * 1000L) {
            val opened = if (alreadyOpen) {
                openedProject!!
            } else {
                val o = ProjectManagerEx.getInstanceEx()
                    .openProjectAsync(workspaceDir.toPath(), openTask())
                    ?: return@withTimeoutOrNull OpenOutcome.OPEN_FAILED
                openedProject = o
                o
            }

            if (!awaitSmartMode(opened)) return@withTimeoutOrNull OpenOutcome.CLOSED_WHILE_WAITING

            val actualRoots = ProjectUtils.getModuleContentRoots(opened)
                .map(::canonicalNormalizedPath).toSet()
            val missing = mavenProjects.filter { canonicalNormalizedPath(it.absolutePath) !in actualRoots }

            if (missing.isNotEmpty()) {
                for (moduleDir in missing) {
                    val dirVf = LocalFileSystem.getInstance()
                        .refreshAndFindFileByPath(moduleDir.absolutePath) ?: continue
                    when (val result = importMavenModule(opened, dirVf)) {
                        is ImportResult.MavenUnavailable ->
                            return@withTimeoutOrNull OpenOutcome.MAVEN_UNAVAILABLE
                        is ImportResult.Failed -> { }
                        is ImportResult.Success -> { }
                    }
                }
                if (!awaitSmartMode(opened)) return@withTimeoutOrNull OpenOutcome.CLOSED_WHILE_WAITING
            }

            val finalRoots = ProjectUtils.getModuleContentRoots(opened)
                .map(::canonicalNormalizedPath).toSet()
            val workspaceRoot = canonicalNormalizedPath(workspaceDir.absolutePath)
            val staleRoots = finalRoots.filter { root ->
                root != workspaceRoot &&
                    root !in expectedRoots &&
                    expectedRoots.none { requested -> root.startsWith("$requested/") }
            }
            if (!finalRoots.containsAll(expectedRoots)) {
                OpenOutcome.IMPORT_INCOMPLETE
            } else if (staleRoots.isNotEmpty()) {
                OpenOutcome.STALE_MODULES
            } else {
                OpenOutcome.READY
            }
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
            OpenOutcome.STALE_MODULES -> {
                val roots = if (opened != null && !opened.isDisposed) {
                    ProjectUtils.getModuleContentRoots(opened).map(::canonicalNormalizedPath).toSet()
                } else emptySet()
                val wsRoot = canonicalNormalizedPath(workspaceDir.absolutePath)
                val stale = roots.filter { root ->
                    root != wsRoot &&
                        root !in expectedRoots &&
                        expectedRoots.none { requested -> root.startsWith("$requested/") }
                }
                createErrorResult(
                    "Workspace has ${stale.size} stale module(s) that are no longer in the expected set. " +
                        "Remove stale modules from IntelliJ's project structure, or delete the " +
                        "workspace directory and reopen.\n" +
                        "Stale roots:\n" + stale.joinToString("\n") { "  ! $it" }
                )
            }
            OpenOutcome.IMPORT_INCOMPLETE -> {
                val roots = if (opened != null && !opened.isDisposed) {
                    ProjectUtils.getModuleContentRoots(opened).map(::canonicalNormalizedPath).toSet()
                } else emptySet()
                val missing = expectedRoots - roots
                createErrorResult(
                    "Workspace opened but ${missing.size} module(s) failed to import. " +
                        "Missing roots:\n" + missing.joinToString("\n") { "  ! $it" }
                )
            }
            OpenOutcome.MAVEN_UNAVAILABLE ->
                createErrorResult("Maven plugin not available — enable Maven support to use ide_open_workspace.")
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

    sealed class ImportResult {
        data class Success(val modules: List<Module>) : ImportResult()
        data object MavenUnavailable : ImportResult()
        data class Failed(val error: String) : ImportResult()
    }

    @Suppress("UNCHECKED_CAST")
    private fun importMavenModule(project: Project, directoryVf: VirtualFile): ImportResult {
        val builderClass = try {
            Class.forName("org.jetbrains.idea.maven.wizards.MavenProjectAsyncBuilder")
        } catch (_: ClassNotFoundException) {
            return ImportResult.MavenUnavailable
        }
        val providerClass = try {
            Class.forName(
                "com.intellij.openapi.externalSystem.service.project.IdeModifiableModelsProvider"
            )
        } catch (_: ClassNotFoundException) {
            return ImportResult.MavenUnavailable
        }
        val builder = builderClass.getDeclaredConstructor().newInstance()
        val commitSync = builderClass.getMethod(
            "commitSync",
            Project::class.java,
            VirtualFile::class.java,
            providerClass
        )
        return try {
            val modules = commitSync.invoke(builder, project, directoryVf, null) as? List<Module>
                ?: emptyList()
            ImportResult.Success(modules)
        } catch (e: Exception) {
            val cause = e.cause ?: e
            ImportResult.Failed("Failed to import ${directoryVf.name}: ${cause.message}")
        }
    }

    private fun createWorkspace(hashKey: String, mavenProjects: List<File>): File {
        val hash = hashPath(hashKey)
        val workspaceDir = File(PathManager.getSystemPath(), "ide-workspaces/ide-workspace-$hash")
        workspaceDir.mkdirs()

        val workspacePath = workspaceDir.toPath()
        val moduleEntries = mavenProjects.map { moduleDir ->
            workspacePath.relativize(moduleDir.toPath()).toString()
        }

        val pomContent = generateAggregatorPom(hash, moduleEntries)
        val pomFile = File(workspaceDir, "pom.xml")
        if (!pomFile.exists() || pomFile.readText() != pomContent) {
            pomFile.writeText(pomContent)
        }

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
