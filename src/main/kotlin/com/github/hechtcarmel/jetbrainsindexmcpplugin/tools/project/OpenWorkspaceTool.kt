package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.MavenImportResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ProjectUtils
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ex.ProjectManagerEx
import com.intellij.openapi.vfs.LocalFileSystem
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import java.io.File
import java.security.MessageDigest

class OpenWorkspaceTool : AbstractMcpTool() {

    override val requiresPsiSync = false
    override val participatesInLifecycle = false

    override val name = "ide_open_workspace"

    override val description = """
        Open several Maven repositories together as ONE IntelliJ workspace window with
        cross-repository code intelligence: ide_find_references, ide_refactor_rename and
        other tools then work across all of them.

        Call it ONCE with every repository you need: never once per repository, and not
        again with a different subset of the same repositories. Each distinct set opens its
        own window and leaves its own workspace directory behind, and no window sees the
        repositories outside its set. For a single repository use ide_open_project (with
        "autoLink": true for Maven/Gradle) instead.

        Two modes; `path` and `modules` are mutually exclusive:
        1. Directory scan: `path` combines every immediate subdirectory containing pom.xml.
        2. Explicit modules: `modules` lists the absolute paths of the Maven projects to combine.

        If an open project already contains every requested Maven project as a module, it is
        reused and no new window opens. Workspaces are cached: the same directory or the same
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
                "Absolute paths of the Maven project directories to combine. List every repository you need " +
                    "in this one call (at least two). Mutually exclusive with 'path'. The same set in any " +
                    "order reuses the cached workspace."
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

    private enum class OpenOutcome { OPEN_FAILED, CLOSED_WHILE_WAITING, MAVEN_UNAVAILABLE, IMPORT_INCOMPLETE, STALE_MODULES, READY }

    override suspend fun doExecute(project: Project, arguments: JsonObject): CallToolResult {
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
                .sortedBy { ProjectUtils.canonicalNormalizedPath(it.absolutePath) }
                .distinctBy { ProjectUtils.canonicalNormalizedPath(it.absolutePath) }
            workspaceHashKey = mavenProjects.joinToString("\n") { ProjectUtils.canonicalNormalizedPath(it.absolutePath) }
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
            workspaceHashKey = ProjectUtils.canonicalNormalizedPath(rootPath)
        }

        val workspaceDir = workspaceDirFor(workspaceHashKey)
        var openedProject: Project? = ProjectUtils.findOpenProjectByPath(workspaceDir.absolutePath)
        val alreadyOpen = openedProject != null
        val expectedRoots = mavenProjects.map { ProjectUtils.canonicalNormalizedPath(it.absolutePath) }.toSet()

        // Issue #436: agents opened a workspace per repository, or per subset of the same
        // repositories. Every such call cost another window indexing code an open window
        // already covered, and another directory under ide-workspaces/ that is never cleaned up.
        if (!alreadyOpen) {
            ProjectUtils.findOpenProjectContainingRoots(expectedRoots)?.let { existing ->
                return reuseOpenProject(existing, mavenProjects, timeoutSeconds)
            }
            if (mavenProjects.size < MIN_WORKSPACE_PROJECTS) {
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

        val outcome = withTimeoutOrNull(timeoutSeconds * 1000L) {
            val opened = if (alreadyOpen) {
                openedProject!!
            } else {
                val o = ProjectManagerEx.getInstanceEx()
                    .openProjectAsync(workspaceDir.toPath(), ProjectUtils.openTask())
                    ?: return@withTimeoutOrNull OpenOutcome.OPEN_FAILED
                openedProject = o
                o
            }

            if (!ProjectUtils.awaitSmartMode(opened)) return@withTimeoutOrNull OpenOutcome.CLOSED_WHILE_WAITING

            val actualRoots = ProjectUtils.getModuleContentRoots(opened)
                .map(ProjectUtils::canonicalNormalizedPath).toSet()
            val missing = mavenProjects.filter { ProjectUtils.canonicalNormalizedPath(it.absolutePath) !in actualRoots }

            if (missing.isNotEmpty()) {
                for (moduleDir in missing) {
                    val dirVf = LocalFileSystem.getInstance()
                        .refreshAndFindFileByPath(moduleDir.absolutePath) ?: continue
                    when (ProjectUtils.importMavenModule(opened, dirVf)) {
                        is MavenImportResult.MavenUnavailable ->
                            return@withTimeoutOrNull OpenOutcome.MAVEN_UNAVAILABLE
                        is MavenImportResult.Failed -> { }
                        is MavenImportResult.Success -> { }
                    }
                }
                if (!ProjectUtils.awaitSmartMode(opened)) return@withTimeoutOrNull OpenOutcome.CLOSED_WHILE_WAITING
            }

            val finalRoots = ProjectUtils.getModuleContentRoots(opened)
                .map(ProjectUtils::canonicalNormalizedPath).toSet()
            val workspaceRoot = ProjectUtils.canonicalNormalizedPath(workspaceDir.absolutePath)
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
                        moduleList(mavenProjects) + "\n" + PROJECT_PATH_HINT
                )
            }
            OpenOutcome.STALE_MODULES -> {
                val roots = if (opened != null && !opened.isDisposed) {
                    ProjectUtils.getModuleContentRoots(opened).map(ProjectUtils::canonicalNormalizedPath).toSet()
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
                    ProjectUtils.getModuleContentRoots(opened).map(ProjectUtils::canonicalNormalizedPath).toSet()
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
                            moduleList(mavenProjects) + "\n" + PROJECT_PATH_HINT
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

    /**
     * Reuses [existing], which already contains every requested Maven project as a module,
     * instead of creating a workspace for them.
     */
    private suspend fun reuseOpenProject(
        existing: Project,
        mavenProjects: List<File>,
        timeoutSeconds: Int
    ): CallToolResult {
        // true: smart mode; false: closed while waiting; null: still indexing at the deadline.
        val smart = if (!DumbService.isDumb(existing)) {
            true
        } else {
            withTimeoutOrNull(timeoutSeconds * 1000L) { ProjectUtils.awaitSmartMode(existing) }
        }
        if (smart == false) {
            return createErrorResult("Project '${existing.name}' was closed while waiting for indexing.")
        }
        val status = if (smart == true) {
            "It is ready."
        } else {
            "It is still indexing after ${timeoutSeconds}s. Check ide_index_status."
        }
        return createSuccessResult(
            "Reused the open project '${existing.name}' (${existing.basePath}): it already contains all " +
                "${mavenProjects.size} requested Maven project(s) as modules, so no new workspace window " +
                "was opened. $status\n" +
                moduleList(mavenProjects) + "\n" + PROJECT_PATH_HINT
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
        mavenProjects.joinToString("\n") { "  - ${it.absolutePath}" }

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
