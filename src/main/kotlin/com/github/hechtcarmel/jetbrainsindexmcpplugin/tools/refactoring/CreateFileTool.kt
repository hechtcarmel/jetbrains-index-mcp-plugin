package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ProjectUtils
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.IOException
import java.nio.file.Files

class CreateFileTool : AbstractMcpTool() {

    override val requiresPsiSync = false

    override val name = ToolNames.CREATE_FILE

    override val description = """
        Create a new source file with content, immediately indexed by IntelliJ.

        The file is created through IntelliJ's VFS, so it is instantly available for
        ide_find_references, ide_refactor_rename, ide_edit_member, and all other IDE tools
        without needing ide_sync_files.

        Use this instead of the Write tool for creating .java, .kt, .ts, .tsx, .py files.
        The file must not already exist.

        The file is NOT registered with version control: no "Add File to Git" prompt appears
        and nothing is staged. Run git add yourself when you want it tracked.

        Examples:
        - {"file": "src/main/java/com/example/NewService.java", "content": "package com.example;\n\npublic class NewService {\n}"}
        - {"file": "src/utils/helper.ts", "content": "export function helper(): string {\n    return 'help';\n}"}
    """.trimIndent()

    override val inputSchema: ToolSchema = SchemaBuilder.tool()
        .projectPath()
        .file(description = "Path to the new file relative to project root. REQUIRED. File must not already exist.")
        .stringProperty("content", "The file content to write.", required = true)
        .build()

    @Serializable
    data class CreateFileResult(
        val success: Boolean,
        val file: String,
        val message: String
    )

    override suspend fun doExecute(project: Project, arguments: JsonObject): CallToolResult {
        val filePath = arguments[ParamNames.FILE]?.jsonPrimitive?.content
            ?: return createErrorResult("Missing required parameter: file")
        val content = arguments["content"]?.jsonPrimitive?.content
            ?: return createErrorResult("Missing required parameter: content")

        if (filePath.isBlank()) {
            return createErrorResult("file must not be empty.")
        }

        val projectPathArg = arguments[ParamNames.PROJECT_PATH]?.jsonPrimitive?.content
        val resolvedBase = resolveBasePath(project, arguments, filePath)
            ?: return createErrorResult(
                if (projectPathArg != null)
                    "project_path '$projectPathArg' is not inside any known project root or content root. " +
                    "Register it with ide_import_modules first, or use ide_open_project to open it as its own project."
                else
                    "Project has no base path."
            )

        val targetFile = File(resolvedBase, filePath).canonicalFile
        val baseCanonical = File(resolvedBase).canonicalFile
        if (!targetFile.path.startsWith(baseCanonical.path + File.separator)) {
            return createErrorResult("File path escapes project root: $filePath")
        }
        if (targetFile.exists()) {
            return createErrorResult("File already exists: $filePath. Use ide_edit_member or ide_replace_member to modify existing files.")
        }

        // Write via NIO, then import into the VFS with a synchronous refresh. The IDE's VCS
        // listener ignores refresh-originated create events, while a direct createChildData()
        // is IDE-originated and goes through the "When files are created" confirmation — an
        // app-modal "Add File to Git" dialog when set to Ask (which freezes the EDT and hangs
        // every queued MCP call), or a silent git stage when set to Add silently. Refresh-based
        // creation avoids both while the file is still indexed in the same call.
        try {
            Files.createDirectories(targetFile.parentFile.toPath())
            Files.write(targetFile.toPath(), content.toByteArray(Charsets.UTF_8))
        } catch (e: IOException) {
            return createErrorResult("Cannot create file '$filePath': ${e.message}")
        }

        val newVf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(targetFile.toPath())
            ?: return createErrorResult(
                "File was written to disk but could not be loaded into the IDE's virtual file system: $filePath"
            )

        val relativePath = suspendingReadAction { ProjectUtils.getToolFilePath(project, newVf) }

        return createJsonResult(
            CreateFileResult(
                success = true,
                file = relativePath,
                message = "Created file '$relativePath' (immediately indexed)"
            )
        )
    }

    private fun resolveBasePath(project: Project, arguments: JsonObject, filePath: String): String? {
        val projectPath = arguments[ParamNames.PROJECT_PATH]?.jsonPrimitive?.content
        if (projectPath != null) {
            val contentRoots = ProjectUtils.getModuleContentRoots(project)
            val canonical = ProjectUtils.canonicalNormalizedPath(projectPath)
            val match = contentRoots.firstOrNull { ProjectUtils.canonicalNormalizedPath(it) == canonical }
            if (match != null) return match

            val knownRoots = contentRoots + listOfNotNull(project.basePath)
            val insideProject = knownRoots.any { root ->
                val r = ProjectUtils.canonicalNormalizedPath(root)
                canonical == r || canonical.startsWith("$r/")
            }
            if (insideProject && File(canonical).isDirectory) return canonical

            return null
        }

        val basePath = project.basePath ?: return null
        val contentRoots = ProjectUtils.getModuleContentRoots(project)
        val firstSegment = filePath.split("/", "\\").firstOrNull() ?: return basePath
        for (root in contentRoots) {
            if (root != basePath && File(root, firstSegment).exists()) {
                return root
            }
        }
        return basePath
    }
}
