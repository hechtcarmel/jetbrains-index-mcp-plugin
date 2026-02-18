package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.SyncFilesResult
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiDocumentManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class SyncFilesTool : AbstractMcpTool() {

    override val requiresPsiSync: Boolean = false

    override val name = ToolNames.SYNC_FILES

    override val description = """
        Force the IDE to synchronize its virtual file system and PSI cache with external file changes. Use when files were created, modified, or deleted outside the IDE (e.g., by coding agents) and other IDE tools report stale results or miss references in recently changed files.
        call it on-demand only when needed.
        Parameters: paths (optional array of relative file/directory paths to sync; if omitted, syncs entire project), project_path (optional).
        Example: {} or {"paths": ["src/main/java/com/example/NewFile.java", "src/main/java/com/example/ModifiedFile.java"]}
    """.trimIndent()

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("project_path") {
                put("type", "string")
                put("description", "Absolute path to the project root. Required when multiple projects are open.")
            }
            putJsonObject("paths") {
                put("type", "array")
                putJsonObject("items") {
                    put("type", "string")
                }
                put("description", "File or directory paths relative to project root to sync. If omitted, syncs the entire project.")
            }
        }
        put("required", buildJsonArray { })
    }

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val basePath = project.basePath
            ?: return createErrorResult("Project base path is not available.")

        val requestedPaths = arguments["paths"]?.jsonArray?.map { it.jsonPrimitive.content }

        val syncedPaths: List<String>
        val syncedAll: Boolean

        if (requestedPaths != null && requestedPaths.isNotEmpty()) {
            val resolvedFiles = requestedPaths.mapNotNull { relativePath ->
                val fullPath = if (relativePath.startsWith("/")) relativePath else "$basePath/$relativePath"
                LocalFileSystem.getInstance().refreshAndFindFileByPath(fullPath)?.let { relativePath to it }
            }
            if (resolvedFiles.isNotEmpty()) {
                VfsUtil.markDirtyAndRefresh(false, true, true, *resolvedFiles.map { it.second }.toTypedArray())
            }
            syncedPaths = resolvedFiles.map { it.first }
            syncedAll = false
        } else {
            val projectDir = LocalFileSystem.getInstance().findFileByPath(basePath)
            if (projectDir != null) {
                VfsUtil.markDirtyAndRefresh(false, true, true, projectDir)
            }
            syncedPaths = listOf(basePath)
            syncedAll = true
        }

        withContext(Dispatchers.EDT) {
            PsiDocumentManager.getInstance(project).commitAllDocuments()
        }

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
}
