package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools

import com.github.hechtcarmel.jetbrainsindexmcpplugin.exceptions.IndexNotReadyException
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ContentBlock
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Abstract base class for MCP tools providing common functionality.
 *
 * This class provides utility methods for:
 * - Dumb mode checking ([requireSmartMode])
 * - Thread-safe PSI access ([readAction], [writeAction])
 * - File and PSI element resolution ([resolveFile], [findPsiElement])
 * - Result creation ([createSuccessResult], [createErrorResult], [createJsonResult])
 *
 * ## Usage
 *
 * Extend this class to implement custom tools:
 *
 * ```kotlin
 * class MyTool : AbstractMcpTool() {
 *     override val name = "ide_my_tool"
 *     override val description = "My tool description"
 *     override val inputSchema = buildJsonObject { /* schema */ }
 *
 *     override suspend fun execute(project: Project, arguments: JsonObject): ToolCallResult {
 *         requireSmartMode(project)  // If index access is needed
 *         return readAction {
 *             // PSI operations here
 *             createJsonResult(MyResult(...))
 *         }
 *     }
 * }
 * ```
 *
 * @see McpTool
 */
abstract class AbstractMcpTool : McpTool {

    /**
     * JSON serializer configured for tool results.
     * - Ignores unknown keys for forward compatibility
     * - Encodes default values
     * - Compact output (no pretty printing)
     */
    protected val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    companion object {
        /**
         * Creates the `project_path` property definition for tool input schemas.
         *
         * All tools should include this property to support multi-project scenarios.
         * The property is optional - if omitted and only one project is open,
         * that project is used automatically.
         *
         * @return A pair of property name and JSON Schema definition
         */
        fun projectPathProperty(): Pair<String, JsonObject> {
            return "project_path" to buildJsonObject {
                put("type", "string")
                put("description", "Absolute path to the project root. Required when multiple projects are open, optional otherwise.")
            }
        }
    }

    /**
     * Throws [IndexNotReadyException] if the IDE is in dumb mode (indexing).
     *
     * Call this at the start of [execute] if your tool requires index access.
     * Tools that don't need the index (e.g., file operations) don't need to call this.
     *
     * @param project The project to check
     * @throws IndexNotReadyException if indexes are not available
     */
    protected fun requireSmartMode(project: Project) {
        if (DumbService.isDumb(project)) {
            throw IndexNotReadyException("IDE is in dumb mode, indexes not available")
        }
    }

    /**
     * Executes an action with a read lock on the PSI tree.
     *
     * Use this for any PSI read operations to ensure thread safety.
     *
     * @param action The action to execute
     * @return The result of the action
     */
    protected fun <T> readAction(action: () -> T): T {
        return ReadAction.compute<T, Throwable>(action)
    }

    /**
     * Executes an action with a write lock on the PSI tree.
     *
     * Use this for any PSI modification operations. The action will be:
     * - Executed on the EDT (Event Dispatch Thread)
     * - Wrapped in an undo-able command
     *
     * @param project The project context
     * @param commandName Name for the undo command (shown in Edit menu)
     * @param action The action to execute
     */
    protected fun writeAction(project: Project, commandName: String, action: () -> Unit) {
        WriteCommandAction.runWriteCommandAction(project, commandName, null, { action() })
    }

    /**
     * Resolves a file path to a [VirtualFile].
     * Uses refreshAndFindFileByPath to ensure externally created files are visible.
     *
     * @param project The project context
     * @param relativePath Path relative to project root, or absolute path
     * @return The VirtualFile, or null if not found
     */
    protected fun resolveFile(project: Project, relativePath: String): VirtualFile? {
        val basePath = project.basePath ?: return null
        val fullPath = if (relativePath.startsWith("/")) relativePath else "$basePath/$relativePath"
        // Use refreshAndFindFileByPath to handle externally created files
        return LocalFileSystem.getInstance().refreshAndFindFileByPath(fullPath)
    }

    /**
     * Gets the PSI file for a given path.
     *
     * @param project The project context
     * @param relativePath Path relative to project root
     * @return The PsiFile, or null if not found
     */
    protected fun getPsiFile(project: Project, relativePath: String): PsiFile? {
        val virtualFile = resolveFile(project, relativePath) ?: return null
        return PsiManager.getInstance(project).findFile(virtualFile)
    }

    /**
     * Finds the PSI element at a specific position in a file.
     *
     * @param project The project context
     * @param file Path to the file relative to project root
     * @param line 1-based line number
     * @param column 1-based column number
     * @return The PSI element at the position, or null if not found
     */
    protected fun findPsiElement(
        project: Project,
        file: String,
        line: Int,
        column: Int
    ): PsiElement? {
        val psiFile = getPsiFile(project, file) ?: return null
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return null

        val offset = getOffset(document, line, column) ?: return null
        return psiFile.findElementAt(offset)
    }

    /**
     * Converts 1-based line/column to document offset.
     *
     * @param document The document
     * @param line 1-based line number
     * @param column 1-based column number
     * @return The character offset, or null if position is invalid
     */
    protected fun getOffset(document: Document, line: Int, column: Int): Int? {
        val lineIndex = line - 1
        if (lineIndex < 0 || lineIndex >= document.lineCount) return null

        val lineStartOffset = document.getLineStartOffset(lineIndex)
        val lineEndOffset = document.getLineEndOffset(lineIndex)
        val columnOffset = column - 1

        val offset = lineStartOffset + columnOffset
        return if (offset <= lineEndOffset) offset else lineEndOffset
    }

    /**
     * Gets the text content of a specific line.
     *
     * @param document The document
     * @param line 1-based line number
     * @return The line text, or empty string if line is invalid
     */
    protected fun getLineText(document: Document, line: Int): String {
        val lineIndex = line - 1
        if (lineIndex < 0 || lineIndex >= document.lineCount) return ""

        val startOffset = document.getLineStartOffset(lineIndex)
        val endOffset = document.getLineEndOffset(lineIndex)
        return document.getText(TextRange(startOffset, endOffset))
    }

    /**
     * Converts an absolute file path to a project-relative path.
     *
     * @param project The project context
     * @param virtualFile The file
     * @return The relative path, or absolute path if not under project root
     */
    protected fun getRelativePath(project: Project, virtualFile: VirtualFile): String {
        val basePath = project.basePath ?: return virtualFile.path
        return virtualFile.path.removePrefix(basePath).removePrefix("/")
    }

    /**
     * Creates a successful result with a text message.
     *
     * @param text The success message
     * @return A [ToolCallResult] with `isError = false`
     */
    protected fun createSuccessResult(text: String): ToolCallResult {
        return ToolCallResult(
            content = listOf(ContentBlock.Text(text = text)),
            isError = false
        )
    }

    /**
     * Creates an error result with a message.
     *
     * @param message The error message
     * @return A [ToolCallResult] with `isError = true`
     */
    protected fun createErrorResult(message: String): ToolCallResult {
        return ToolCallResult(
            content = listOf(ContentBlock.Text(text = message)),
            isError = true
        )
    }

    /**
     * Creates a successful result with JSON-serialized data.
     *
     * @param data The data to serialize (must be @Serializable)
     * @return A [ToolCallResult] with JSON content and `isError = false`
     */
    protected inline fun <reified T> createJsonResult(data: T): ToolCallResult {
        val jsonText = json.encodeToString(data)
        return ToolCallResult(
            content = listOf(ContentBlock.Text(text = jsonText)),
            isError = false
        )
    }
}
