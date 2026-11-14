package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.MavenImportResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ProjectUtils
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.BuildSystemLinker
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PlainDirectoryModules
import com.intellij.openapi.progress.ProcessCanceledException
import kotlinx.coroutines.CancellationException
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.application.readAction
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ex.ProjectManagerEx
import com.intellij.openapi.vfs.LocalFileSystem
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.security.MessageDigest

class OpenWorkspaceTool : AbstractMcpTool() {

    override val requiresPsiSync = false
    override val participatesInLifecycle = false

    override val name = "ide_open_workspace"

    override val description = """
        Open Maven repositories and optional plain directories in ONE IntelliJ workspace window.
        Search covers all roots; language-specific references and refactoring follow IDE support.

        Call it ONCE with every repository you need: never once per repository, and not
        again with a different subset of the same repositories. Each distinct set opens its
        own window and leaves its own workspace directory behind, and no window sees the
        repositories outside its set. For a single repository use ide_open_project (with
        "autoLink": true for Maven/Gradle) instead.

        Two modes; `path` and `modules` are mutually exclusive:
        1. Directory scan: `path` combines every immediate subdirectory containing pom.xml.
        2. Explicit modules: `modules` lists absolute directories: Maven plus optional TypeScript/plain
           roots. At least one Maven project is required; other detected build systems are refused.
           Plain roots exclude node_modules, dist and .next, even before those directories exist.
           Their module files live under .idea/modules in the owning project configuration.

        An open project containing the requested Maven roots is reused; missing plain roots are added
        there instead of opening another window. Setup waits for indexing before reporting ready. Workspaces are cached: the same directory or the same
        module combination (in any order) reuses the existing workspace without reimporting.
        Once the workspace is open, target one repository by passing its directory as
        project_path. Requires the Maven plugin.

        Examples:
        - {"path": "/Users/dev/casehub"}
        - {"modules": ["/Users/dev/casehub/platform", "/Users/dev/casehub/engine", "/Users/dev/casehub/worker"]}
    """.trimIndent()

    override val inputSchema: ToolSchema = SchemaBuilder.tool()
        .stringProperty(
            "path",
            "Absolute path of a directory whose immediate subdirectories are Maven projects; all of them " +
                "are combined. Mutually exclusive with 'modules'."
        )
        .property("modules", buildJsonObject {
            put("type", "array")
            put(
                "description",
                "Absolute directories to combine: at least one Maven project plus optional plain directories. " +
                    "Other detected build systems are refused. List every directory in one call; " +
                    "a single new directory is refused. Mutually exclusive with path; order is ignored."
            )
            put("items", buildJsonObject { put("type", "string") })
            put("minItems", MIN_WORKSPACE_PROJECTS)
        })
        .intProperty(
            ParamNames.TIMEOUT_SECONDS,
            "Maximum seconds to wait for opening + indexing. Default: $DEFAULT_TIMEOUT_SECONDS."
        )
        .projectPath()
        .build()

    internal enum class OpenOutcome { OPEN_FAILED, CLOSED_WHILE_WAITING, MAVEN_UNAVAILABLE, IMPORT_INCOMPLETE, STALE_MODULES, READY }

