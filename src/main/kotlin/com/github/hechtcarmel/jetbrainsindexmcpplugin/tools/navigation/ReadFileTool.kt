package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.SchemaConstants
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.ReadFileResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ProjectUtils
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PsiUtils
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

@Suppress("unused")
class ReadFileTool : AbstractMcpTool() {

    override val name = ToolNames.READ_FILE

    override val description = """
    File lookup: by file path (relative, absolute, jar path with !/or jar://) or qualifiedName (e.g., java.util.ArrayList).
    Best for library/dependency Java sources (jars/external libs). For project files, prefer native tools; enable only when needed.

    Returns: file content (full or line range) with metadata (language, lineCount, start/end, isLibraryFile).

    Parameters: file or qualifiedName (one required), startLine (optional), endLine (optional).

    Examples: {"file": "src/main/java/MyClass.java"} or {"qualifiedName": "java.util.ArrayList"} or {"file": "MyClass.java", "startLine": 10, "endLine": 20}
""".trimIndent()

    override val inputSchema: JsonObject = buildJsonObject {
        put(SchemaConstants.TYPE, SchemaConstants.TYPE_OBJECT)
        putJsonObject(SchemaConstants.PROPERTIES) {
            putJsonObject(ParamNames.PROJECT_PATH) {
                put(SchemaConstants.TYPE, SchemaConstants.TYPE_STRING)
                put(SchemaConstants.DESCRIPTION, SchemaConstants.DESC_PROJECT_PATH)
            }
            putJsonObject(ParamNames.FILE) {
                put(SchemaConstants.TYPE, SchemaConstants.TYPE_STRING)
                put(SchemaConstants.DESCRIPTION, "File path (relative, absolute, jar path with jar!/, or jar:// URL).")
            }
            putJsonObject(ParamNames.QUALIFIED_NAME) {
                put(SchemaConstants.TYPE, SchemaConstants.TYPE_STRING)
                put(SchemaConstants.DESCRIPTION, "Fully qualified class name (e.g., java.util.ArrayList).")
            }
            putJsonObject(ParamNames.START_LINE) {
                put(SchemaConstants.TYPE, SchemaConstants.TYPE_INTEGER)
                put(SchemaConstants.DESCRIPTION, "Starting line number (1-based, inclusive).")
            }
            putJsonObject(ParamNames.END_LINE) {
                put(SchemaConstants.TYPE, SchemaConstants.TYPE_INTEGER)
                put(SchemaConstants.DESCRIPTION, "Ending line number (1-based, inclusive).")
            }
        }
        putJsonArray(SchemaConstants.REQUIRED) {
            // Either file or qualifiedName is required (validated in code)
        }
    }

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val filePath = arguments[ParamNames.FILE]?.jsonPrimitive?.content
        val qualifiedName = arguments[ParamNames.QUALIFIED_NAME]?.jsonPrimitive?.content
        val startLineInput = arguments[ParamNames.START_LINE]?.jsonPrimitive?.int
        val endLineInput = arguments[ParamNames.END_LINE]?.jsonPrimitive?.int

        if (filePath.isNullOrBlank() && qualifiedName.isNullOrBlank()) {
            return createErrorResult("Either '${ParamNames.FILE}' or '${ParamNames.QUALIFIED_NAME}' is required")
        }

        var startLine = startLineInput
        var endLine = endLineInput

        if (startLine == null && endLine != null) {
            return createErrorResult("startLine is required when endLine is provided")
        }

        if (startLine != null && endLine == null) {
            endLine = startLine
        }

        if (startLine != null && endLine != null) {
            if (startLine < 1 || endLine < 1) {
                return createErrorResult("startLine and endLine must be >= 1")
            }
            if (endLine < startLine) {
                return createErrorResult("endLine must be >= startLine")
            }
        }

        return suspendingReadAction {
            val virtualFile = when {
                !qualifiedName.isNullOrBlank() -> {
                    requireSmartMode(project)
                    val element = findClassByName(project, qualifiedName)
                        ?: return@suspendingReadAction createErrorResult("Class not found: $qualifiedName")
                    element.containingFile?.virtualFile
                }
                !filePath.isNullOrBlank() -> PsiUtils.resolveVirtualFileAnywhere(project, filePath)
                else -> null
            } ?: return@suspendingReadAction createErrorResult("File not found: ${filePath ?: qualifiedName}")

            val content = if (startLine != null && endLine != null) {
                PsiUtils.getFileContentByLines(project, virtualFile, startLine, endLine)
            } else {
                PsiUtils.getFileContent(project, virtualFile)
            } ?: return@suspendingReadAction createErrorResult("Unable to read file contents")

            val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
            val document = psiFile?.let { PsiDocumentManager.getInstance(project).getDocument(it) }
            val fullText = if (document == null) PsiUtils.getFileContent(project, virtualFile) else null
            val lineCount = document?.lineCount ?: fullText?.split("\n")?.size ?: content.split("\n").size
            val language = psiFile?.language?.id

            val resolvedPath = when {
                ProjectUtils.isProjectFile(project, virtualFile) -> ProjectUtils.getRelativePath(project, virtualFile)
                virtualFile.fileSystem.protocol == "jar" -> virtualFile.url
                else -> virtualFile.path
            }

            val isLibraryFile = !ProjectUtils.isProjectFile(project, virtualFile) ||
                virtualFile.fileSystem.protocol == "jar"

            createJsonResult(ReadFileResult(
                file = resolvedPath,
                content = content,
                language = language,
                lineCount = lineCount,
                startLine = startLine,
                endLine = endLine,
                isLibraryFile = isLibraryFile
            ))
        }
    }
}
