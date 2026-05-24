package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.SyncFilesResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ProjectUtils
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

class SyncFilesTool : AbstractMcpTool() {

    override val requiresPsiSync: Boolean = false

    override val name = ToolNames.SYNC_FILES

    override val description = """
        Force the IDE to synchronize its virtual file system and PSI cache with external file changes. Use when files were created, modified, or deleted outside the IDE (e.g., by coding agents) and other IDE tools report stale results or miss references in recently changed files.
        call it on-demand only when needed.
        Parameters: paths (optional array of relative file/directory paths to sync; if omitted, syncs entire project), project_path (optional).
        Example: {} or {"paths": ["src/main/java/com/example/NewFile.java", "src/main/java/com/example/ModifiedFile.java"]}
    """.trimIndent()

    override val inputSchema: JsonObject = SchemaBuilder.tool()
        .projectPath()
        .stringArrayProperty(
            "paths",
            "File or directory paths relative to project root to sync. If omitted, syncs the entire project."
        )
        .build()

    override val outputSchema: JsonObject = SchemaBuilder.tool()
        .stringArrayProperty("syncedPaths", "Project-relative paths that were synchronized.", required = true)
        .booleanProperty("syncedAll", "Whether the entire project was synchronized.", required = true)
        .stringProperty("message", "Human-readable synchronization status message.", required = true)
        .build()

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val basePath = project.basePath
            ?: return createErrorResult("Project base path is not available.")

        val requestedPaths = arguments["paths"]?.jsonArray?.map { it.jsonPrimitive.content }

        // Determine the effective root: if project_path was a workspace sub-project,
        // use that sub-project root instead of the workspace basePath
        val projectPathArg = arguments["project_path"]?.jsonPrimitive?.content
        val effectiveRoot = resolveEffectiveRoot(project, projectPathArg) ?: basePath

        val syncedPaths: List<String>
        val syncedAll: Boolean

        if (requestedPaths != null && requestedPaths.isNotEmpty()) {
            val resolvedFiles = requestedPaths.mapNotNull { relativePath ->
                resolveFile(project, relativePath)?.let { relativePath to it }
            }
            if (resolvedFiles.isNotEmpty()) {
                VfsUtil.markDirtyAndRefresh(false, true, true, *resolvedFiles.map { it.second }.toTypedArray())
            }
            syncedPaths = resolvedFiles.map { it.first }
            syncedAll = false
        } else {
            val projectDir = LocalFileSystem.getInstance().findFileByPath(effectiveRoot)
            if (projectDir != null) {
                VfsUtil.markDirtyAndRefresh(false, true, true, projectDir)
            }
            syncedPaths = listOf(effectiveRoot)
            syncedAll = true
        }

        commitDocuments(project)

        val message = if (syncedAll) {
            "Synchronized entire project."
        } else if (requestedPaths != null && syncedPaths.size < requestedPaths.size) {
            "Synchronized ${syncedPaths.size} of ${requestedPaths.size} requested path(s). Not found: ${(requestedPaths - syncedPaths.toSet()).joinToString(", ")}."
        } else {
            "Synchronized ${syncedPaths.size} path(s)."
        }

        return createJsonResult(SyncFilesResult(
            syncedPaths = syncedPaths,
            syncedAll = syncedAll,
            message = message
        ))
    }

    private fun resolveEffectiveRoot(project: Project, projectPathArg: String?): String? {
        if (projectPathArg == null) return project.basePath
        val normalized = projectPathArg.trimEnd('/', '\\')
        if (normalized == project.basePath) return project.basePath
        return ProjectUtils.getModuleContentRoots(project)
            .firstOrNull { it == normalized }
            ?: project.basePath
    }
}