    override suspend fun doExecute(project: Project, arguments: JsonObject): CallToolResult {
        val pathArg = arguments["path"]?.let { value ->
            (value as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotBlank() }?.content
                ?: return createErrorResult("path must be a non-empty string.")
        }
        val modulesArg = arguments["modules"]?.let { value ->
            if (value !is JsonArray) return createErrorResult("modules must be an array of non-empty path strings.")
            value.map {
                (it as? JsonPrimitive)?.takeIf { item -> item.isString && item.content.isNotBlank() }?.content
                    ?: return createErrorResult("modules must contain non-empty path strings.")
            }
        }

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
        val plainDirectories: List<File>
        val workspaceHashKey: String

        if (modulesArg != null) {
            val plan = classifyModules(project, modulesArg).getOrElse {
                return createErrorResult(it.message ?: "Invalid module directories")
            }
            mavenProjects = plan.maven
            plainDirectories = plan.plain
            workspaceHashKey = plan.all.joinToString("\n") { ProjectUtils.canonicalNormalizedPath(it.absolutePath) }
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
            plainDirectories = emptyList()
            workspaceHashKey = ProjectUtils.canonicalNormalizedPath(rootPath)
        }

        val workspaceDir = workspaceDirFor(workspaceHashKey)
        var openedProject: Project? = ProjectUtils.findOpenProjectByPath(workspaceDir.absolutePath)
        val alreadyOpen = openedProject != null
        val allModules = (mavenProjects + plainDirectories).sortedBy { ProjectUtils.canonicalNormalizedPath(it.absolutePath) }
        val registrationNotes = mutableListOf<String>()
        val expectedRoots = allModules.map { ProjectUtils.canonicalNormalizedPath(it.absolutePath) }.toSet()

        // Issue #436: agents opened a workspace per repository, or per subset of the same
        // repositories. Every such call cost another window indexing code an open window
        // already covered, and another directory under ide-workspaces/ that is never cleaned up.
        if (!alreadyOpen) {
            val mavenRoots = mavenProjects.map { ProjectUtils.canonicalNormalizedPath(it.absolutePath) }.toSet()
            readAction { ProjectUtils.findOpenProjectContainingRoots(mavenRoots) }?.let { existing ->
                return reuseOpenProject(existing, allModules, plainDirectories, timeoutSeconds)
            }
            if (allModules.size < MIN_WORKSPACE_PROJECTS) {
                return createErrorResult(singleProjectMessage(mavenProjects.single(), pathArg))
            }
        }

        try {
            writeAggregatorPom(workspaceDir, mavenProjects)
        } catch (e: Exception) {
            return createErrorResult(
                "Cannot create workspace: ${e.message}. " +
                    "Both workspace and module paths must be on the same filesystem root."
            )
        }

        val outcome = try {
            withTimeoutOrNull(timeoutSeconds * 1000L) {
                val opened = if (alreadyOpen) {
                    openedProject!!
                } else {
                    val o = ProjectManagerEx.getInstanceEx()
                        .openProjectAsync(workspaceDir.toPath(), ProjectUtils.openTask())
                        ?: return@withTimeoutOrNull OpenOutcome.OPEN_FAILED
                    openedProject = o
                    o
                }

                configureWorkspace(opened, mavenProjects, plainDirectories, workspaceDir, expectedRoots, registrationNotes)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            return createErrorResult("Workspace setup failed: ${e.message}. Check Project Structure before retrying.")
        }

        val opened = openedProject
        return when (outcome) {
            OpenOutcome.READY -> {
                val roots = if (opened != null && !opened.isDisposed) {
                    contentRoots(opened)
                } else emptyList()
                createSuccessResult(
                    "Workspace open and ready with ${allModules.size} modules " +
                        "(${roots.size} content roots resolved):\n" +
                        moduleList(allModules) + "\n" + registrationNotes.joinToString("\n") + "\n" + PROJECT_PATH_HINT
                )
            }
            OpenOutcome.STALE_MODULES -> {
                val roots = if (opened != null && !opened.isDisposed) {
                    contentRoots(opened).map(ProjectUtils::canonicalNormalizedPath).toSet()
                } else emptySet()
                val wsRoot = ProjectUtils.canonicalNormalizedPath(workspaceDir.absolutePath)
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
                    contentRoots(opened).map(ProjectUtils::canonicalNormalizedPath).toSet()
                } else emptySet()
                val missing = expectedRoots - roots
                createErrorResult(
                    "Workspace opened but ${missing.size} requested root(s) are missing. Plain roots are added only after Maven import succeeds. " +
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
                        "Workspace open for ${allModules.size} requested roots, but setup/indexing did not finish " +
                            "after ${timeoutSeconds}s. Check ide_index_status and retry with the same complete modules list.\n" +
                            moduleList(allModules) + "\n" + registrationNotes.joinToString("\n") + "\n" + PROJECT_PATH_HINT
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

    private suspend fun contentRoots(project: Project): List<String> =
        readAction { ProjectUtils.getModuleContentRoots(project) }

    internal suspend fun configureWorkspace(
        opened: Project, mavenProjects: List<File>, plainDirectories: List<File>, workspaceDir: File,
        expectedRoots: Set<String>, registrationNotes: MutableList<String>
    ): OpenOutcome {
        if (!ProjectUtils.awaitSmartMode(opened)) return OpenOutcome.CLOSED_WHILE_WAITING
        val actualRoots = contentRoots(opened)
            .map(ProjectUtils::canonicalNormalizedPath).toSet()
        val missing = mavenProjects.filter { ProjectUtils.canonicalNormalizedPath(it.absolutePath) !in actualRoots }
        if (missing.isNotEmpty()) {
            for (moduleDir in missing) {
                val dirVf = LocalFileSystem.getInstance().refreshAndFindFileByPath(moduleDir.absolutePath)
                    ?: return OpenOutcome.IMPORT_INCOMPLETE
                currentCoroutineContext().ensureActive()
                val imported = ProjectUtils.importMavenModule(opened, dirVf)
                currentCoroutineContext().ensureActive()
                when (imported) {
                    MavenImportResult.MavenUnavailable -> return OpenOutcome.MAVEN_UNAVAILABLE
                    is MavenImportResult.Failed -> return OpenOutcome.IMPORT_INCOMPLETE
                    is MavenImportResult.Success -> Unit
                }
            }
            if (!ProjectUtils.awaitSmartMode(opened)) return OpenOutcome.CLOSED_WHILE_WAITING
        }
        val importedRoots = contentRoots(opened).map(ProjectUtils::canonicalNormalizedPath).toSet()
        if (mavenProjects.any { ProjectUtils.canonicalNormalizedPath(it.absolutePath) !in importedRoots }) {
            return OpenOutcome.IMPORT_INCOMPLETE
        }
        registrationNotes.addAll(registerPlainDirectories(opened, plainDirectories, workspaceDir))
        if (!ProjectUtils.awaitSmartMode(opened)) return OpenOutcome.CLOSED_WHILE_WAITING
        val finalRoots = contentRoots(opened).map(ProjectUtils::canonicalNormalizedPath).toSet()
        val workspaceRoot = ProjectUtils.canonicalNormalizedPath(workspaceDir.absolutePath)
        val staleRoots = finalRoots.filter { root ->
            root != workspaceRoot && root !in expectedRoots &&
                expectedRoots.none { requested -> root.startsWith("$requested/") }
        }
        return when {
            !finalRoots.containsAll(expectedRoots) -> OpenOutcome.IMPORT_INCOMPLETE
            staleRoots.isNotEmpty() -> OpenOutcome.STALE_MODULES
            else -> OpenOutcome.READY
        }
    }

    internal data class ModulePlan(val maven: List<File>, val plain: List<File>) {
        val all: List<File> get() = (maven + plain).sortedBy { ProjectUtils.canonicalNormalizedPath(it.absolutePath) }
    }

    /** Validate the complete request before creating a workspace or changing any module. */
    internal suspend fun classifyModules(project: Project, paths: List<String>): Result<ModulePlan> {
        return try {
            val directories = paths.map { path ->
                require(File(path).isAbsolute) { "Not absolute: $path" }
                val dir = File(path).canonicalFile
                require(dir.isDirectory) { "Not a directory or does not exist: $path" }
                dir
            }.distinctBy { ProjectUtils.canonicalNormalizedPath(it.absolutePath) }
            val (maven, plain) = directories.partition { File(it, "pom.xml").isFile }
            require(maven.isNotEmpty()) {
                "No Maven projects found. Use ide_open_project for one plain directory and ide_create_module for additional roots."
            }
            for (dir in plain) {
                val build = BuildSystemLinker.detectedBuildSystem(project, dir.absolutePath)
                require(build == null) { "$build build found in $dir; open it with ide_open_project and autoLink=true instead." }
            }
            Result.success(ModulePlan(maven, plain))
        } catch (e: CancellationException) { throw e
        } catch (e: ProcessCanceledException) { throw e
        } catch (e: Exception) { Result.failure(e) }
    }

    internal suspend fun registerPlainDirectories(project: Project, dirs: List<File>, ownerDirectory: File): List<String> =
        PlainDirectoryModules.register(project, dirs, File(ownerDirectory, ".idea/modules")).map { it.describe() }

    private suspend fun reuseOpenProject(
        existing: Project, allModules: List<File>, plainDirectories: List<File>, timeoutSeconds: Int
    ): CallToolResult {
        val notes = mutableListOf<String>()
        val smart = try {
            withTimeoutOrNull(timeoutSeconds * 1000L) {
                if (!ProjectUtils.awaitSmartMode(existing)) return@withTimeoutOrNull false
                notes.addAll(registerPlainDirectories(existing, plainDirectories,
                    File(existing.basePath ?: error("Open project has no configuration directory"))))
                ProjectUtils.awaitSmartMode(existing)
            }
        } catch (e: CancellationException) { throw e
        } catch (e: ProcessCanceledException) { throw e
        } catch (e: Exception) {
            return createErrorResult("Cannot add plain roots to the open project: ${e.message}. Check Project Structure before retrying.")
        }
        if (smart == false) return createErrorResult("Project '${existing.name}' was closed while waiting for indexing.")
        val status = if (smart == true) "It is ready." else "It is still indexing after ${timeoutSeconds}s. Check ide_index_status."
        return createSuccessResult(
            "Reused the open project '${existing.name}' (${existing.basePath}): " +
                "${allModules.size} requested directories; no new workspace window was opened. $status\n" +
                moduleList(allModules) + "\n" + notes.joinToString("\n") + "\n" + PROJECT_PATH_HINT
        )
    }

    private fun singleProjectMessage(mavenProject: File, scannedRoot: String?): String {
        val found = if (scannedRoot != null) {
            "Only one Maven project was found under $scannedRoot: ${mavenProject.absolutePath}."
        } else {
            "Only one Maven project was given: ${mavenProject.absolutePath}."
        }
        val openProjectArguments = buildJsonObject {
            put("path", mavenProject.absolutePath)
            put("autoLink", true)
        }
        return "$found A workspace combines two or more Maven projects; for one project it only adds " +
            "another window. Open it with ide_open_project $openProjectArguments instead. To work across " +
            "several repositories, call ide_open_workspace once with all of them in 'modules'."
    }

    private fun moduleList(mavenProjects: List<File>): String =
        mavenProjects.joinToString("\n") { "  - [${if (File(it, "pom.xml").isFile) "Maven" else "plain"}] ${it.absolutePath}" }

    private fun workspaceDirFor(hashKey: String): File =
        File(PathManager.getSystemPath(), "ide-workspaces/ide-workspace-${hashPath(hashKey)}")

    private fun writeAggregatorPom(workspaceDir: File, mavenProjects: List<File>) {
        workspaceDir.mkdirs()

        val workspacePath = workspaceDir.toPath()
        val moduleEntries = mavenProjects.map { moduleDir ->
            workspacePath.relativize(moduleDir.toPath()).toString()
        }

        val pomContent = generateAggregatorPom(workspaceDir.name, moduleEntries)
        val pomFile = File(workspaceDir, "pom.xml")
        if (!pomFile.exists() || pomFile.readText() != pomContent) {
            pomFile.writeText(pomContent)
        }
    }

    private fun generateAggregatorPom(workspaceName: String, modulePaths: List<String>): String {
        val moduleEntries = modulePaths.joinToString("\n") { "    <module>${escapeXml(it)}</module>" }
        return """<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>workspace</groupId>
  <artifactId>$workspaceName</artifactId>
  <version>1.0</version>
  <packaging>pom</packaging>
  <name>$workspaceName</name>
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

    companion object {
        private const val DEFAULT_TIMEOUT_SECONDS = 600

        /** Fewer would only wrap one project in a second window: use ide_open_project for that. */
        private const val MIN_WORKSPACE_PROJECTS = 2

        private const val PROJECT_PATH_HINT =
            "To target one of these repositories, pass its directory as project_path; " +
                "do not open it again with ide_open_project or ide_open_workspace."
    }
}
