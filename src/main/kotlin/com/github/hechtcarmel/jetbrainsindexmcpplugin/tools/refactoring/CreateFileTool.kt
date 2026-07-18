package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ProjectUtils
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiDocumentManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

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

        Examples:
        - {"file": "src/main/java/com/example/NewService.java", "content": "package com.example;\n\npublic class NewService {\n}"}
        - {"file": "src/utils/helper.ts", "content": "export function helper(): string {\n    return 'help';\n}"}
    """.trimIndent()

    override val inputSchema: JsonObject = SchemaBuilder.tool()
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

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val filePath = arguments[ParamNames.FILE]?.jsonPrimitive?.content
            ?: return createErrorResult("Missing required parameter: file")
        val content = arguments["content"]?.jsonPrimitive?.content
            ?: return createErrorResult("Missing required parameter: content")

        if (filePath.isBlank()) {
            return createErrorResult("file must not be empty.")
        }

        val resolvedBase = resolveBasePath(project, arguments, filePath)
            ?: return createErrorResult("Project has no base path.")

        val targetFile = File(resolvedBase, filePath).canonicalFile
        val baseCanonical = File(resolvedBase).canonicalFile
        if (!targetFile.path.startsWith(baseCanonical.path + File.separator)) {
            return createErrorResult("File path escapes project root: $filePath")
        }
        if (targetFile.exists()) {
            return createErrorResult("File already exists: $filePath. Use ide_edit_member or ide_replace_member to modify existing files.")
        }

        var relativePath = filePath
        var savedDocument: com.intellij.openapi.editor.Document? = null

        suspendingWriteAction(project, "Create file: $filePath") {
            val parentDir = targetFile.parentFile
            val parentVf = VfsUtil.createDirectoryIfMissing(parentDir.absolutePath)
                ?: throw Exception("Cannot create directory: ${parentDir.absolutePath}")

            val newVf = parentVf.createChildData(this, targetFile.name)
            val document = FileDocumentManager.getInstance().getDocument(newVf)
            if (document != null) {
                document.setText(content)
                PsiDocumentManager.getInstance(project).commitDocument(document)
                savedDocument = document
            } else {
                newVf.setBinaryContent(content.toByteArray(Charsets.UTF_8))
            }

            relativePath = ProjectUtils.getToolFilePath(project, newVf)
        }

        if (savedDocument != null) {
            edtAction { FileDocumentManager.getInstance().saveDocument(savedDocument!!) }
        }

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
