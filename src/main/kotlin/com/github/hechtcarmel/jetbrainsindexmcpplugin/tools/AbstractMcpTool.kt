package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ErrorMessages
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.toArgumentFailure
import com.github.hechtcarmel.jetbrainsindexmcpplugin.exceptions.IndexNotReadyException
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.LanguageHandlerRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.PaginationService
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.ProjectResolver
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ContentBlock
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.settings.McpSettings
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ClassResolver
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ProjectUtils
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PsiUtils
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ResponseFormatter
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.TransactionGuard
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.application.readAction as platformReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import java.io.File
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.util.concurrency.annotations.RequiresReadLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

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
     * Executes an action on the EDT, reusing the current thread if already on EDT.
     *
     * This avoids deadlocks when called from `runBlocking` on EDT in tests
     * or other scenarios where the EDT is already the current thread.
     */
    protected suspend fun <T> edtAction(action: () -> T): T {
        return if (ApplicationManager.getApplication().isDispatchThread) {
            action()
        } else {
            withContext(Dispatchers.EDT + ModalityState.nonModal().asContextElement()) { action() }
        }
    }

    /**
     * Commits all documents in a write-safe context.
     *
     * [PsiDocumentManager.commitAllDocuments] requires a write-safe EDT context
     * (enforced by [TransactionGuard]). Since MCP tools are invoked from HTTP handlers
     * (not user actions), there is no inherent write-safe context.
     * [TransactionGuard.submitTransactionAndWait] explicitly creates one.
     *
     * From EDT (e.g. inside [withContext]([Dispatchers.EDT])), falls back to
     * [WriteCommandAction] which also provides write-safety.
     */
    @Suppress("DEPRECATION")
    protected suspend fun commitDocuments(project: Project) {
        if (ApplicationManager.getApplication().isDispatchThread) {
            WriteCommandAction.runWriteCommandAction(project) {
                PsiDocumentManager.getInstance(project).commitAllDocuments()
            }
        } else {
            TransactionGuard.getInstance().submitTransactionAndWait {
                PsiDocumentManager.getInstance(project).commitAllDocuments()
            }
        }
    }

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
        // 1. Force VFS to see external changes before PSI work proceeds.
        // Refresh all content roots (includes workspace sub-project directories)
        val dirsToRefresh = mutableListOf<VirtualFile>()
        val projectDir = project.basePath?.let { LocalFileSystem.getInstance().findFileByPath(it) }
        if (projectDir != null) {
            dirsToRefresh.add(projectDir)
        }
        for (rootPath in ProjectUtils.getModuleContentRoots(project)) {
            if (rootPath != project.basePath) {
                LocalFileSystem.getInstance().findFileByPath(rootPath)?.let { dirsToRefresh.add(it) }
            }
        }
        if (dirsToRefresh.isNotEmpty()) {
            VfsUtil.markDirtyAndRefresh(false, true, true, *dirsToRefresh.toTypedArray())
        }

        // 2. Commit Documents in a write-safe context
        commitDocuments(project)
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
     * - Doesn't block the calling thread
     * - Automatically cancels and retries when a write action is requested
     * - Integrates with coroutine cancellation
     *
     * Use this instead of [readAction] for long-running PSI operations to avoid
     * blocking write actions and causing UI freezes.
     *
     * @param action The action to execute
     * @return The result of the action
     */
    protected suspend fun <T> suspendingReadAction(action: () -> T): T {
        return platformReadAction { action() }
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
        edtAction {
            WriteCommandAction.runWriteCommandAction(project, commandName, null, { action() })
        }
    }

    /**
     * Resolves a file path to a [VirtualFile].
     * Uses refreshAndFindFileByPath to ensure externally created files are visible.
     * Supports workspace projects by trying module content roots when basePath resolution fails.
     *
     * @param project The project context
     * @param relativePath Path relative to project root, or absolute path
     * @return The VirtualFile, or null if not found
     */
    protected fun resolveFile(project: Project, relativePath: String): VirtualFile? {
        // Absolute paths are validated against project roots before resolving
        if (relativePath.startsWith("/") || relativePath.startsWith("\\")) {
            val canonical = File(relativePath).canonicalPath
            val projectRoots = listOfNotNull(project.basePath) + ProjectUtils.getModuleContentRoots(project)
            val withinProject = projectRoots.any { root ->
                canonical.startsWith(File(root).canonicalPath + File.separator)
            }
            if (!withinProject) return null
            return LocalFileSystem.getInstance().refreshAndFindFileByPath(canonical)
        }

        // Try project basePath first
        val basePath = project.basePath
        if (basePath != null) {
            val canonical = File(basePath, relativePath).canonicalPath
            if (canonical.startsWith(File(basePath).canonicalPath + File.separator)) {
                val file = LocalFileSystem.getInstance().refreshAndFindFileByPath(canonical)
                if (file != null) return file
            }
        }

        // Try module content roots (workspace sub-project support)
        for (rootPath in ProjectUtils.getModuleContentRoots(project)) {
            if (rootPath != basePath) {
                val canonical = File(rootPath, relativePath).canonicalPath
                if (canonical.startsWith(File(rootPath).canonicalPath + File.separator)) {
                    val file = LocalFileSystem.getInstance().refreshAndFindFileByPath(canonical)
                    if (file != null) return file
                }
            }
        }

        return null
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
     * Gets the PSI file for a navigation target path.
     *
     * Unlike [getPsiFile], this may resolve files from project dependencies/libraries
     * in addition to project content roots. It is intended only for read-only
     * navigation flows that need to round-trip tool output back into PSI.
     */
    protected fun getNavigablePsiFile(project: Project, path: String): PsiFile? {
        val virtualFile = PsiUtils.resolveNavigableVirtualFile(project, path) ?: return null
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
     * Finds a PSI element in a read-only navigation target.
     *
     * Supports the same project/content-root files as [findPsiElement] plus library
     * files that are part of the current project's dependency graph.
     */
    protected fun findNavigablePsiElement(
        project: Project,
        file: String,
        line: Int,
        column: Int
    ): PsiElement? {
        val psiFile = getNavigablePsiFile(project, file) ?: return null
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return null

        val offset = getOffset(document, line, column) ?: return null
        return psiFile.findElementAt(offset)
    }

    /**
     * Resolves a PSI element from arguments using either `language`+`symbol` or `file`+`line`+`column`.
     *
     * These two parameter groups are mutually exclusive.
     *
     * The symbol path always returns a [com.intellij.psi.PsiNamedElement] (the declaration);
     * the position path returns a leaf token that callers must resolve further.
     *
     * @param project The project context
     * @param arguments The tool arguments
     * @return [Result.success] with the resolved element, or [Result.failure] with an [IllegalArgumentException]
     */
    @RequiresReadLock
    protected fun resolveElementFromArguments(
        project: Project,
        arguments: JsonObject,
        allowLibraryFilesForPosition: Boolean = false
    ): Result<PsiElement> {
        val language = arguments[ParamNames.LANGUAGE]?.jsonPrimitive?.content
        val symbol = arguments[ParamNames.SYMBOL]?.jsonPrimitive?.content
        val file = arguments[ParamNames.FILE]?.jsonPrimitive?.content
        val line = arguments[ParamNames.LINE]?.jsonPrimitive?.int
        val column = arguments[ParamNames.COLUMN]?.jsonPrimitive?.int

        val hasSymbol = language != null || symbol != null
        val hasPosition = file != null || line != null || column != null

        if (hasSymbol && hasPosition) {
            return ErrorMessages.SYMBOL_AND_POSITION_EXCLUSIVE.toArgumentFailure()
        }

        if (hasSymbol) {
            if (language == null) return ErrorMessages.missingParamForSymbol(ParamNames.LANGUAGE).toArgumentFailure()
            if (symbol == null) return ErrorMessages.missingParamForSymbol(ParamNames.SYMBOL).toArgumentFailure()

            val handler = LanguageHandlerRegistry.getSymbolReferenceHandlerByLanguageName(language)
                ?: return ErrorMessages.noSymbolReferenceHandler(
                    language, LanguageHandlerRegistry.getSupportedLanguageNamesForSymbolReference()
                ).toArgumentFailure()

            return handler.resolveSymbol(project, symbol)
        }

        if (hasPosition) {
            if (file == null) return ErrorMessages.missingParamForPosition(ParamNames.FILE, "line or column").toArgumentFailure()
            if (line == null) return ErrorMessages.missingParamForPosition(ParamNames.LINE, "file or column").toArgumentFailure()
            if (column == null) return ErrorMessages.missingParamForPosition(ParamNames.COLUMN, "file or line").toArgumentFailure()

            val element = if (allowLibraryFilesForPosition) {
                findNavigablePsiElement(project, file, line, column)
            } else {
                findPsiElement(project, file, line, column)
            }
                ?: return ErrorMessages.noElementAtPosition(file, line, column).toArgumentFailure()

            return Result.success(element)
        }

        return ErrorMessages.SYMBOL_OR_POSITION_REQUIRED.toArgumentFailure()
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
     * Supports workspace projects by checking module content roots.
     *
     * @param project The project context
     * @param virtualFile The file
     * @return The relative path, or absolute path if not under project root or any content root
     */
    protected fun getRelativePath(project: Project, virtualFile: VirtualFile): String {
        return ProjectUtils.getRelativePath(project, virtualFile)
    }

    /**
     * Finds a class by its fully qualified name.
     *
     * Delegates to [ClassResolver] which supports multiple languages:
     * - **PHP**: Uses `PhpIndex.getClassesByFQN()` and `getInterfacesByFQN()`
     * - **Java/Kotlin**: Uses `JavaPsiFacade.findClass()`
     *
     * @param project The project context
     * @param qualifiedName Fully qualified class name (e.g., "com.example.MyClass" or "\App\Models\User")
     * @return The PsiClass/PhpClass, or null if not found or no suitable plugin is available
     */
    protected fun findClassByName(project: Project, qualifiedName: String): PsiElement? {
        return ClassResolver.findClassByName(project, qualifiedName)
    }

    /**
     * Gets a page from the pagination cache.
     * Extracts project basePath and PSI mod count, delegates to PaginationService.
     * Returns GetPageResult — caller maps Success/Error into tool-specific ToolCallResult.
     *
     * @param pageSize Explicit pageSize from request, or null to use the cursor-embedded value.
     */
    protected suspend fun getPageFromCache(cursorToken: String, pageSize: Int?, project: Project): PaginationService.GetPageResult {
        val service = ApplicationManager.getApplication().getService(PaginationService::class.java)
        val basePath = ProjectResolver.normalizePath(project.basePath ?: "")
        val modCount = PsiModificationTracker.getInstance(project).modificationCount
        return service.getPage(cursorToken, pageSize, basePath, modCount)
    }

    /**
     * Builds a paginated tool result from a GetPageResult.
     * Handles error mapping and JSON deserialization of page items.
     * @param T The item type to deserialize from JSON
     * @param R The result model type to serialize (must be @Serializable)
     * @param result The pagination result from getPageFromCache
     * @param builder Constructs the tool-specific result model from deserialized items and page metadata
     */
    protected inline fun <reified T, reified R> buildPaginatedResult(
        result: PaginationService.GetPageResult,
        builder: (items: List<T>, page: PaginationService.PaginationPage) -> R
    ): ToolCallResult {
        return when (result) {
            is PaginationService.GetPageResult.Error -> createErrorResult(result.message)
            is PaginationService.GetPageResult.Success -> {
                val items = result.page.items.map { json.decodeFromJsonElement<T>(it) }
                createJsonResult(builder(items, result.page))
            }
        }
    }

    /**
     * Resolves pageSize from arguments, checking pageSize first, then legacy aliases.
     * Result is clamped to [1, maxPageSize].
     */
    protected fun resolvePageSize(
        arguments: JsonObject,
        defaultPageSize: Int,
        maxPageSize: Int = PaginationService.MAX_PAGE_SIZE,
        vararg aliases: String
    ): Int {
        val raw = arguments["pageSize"]?.jsonPrimitive?.int
            ?: aliases.firstNotNullOfOrNull { arguments[it]?.jsonPrimitive?.int }
            ?: defaultPageSize
        return raw.coerceIn(1, maxPageSize)
    }

    /**
     * Returns the explicitly provided pageSize from arguments, or null if not specified.
     * Used in cursor paths so the cursor-embedded pageSize is used as fallback.
     */
    protected fun resolveExplicitPageSize(
        arguments: JsonObject,
        maxPageSize: Int = PaginationService.MAX_PAGE_SIZE,
        vararg aliases: String
    ): Int? {
        val raw = arguments["pageSize"]?.jsonPrimitive?.int
            ?: aliases.firstNotNullOfOrNull { arguments[it]?.jsonPrimitive?.int }
            ?: return null
        return raw.coerceIn(1, maxPageSize)
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
     * Creates an error result with a structured payload.
     *
     * The payload is emitted using the configured response format.
     * If formatting fails, returns a plain-text formatting error instead.
     */
    protected fun createStructuredErrorResult(data: JsonElement): ToolCallResult {
        return try {
            val jsonText = json.encodeToString(JsonElement.serializer(), data)
            ToolCallResult(
                content = listOf(ContentBlock.Text(text = formatStructuredPayload(jsonText))),
                isError = true
            )
        } catch (e: Exception) {
            createErrorResult(formattingFailureMessage(e))
        }
    }

    /**
     * Creates a successful result with JSON-serialized data.
     *
     * @param data The data to serialize (must be @Serializable)
     * @return A [ToolCallResult] with JSON content and `isError = false`
     */
    protected inline fun <reified T> createJsonResult(data: T): ToolCallResult {
        return try {
            val jsonText = json.encodeToString(data)
            ToolCallResult(
                content = listOf(ContentBlock.Text(text = formatStructuredPayload(jsonText))),
                isError = false
            )
        } catch (e: Exception) {
            createErrorResult(formattingFailureMessage(e))
        }
    }

    @PublishedApi
    internal fun formatStructuredPayload(jsonText: String): String {
        val format = McpSettings.getInstance().responseFormat
        return ResponseFormatter.formatStructuredPayload(jsonText, format)
    }

    @PublishedApi
    internal fun formattingFailureMessage(error: Exception): String {
        val message = error.message?.takeIf { it.isNotBlank() } ?: "unknown error"
        return "Response formatting failed: $message"
    }
}
