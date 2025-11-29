package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools

import com.github.hechtcarmel.jetbrainsindexmcpplugin.exceptions.IndexNotReadyException
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ContentBlock
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.settings.McpSettings
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.JavaPluginDetector
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Abstract base class for MCP tools providing common functionality.
 *
 * This class provides:
 * - **PSI synchronization**: Automatically commits document changes before tool execution
 * - Dumb mode checking ([requireSmartMode])
 * - Thread-safe PSI access ([readAction], [writeAction])
 * - File and PSI element resolution ([resolveFile], [findPsiElement])
 * - Result creation ([createSuccessResult], [createErrorResult], [createJsonResult])
 *
 * ## Usage
 *
 * Extend this class and implement [doExecute]:
 *
 * ```kotlin
 * class MyTool : AbstractMcpTool() {
 *     override val name = "ide_my_tool"
 *     override val description = "My tool description"
 *     override val inputSchema = buildJsonObject { /* schema */ }
 *
 *     override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
 *         requireSmartMode(project)  // If index access is needed
 *         return readAction {
 *             // PSI operations here
 *             createJsonResult(MyResult(...))
 *         }
 *     }
 * }
 * ```
 *
 * ## PSI Synchronization
 *
 * By default, all tools automatically synchronize PSI with document changes before
 * execution. This ensures that recently created or modified files (e.g., by external
 * tools like Claude Code's write tool) are visible to PSI-based searches.
 *
 * This behavior is controlled by:
 * - **User setting**: "Sync external file changes" in Settings (enabled by default)
 * - **Per-tool opt-out**: Override [requiresPsiSync] to `false` for tools that don't use PSI
 *
 * ```kotlin
 * override val requiresPsiSync: Boolean = false
 * ```
 *
 * @see McpTool
 * @see doExecute
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
     * Whether this tool requires PSI synchronization before execution.
     *
     * When true (default) AND the user has "Sync external file changes" enabled,
     * [execute] will commit all document changes to PSI before calling [doExecute].
     * This ensures that recently created or modified files are visible to
     * PSI-based searches and operations.
     *
     * Override and return false for tools that:
     * - Only check status (e.g., index status)
     * - Don't interact with PSI indices or search APIs
     *
     * @see ensurePsiUpToDate
     * @see McpSettings.syncExternalChanges
     */
    protected open val requiresPsiSync: Boolean = true

    /**
     * Ensures all document changes are committed to PSI before proceeding.
     *
     * This is necessary because external tools (like Claude Code's write tool)
     * may create or modify files immediately before calling MCP tools.
     * Without this, PSI-based searches may miss recently created/modified content.
     *
     * Called automatically by [execute] when [requiresPsiSync] is true.
     *
     * Uses non-blocking coroutine approach to avoid EDT freezes.
     */
    private suspend fun ensurePsiUpToDate(project: Project) {
        // 1. Force VFS to see external changes (async refresh)
        val projectDir = project.basePath?.let { LocalFileSystem.getInstance().findFileByPath(it) }
        if (projectDir != null) {
            // Async refresh - doesn't block, the PSI commit below will pick up changes
            VfsUtil.markDirtyAndRefresh(true, true, true, projectDir)
        }

        // 2. Commit Documents using suspend function (non-blocking)
        withContext(Dispatchers.EDT) {
            PsiDocumentManager.getInstance(project).commitAllDocuments()
        }
    }

    /**
     * Template method that handles common setup before delegating to tool-specific logic.
     *
     * This method:
     * 1. Synchronizes PSI with documents (if enabled by settings and tool requires it)
     * 2. Delegates to [doExecute] for tool-specific implementation
     *
     * PSI synchronization runs when:
     * - The tool's [requiresPsiSync] is true (tool needs PSI), AND
     * - The user's "Sync external file changes" setting is enabled
     *
     * @param project The IntelliJ project context
     * @param arguments The tool arguments as a JSON object
     * @return A [ToolCallResult] containing the operation result or error
     */
    final override suspend fun execute(project: Project, arguments: JsonObject): ToolCallResult {
        val settings = McpSettings.getInstance()
        if (requiresPsiSync && settings.syncExternalChanges) {
            ensurePsiUpToDate(project)
        }
        return doExecute(project, arguments)
    }

    /**
     * Implement this method with the tool's specific execution logic.
     *
     * PSI synchronization is handled automatically by [execute] before this is called.
     *
     * @param project The IntelliJ project context
     * @param arguments The tool arguments as a JSON object matching [inputSchema]
     * @return A [ToolCallResult] containing the operation result or error
     */
    protected abstract suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult

    /**
     * Throws [IndexNotReadyException] if the IDE is in dumb mode (indexing).
     *
     * Call this at the start of [doExecute] if your tool requires index access.
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
     * Executes an action with a read lock on the PSI tree (blocking version).
     *
     * Use this for any PSI read operations to ensure thread safety.
     * For long-running operations, prefer [suspendingReadAction] which yields to write actions.
     *
     * @param action The action to execute
     * @return The result of the action
     */
    protected fun <T> readAction(action: () -> T): T {
        return ReadAction.compute<T, Throwable>(action)
    }

    /**
     * Executes an action with a read lock using suspend (non-blocking version).
     *
     * This is the preferred method for read operations as it:
     * - Yields to pending write actions (WARA - Write Allowing Read Action)
     * - Doesn't block the EDT
     * - Automatically cancels when a write action is requested
     *
     * @param action The action to execute
     * @return The result of the action
     */
    protected suspend fun <T> suspendingReadAction(action: () -> T): T {
        return readAction(action)
    }

    /**
     * Checks if the current operation has been cancelled.
     *
     * Call this frequently in long-running loops to allow cancellation
     * and prevent blocking write actions. Throws ProcessCanceledException
     * if cancellation is requested.
     */
    protected fun checkCanceled() {
        ProgressManager.checkCanceled()
    }

    /**
     * Executes an action with a write lock on the PSI tree (blocking version).
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
     * Executes a write action using suspend function (non-blocking for caller).
     *
     * This is the preferred method for write operations as it:
     * - Doesn't block the calling thread while waiting for EDT
     * - Still executes the action on EDT with proper locking
     * - Supports undo/redo grouping
     *
     * @param project The project context
     * @param commandName Name for the undo command (shown in Edit menu)
     * @param action The action to execute
     */
    protected suspend fun suspendingWriteAction(
        project: Project,
        commandName: String,
        action: () -> Unit
    ) {
        withContext(Dispatchers.EDT) {
            WriteCommandAction.runWriteCommandAction(project, commandName, null, { action() })
        }
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
     * Finds a Java/Kotlin class by its fully qualified name.
     *
     * **NOTE**: This method requires the Java plugin to be available.
     * Returns null immediately if the Java plugin is not installed.
     *
     * Uses multiple lookup strategies:
     * 1. JavaPsiFacade with project scope (fastest, index-based)
     * 2. JavaPsiFacade with all scope (includes libraries)
     * 3. Filename index fallback (when index lookup fails)
     *
     * @param project The project context
     * @param qualifiedName Fully qualified class name (e.g., "com.example.MyClass")
     * @return The PsiClass, or null if not found or Java plugin is not available
     * @see JavaPluginDetector.isJavaPluginAvailable
     */
    protected fun findClassByName(project: Project, qualifiedName: String): PsiElement? {
        // Early return if Java plugin is not available
        if (!JavaPluginDetector.isJavaPluginAvailable) {
            return null
        }

        return try {
            // Use reflection to avoid compile-time dependency on Java plugin classes
            // This allows the code to compile and run even when Java plugin is not available
            findClassByNameWithJavaPlugin(project, qualifiedName)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Internal implementation that uses Java plugin classes.
     * This method should only be called when Java plugin is available.
     */
    private fun findClassByNameWithJavaPlugin(project: Project, qualifiedName: String): PsiElement? {
        // These classes are only available when Java plugin is installed
        val javaPsiFacadeClass = Class.forName("com.intellij.psi.JavaPsiFacade")
        val globalSearchScopeClass = Class.forName("com.intellij.psi.search.GlobalSearchScope")
        val filenameIndexClass = Class.forName("com.intellij.psi.search.FilenameIndex")
        val psiJavaFileClass = Class.forName("com.intellij.psi.PsiJavaFile")

        // Get JavaPsiFacade instance
        val getInstanceMethod = javaPsiFacadeClass.getMethod("getInstance", Project::class.java)
        val javaPsiFacade = getInstanceMethod.invoke(null, project)

        // Get search scopes
        val projectScopeMethod = globalSearchScopeClass.getMethod("projectScope", Project::class.java)
        val allScopeMethod = globalSearchScopeClass.getMethod("allScope", Project::class.java)
        val everythingScopeMethod = globalSearchScopeClass.getMethod("everythingScope", Project::class.java)
        val fileScopeMethod = globalSearchScopeClass.getMethod("fileScope", PsiFile::class.java)

        val projectScope = projectScopeMethod.invoke(null, project)
        val allScope = allScopeMethod.invoke(null, project)

        // Try indexed lookup first (fastest)
        val findClassMethod = javaPsiFacadeClass.getMethod("findClass", String::class.java, globalSearchScopeClass)

        val classInProject = findClassMethod.invoke(javaPsiFacade, qualifiedName, projectScope)
        if (classInProject != null) return classInProject as PsiElement

        val classInAll = findClassMethod.invoke(javaPsiFacade, qualifiedName, allScope)
        if (classInAll != null) return classInAll as PsiElement

        // Fallback: search by filename if index lookup fails
        val simpleClassName = qualifiedName.substringAfterLast('.')
        val expectedPackage = qualifiedName.substringBeforeLast('.', "")

        // Try Java files
        val everythingScope = everythingScopeMethod.invoke(null, project)
        val getFilesByNameMethod = filenameIndexClass.getMethod(
            "getVirtualFilesByName",
            String::class.java,
            globalSearchScopeClass
        )
        val javaFiles = getFilesByNameMethod.invoke(null, "$simpleClassName.java", everythingScope) as Collection<*>

        val psiManager = PsiManager.getInstance(project)
        for (virtualFile in javaFiles) {
            val psiFile = psiManager.findFile(virtualFile as VirtualFile) ?: continue
            if (!psiJavaFileClass.isInstance(psiFile)) continue

            val getPackageNameMethod = psiJavaFileClass.getMethod("getPackageName")
            val getClassesMethod = psiJavaFileClass.getMethod("getClasses")

            val packageName = getPackageNameMethod.invoke(psiFile) as String
            if (packageName == expectedPackage) {
                val classes = getClassesMethod.invoke(psiFile) as Array<*>
                for (psiClass in classes) {
                    val psiElement = psiClass as PsiElement
                    val getNameMethod = psiElement.javaClass.getMethod("getName")
                    val className = getNameMethod.invoke(psiElement) as String?
                    if (className == simpleClassName) return psiElement
                }
            }
        }

        // Try Kotlin files
        val ktFiles = getFilesByNameMethod.invoke(null, "$simpleClassName.kt", everythingScope) as Collection<*>
        for (virtualFile in ktFiles) {
            val psiFile = psiManager.findFile(virtualFile as VirtualFile) ?: continue
            val fileScope = fileScopeMethod.invoke(null, psiFile)
            val ktClass = findClassMethod.invoke(javaPsiFacade, qualifiedName, fileScope)
            if (ktClass != null) return ktClass as PsiElement
        }

        return null
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
