package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.dotnet.RiderBackendSemanticService
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.MutationVerification
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.RefactoringResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import com.intellij.codeInsight.actions.AbstractLayoutCodeProcessor
import com.intellij.codeInsight.actions.OptimizeImportsProcessor
import com.intellij.codeInsight.actions.RearrangeCodeProcessor
import com.intellij.codeInsight.actions.ReformatCodeProcessor
import com.intellij.ide.IdeEventQueue
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import java.awt.Component
import java.awt.event.KeyEvent
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

/**
 * Reformats code in a file according to the project's code style settings.
 *
 * Equivalent to the IDE's "Reformat Code" action (Ctrl+Alt+L / Cmd+Opt+L).
 * Uses Rider's frontend action system for Rider/.NET files and the generic
 * IntelliJ [ReformatCodeProcessor] chain elsewhere.
 *
 * Respects .editorconfig, project code style, and language-specific formatting rules.
 *
 * Does NOT require smart mode -- formatting doesn't need indexes.
 */
class ReformatCodeTool : AbstractMcpTool() {

    companion object {
        private val LOG = logger<ReformatCodeTool>()
        private const val RIDER_REFORMAT_ACTION_ID_FALLBACK = "ReformatCode"
        private const val RIDER_REARRANGE_ACTION_ID_FALLBACK = "RearrangeCode"
        private const val RIDER_SHORTCUT_SETTLE_ATTEMPTS = 8
        private const val RIDER_SHORTCUT_SETTLE_DELAY_MS = 150L

        internal fun normalizeOptionalLineRange(startLine: Int?, endLine: Int?): NormalizedOptionalLineRange {
            if (startLine != null && startLine < 0) {
                return NormalizedOptionalLineRange(error = "startLine must be >= 1")
            }
            if (endLine != null && endLine < 0) {
                return NormalizedOptionalLineRange(error = "endLine must be >= 1")
            }

            val normalizedStartLine = startLine?.takeUnless { it == 0 }
            val normalizedEndLine = endLine?.takeUnless { it == 0 }

            if ((normalizedStartLine != null) != (normalizedEndLine != null)) {
                return NormalizedOptionalLineRange(error = "Both startLine and endLine must be provided together, or neither.")
            }
            if (normalizedStartLine != null && normalizedEndLine != null && normalizedEndLine < normalizedStartLine) {
                return NormalizedOptionalLineRange(error = "endLine must be >= startLine")
            }

            return NormalizedOptionalLineRange(
                startLine = normalizedStartLine,
                endLine = normalizedEndLine
            )
        }
    }

    internal data class NormalizedOptionalLineRange(
        val startLine: Int? = null,
        val endLine: Int? = null,
        val error: String? = null
    )

    private data class RiderReformatEditorLookup(
        val editor: Editor?,
        val virtualFile: VirtualFile,
        val openedByTool: Boolean,
        val reason: String
    )

    private data class RiderReformatInvocation(
        val lane: String,
        val actionsRun: List<String>
    )

    override val name = ToolNames.REFORMAT_CODE

    override val description = """
        Reformat code in a file according to the project's code style settings (.editorconfig, IDE code style). Equivalent to the IDE's "Reformat Code" action (Ctrl+Alt+L / Cmd+Opt+L). Supports undo (Ctrl+Z).

        By default optimizes imports, but does not rearrange code members unless explicitly requested. Use optimizeImports=false and rearrangeCode=false to only reformat whitespace/indentation.

        Respects: .editorconfig, project code style, language-specific formatting rules.

        Returns: success status, affected file, and description of operations performed.

        Parameters: file (required), startLine/endLine (optional inclusive range), optimizeImports (default: true), rearrangeCode (default: false).

        Example: {"file": "src/MyClass.java"}
        Example: {"file": "src/MyClass.java", "startLine": 10, "endLine": 50, "optimizeImports": false}
    """.trimIndent()

    override val inputSchema: JsonObject = SchemaBuilder.tool()
        .projectPath()
        .file()
        .intProperty(ParamNames.START_LINE, "Start line for partial formatting (1-based, inclusive). If provided, endLine is also required.")
        .intProperty(ParamNames.END_LINE, "End line for partial formatting (1-based, inclusive). If provided, startLine is also required.")
        .booleanProperty(ParamNames.OPTIMIZE_IMPORTS, "Optimize imports (remove unused, organize). Default: true.")
        .booleanProperty(ParamNames.REARRANGE_CODE, "Rearrange code members according to arrangement rules. Default: false.")
        .build()

    /**
     * Data class holding validated reformat parameters from Phase 1.
     */
    private data class ReformatValidation(
        val psiFile: PsiFile? = null,
        val textRange: TextRange? = null,
        val initialText: String? = null,
        val error: String? = null
    )

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val file = requiredStringArg(arguments, ParamNames.FILE).getOrElse {
            return createErrorResult(it.message ?: "Missing required parameter: file")
        }
        val normalizedLineRange = normalizeOptionalLineRange(
            startLine = arguments[ParamNames.START_LINE]?.jsonPrimitive?.int,
            endLine = arguments[ParamNames.END_LINE]?.jsonPrimitive?.int
        )
        if (normalizedLineRange.error != null) {
            return createErrorResult(normalizedLineRange.error)
        }
        val startLine = normalizedLineRange.startLine
        val endLine = normalizedLineRange.endLine
        val optimizeImports = arguments[ParamNames.OPTIMIZE_IMPORTS]?.jsonPrimitive?.boolean ?: true
        val rearrangeCode = arguments[ParamNames.REARRANGE_CODE]?.jsonPrimitive?.boolean ?: false

        // ═══════════════════════════════════════════════════════════════════════
        // PHASE 1: BACKGROUND - Resolve file and validate (suspending read action)
        // ═══════════════════════════════════════════════════════════════════════
        val virtualFile = resolveFile(project, file)
            ?: return createErrorResult("File not found: $file")

        val validation = suspendingReadAction {
            validateAndPrepare(project, virtualFile, file, startLine, endLine)
        }

        if (validation.error != null) {
            return createErrorResult(validation.error)
        }

        val psiFile = validation.psiFile
            ?: return createErrorResult("Failed to resolve file: $file")
        val textRange = validation.textRange
        val initialText = validation.initialText
            ?: return createErrorResult("Failed to snapshot file before reformat: $file")

        if (shouldUseRiderFrontendReformat(file) && textRange != null) {
            return if (optimizeImports || rearrangeCode) {
                createErrorResult(
                    "Reformat failed: Rider .NET startLine/endLine is not supported when optimizeImports or rearrangeCode is enabled because those actions are file-wide."
                )
            } else {
                createErrorResult(
                    "Reformat failed: Rider .NET startLine/endLine is not supported because the frontend reformat action cannot guarantee selection-scoped formatting."
                )
            }
        }

        val operationsRun = buildList {
            add("reformat")
            if (optimizeImports) add("optimize_imports")
            if (rearrangeCode) add("rearrange_code")
        }
        val skippedOperations = buildList {
            if (!optimizeImports) add("optimize_imports_skipped")
            if (!rearrangeCode) add("rearrange_code_skipped")
        }

        var openedByTool = false
        return try {
            val invocationLane = if (shouldUseRiderFrontendReformat(file)) {
                val editorLookup = resolveRiderReformatEditor(project, virtualFile)
                openedByTool = editorLookup.openedByTool
                val editor = editorLookup.editor ?: return createErrorResult(
                    "Reformat failed: Rider reformat requires a focused text editor for '$file' (${editorLookup.reason})"
                )
                val invocation = executeRiderFrontendReformat(
                    project = project,
                    file = file,
                    virtualFile = virtualFile,
                    editor = editor,
                    optimizeImports = optimizeImports,
                    rearrangeCode = rearrangeCode
                )
                commitDocuments(project)
                edtAction { FileDocumentManager.getInstance().saveAllDocuments() }
                awaitObservableRiderMutation(project, virtualFile, initialText)
                " using ${invocation.lane}"
            } else {
                edtAction {
                    executeReformat(project, psiFile, textRange, optimizeImports, rearrangeCode)
                }
                commitDocuments(project)
                edtAction { FileDocumentManager.getInstance().saveAllDocuments() }
                ""
            }

            val finalText = readCurrentFileText(project, virtualFile)
                ?: return createErrorResult("Reformat failed: file disappeared after invocation: $file")
            val changed = finalText != initialText
            createJsonResult(
                buildReformatResult(
                    file = file,
                    changed = changed,
                    startLine = startLine,
                    endLine = endLine,
                    operationsRun = operationsRun,
                    skippedOperations = skippedOperations,
                    laneSuffix = invocationLane
                )
            )
        } catch (e: Exception) {
            LOG.warn("Reformat failed for $file", e)
            createErrorResult("Reformat failed: ${e.message ?: "Unknown error during reformat"}")
        } finally {
            if (openedByTool) {
                edtAction {
                    FileEditorManager.getInstance(project).closeFile(virtualFile)
                }
            }
        }
    }

    private fun shouldUseRiderFrontendReformat(file: String): Boolean {
        return RiderBackendSemanticService.isRiderEnvironment() && RiderBackendSemanticService.isDotNetFile(file)
    }

    private suspend fun readCurrentFileText(project: Project, virtualFile: VirtualFile): String? {
        val document = edtAction { FileDocumentManager.getInstance().getDocument(virtualFile) }
        if (document != null) {
            return document.text
        }
        return suspendingReadAction { PsiManager.getInstance(project).findFile(virtualFile)?.text }
    }

    private suspend fun resolveRiderReformatEditor(
        project: Project,
        virtualFile: VirtualFile
    ): RiderReformatEditorLookup {
        return edtAction {
            val fileEditorManager = FileEditorManager.getInstance(project)
            val targetDocument = FileDocumentManager.getInstance().getDocument(virtualFile)
            fun selectedMatchingEditor(): Editor? {
                val document = targetDocument ?: return null
                return fileEditorManager.selectedTextEditor?.takeIf { selected ->
                    selected.document == document
                }
            }

            selectedMatchingEditor()?.let { selectedEditor ->
                return@edtAction RiderReformatEditorLookup(
                    editor = selectedEditor,
                    virtualFile = virtualFile,
                    openedByTool = false,
                    reason = "selected text editor already matches target file"
                )
            }

            val wasAlreadyOpen = fileEditorManager.isFileOpen(virtualFile)
            val editor = fileEditorManager.openTextEditor(OpenFileDescriptor(project, virtualFile), true)
                ?: fileEditorManager.getEditors(virtualFile)
                    .filterIsInstance<TextEditor>()
                    .firstOrNull { !it.editor.isDisposed }
                    ?.editor

            RiderReformatEditorLookup(
                editor = editor,
                virtualFile = virtualFile,
                openedByTool = editor != null && !wasAlreadyOpen,
                reason = if (editor != null) {
                    if (wasAlreadyOpen) "focused already-open file for Rider reformat" else "auto-opened file for Rider reformat"
                } else {
                    "no selected text editor matched target file after attempting to open it"
                }
            )
        }
    }

    private suspend fun executeRiderFrontendReformat(
        project: Project,
        file: String,
        virtualFile: VirtualFile,
        editor: Editor,
        optimizeImports: Boolean,
        rearrangeCode: Boolean
    ): RiderReformatInvocation {
        commitDocuments(project)
        edtAction { FileDocumentManager.getInstance().saveAllDocuments() }

        val dataContext = buildRiderReformatDataContext(project, virtualFile, editor)
        val actionsRun = mutableListOf<String>()
        val laneParts = mutableListOf<String>()

        val reformatActionId = invokeOptionalRiderAction(
            actionIds = resolveRiderReformatActionIds(),
            dataContext = dataContext
        )
        if (reformatActionId != null) {
            actionsRun += "reformat:$reformatActionId"
            laneParts += "Rider IDE action '$reformatActionId'"
        } else {
            edtAction {
                dispatchRiderReformatShortcut(editor.contentComponent)
            }
            actionsRun += "reformat:Ctrl+Alt+L"
            laneParts += "Rider Ctrl+Alt+L shortcut fallback"
        }

        if (optimizeImports) {
            val optimizeImportsActionId = invokeOptionalRiderAction(
                actionIds = resolveRiderOptimizeImportsActionIds(),
                dataContext = dataContext
            )
            if (optimizeImportsActionId != null) {
                actionsRun += "optimize_imports:$optimizeImportsActionId"
                laneParts += "optimize imports '$optimizeImportsActionId'"
            } else {
                edtAction {
                    dispatchRiderOptimizeImportsShortcut(editor.contentComponent)
                }
                actionsRun += "optimize_imports:Alt+Shift+O"
                laneParts += "optimize imports Alt+Shift+O shortcut fallback"
            }
        }

        if (rearrangeCode) {
            val rearrangeCodeActionId = invokeOptionalRiderAction(
                actionIds = resolveRiderRearrangeActionIds(),
                dataContext = dataContext
            ) ?: throw IllegalStateException(
                "Rider rearrange code action could not be resolved for '$file'"
            )
            actionsRun += "rearrange_code:$rearrangeCodeActionId"
            laneParts += "rearrange code '$rearrangeCodeActionId'"
        }

        return RiderReformatInvocation(
            lane = laneParts.joinToString(" + "),
            actionsRun = actionsRun
        )
    }

    private suspend fun buildRiderReformatDataContext(
        project: Project,
        virtualFile: VirtualFile,
        editor: Editor
    ): DataContext {
        val psiFile = suspendingReadAction { PsiManager.getInstance(project).findFile(virtualFile) }
            ?: throw IllegalStateException("Rider reformat could not resolve PSI for '${virtualFile.path}'")
        val descriptor = OpenFileDescriptor(project, virtualFile)

        val entries = linkedMapOf(
            CommonDataKeys.PROJECT.name to project,
            CommonDataKeys.EDITOR.name to editor,
            CommonDataKeys.PSI_FILE.name to psiFile,
            CommonDataKeys.PSI_ELEMENT.name to psiFile,
            PlatformCoreDataKeys.PSI_ELEMENT_ARRAY.name to arrayOf(psiFile),
            CommonDataKeys.VIRTUAL_FILE.name to virtualFile,
            CommonDataKeys.VIRTUAL_FILE_ARRAY.name to arrayOf(virtualFile),
            CommonDataKeys.NAVIGATABLE.name to descriptor,
            CommonDataKeys.NAVIGATABLE_ARRAY.name to arrayOf(descriptor),
            PlatformCoreDataKeys.CONTEXT_COMPONENT.name to editor.contentComponent
        )
        return DataContext { dataId -> entries[dataId] }
    }

    private fun resolveRiderReformatActionIds(): LinkedHashSet<String> {
        val actionIds = linkedSetOf<String>()
        resolveIdeActionsActionId("ACTION_EDITOR_REFORMAT")?.let(actionIds::add)
        resolveIdeActionsActionId("ACTION_REFORMAT_CODE")?.let(actionIds::add)
        actionIds.add(RIDER_REFORMAT_ACTION_ID_FALLBACK)
        return actionIds
    }

    private fun resolveRiderOptimizeImportsActionIds(): LinkedHashSet<String> {
        val actionIds = linkedSetOf<String>()
        resolveIdeActionsActionId("ACTION_OPTIMIZE_IMPORTS")?.let(actionIds::add)
        actionIds.add("OptimizeImports")
        return actionIds
    }

    private fun resolveRiderRearrangeActionIds(): LinkedHashSet<String> {
        val actionIds = linkedSetOf<String>()
        resolveIdeActionsActionId("ACTION_REARRANGE_CODE")?.let(actionIds::add)
        actionIds.add(RIDER_REARRANGE_ACTION_ID_FALLBACK)
        return actionIds
    }

    private fun resolveIdeActionsActionId(fieldName: String): String? {
        return runCatching {
            IdeActions::class.java.getField(fieldName).get(null) as? String
        }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    }

    private suspend fun awaitObservableRiderMutation(
        project: Project,
        virtualFile: VirtualFile,
        beforeText: String
    ) {
        for (attempt in 0 until RIDER_SHORTCUT_SETTLE_ATTEMPTS) {
            val currentText = readCurrentFileText(project, virtualFile)
            if (currentText != null && currentText != beforeText) {
                break
            }
            if (attempt < RIDER_SHORTCUT_SETTLE_ATTEMPTS - 1) {
                delay(RIDER_SHORTCUT_SETTLE_DELAY_MS)
                commitDocuments(project)
                edtAction { FileDocumentManager.getInstance().saveAllDocuments() }
            }
        }
    }

    private suspend fun invokeOptionalRiderAction(
        actionIds: LinkedHashSet<String>,
        dataContext: DataContext
    ): String? {
        return edtAction {
            val actionManager = ActionManager.getInstance()
            for (actionId in actionIds) {
                val action = actionManager.getAction(actionId) ?: continue
                invokeRiderAction(action, dataContext)
                return@edtAction actionId
            }
            null
        }
    }

    private fun invokeRiderAction(action: AnAction, dataContext: DataContext) {
        val presentation = action.templatePresentation.clone()
        val event = AnActionEvent.createEvent(
            action,
            dataContext,
            presentation,
            ActionPlaces.UNKNOWN,
            ActionUiKind.NONE,
            null
        )
        ActionUtil.invokeAction(action, event, null)
    }

    private fun dispatchRiderReformatShortcut(component: Component) {
        if (!component.isShowing) {
            throw IllegalStateException("Rider reformat shortcut fallback requires a showing editor component")
        }
        component.requestFocusInWindow()
        val modifiers = KeyEvent.CTRL_DOWN_MASK or KeyEvent.ALT_DOWN_MASK
        val now = System.currentTimeMillis()
        val eventQueue = IdeEventQueue.getInstance()
        eventQueue.dispatchEvent(
            KeyEvent(
                component,
                KeyEvent.KEY_PRESSED,
                now,
                modifiers,
                KeyEvent.VK_L,
                'L'
            )
        )
        eventQueue.dispatchEvent(
            KeyEvent(
                component,
                KeyEvent.KEY_RELEASED,
                now + 10,
                modifiers,
                KeyEvent.VK_L,
                'L'
            )
        )
    }

    private fun dispatchRiderOptimizeImportsShortcut(component: Component) {
        if (!component.isShowing) {
            throw IllegalStateException("Rider optimize imports shortcut fallback requires a showing editor component")
        }
        component.requestFocusInWindow()
        val modifiers = KeyEvent.ALT_DOWN_MASK or KeyEvent.SHIFT_DOWN_MASK
        val now = System.currentTimeMillis()
        val eventQueue = IdeEventQueue.getInstance()
        eventQueue.dispatchEvent(
            KeyEvent(
                component,
                KeyEvent.KEY_PRESSED,
                now,
                modifiers,
                KeyEvent.VK_O,
                'O'
            )
        )
        eventQueue.dispatchEvent(
            KeyEvent(
                component,
                KeyEvent.KEY_RELEASED,
                now + 10,
                modifiers,
                KeyEvent.VK_O,
                'O'
            )
        )
    }

    private fun buildReformatResult(
        file: String,
        changed: Boolean,
        startLine: Int?,
        endLine: Int?,
        operationsRun: List<String>,
        skippedOperations: List<String>,
        laneSuffix: String
    ): RefactoringResult {
        val rangeNote = if (startLine != null && endLine != null) {
            " (lines $startLine-$endLine)"
        } else ""
        val operationsNote = operationsRun.joinToString(", ")
        val skippedNote = if (skippedOperations.isEmpty()) {
            ""
        } else {
            "; skipped ${skippedOperations.joinToString(", ")}"
        }
        val action = if (changed) "Reformatted" else "No formatting changes required for"

        return RefactoringResult(
            success = changed,
            affectedFiles = if (changed) listOf(file) else emptyList(),
            changesCount = if (changed) 1 else 0,
            message = "$action $file$rangeNote; ran $operationsNote$skippedNote$laneSuffix",
            status = if (changed) "success" else "no_op",
            verification = MutationVerification(
                status = if (changed) "success" else "no_op",
                checksRun = operationsRun,
                warnings = skippedOperations
            )
        )
    }

    /**
     * Validates parameters and resolves the PSI file and text range.
     * Runs in a read action (background thread).
     */
    private fun validateAndPrepare(
        project: Project,
        virtualFile: VirtualFile,
        file: String,
        startLine: Int?,
        endLine: Int?
    ): ReformatValidation {
        val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
            ?: return ReformatValidation(error = "Cannot parse file: $file")

        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile)
            ?: return ReformatValidation(error = "Cannot get document for file: $file")

        // Calculate text range if line range specified
        val textRange = if (startLine != null && endLine != null) {
            val lineCount = document.lineCount
            if (startLine > lineCount) {
                return ReformatValidation(
                    error = "startLine ($startLine) exceeds file line count ($lineCount)"
                )
            }
            if (endLine > lineCount) {
                return ReformatValidation(
                    error = "endLine ($endLine) exceeds file line count ($lineCount)"
                )
            }

            val startOffset = document.getLineStartOffset(startLine - 1)
            val endOffset = document.getLineEndOffset(endLine - 1)
            TextRange(startOffset, endOffset)
        } else null

        return ReformatValidation(psiFile = psiFile, textRange = textRange, initialText = document.text)
    }

    /**
     * Executes the reformat operation using IntelliJ's processor chaining.
     * Must run on EDT.
     *
     * Uses [AbstractLayoutCodeProcessor.runWithoutProgress] instead of
     * [AbstractLayoutCodeProcessor.run] because `run()` dispatches via `ProgressManager`
     * as a background task in non-headless mode, returning before processing completes.
     * `runWithoutProgress()` executes synchronously, ensuring the document is fully
     * updated before we commit and save. Undo (Ctrl+Z) works automatically.
     */
    private fun executeReformat(
        project: Project,
        psiFile: PsiFile,
        textRange: TextRange?,
        optimizeImports: Boolean,
        rearrangeCode: Boolean
    ) {
        var processor: AbstractLayoutCodeProcessor = if (textRange != null) {
            ReformatCodeProcessor(psiFile, arrayOf(textRange))
        } else {
            ReformatCodeProcessor(psiFile, false)
        }

        if (optimizeImports) {
            processor = OptimizeImportsProcessor(processor)
        }
        if (rearrangeCode) {
            processor = RearrangeCodeProcessor(processor)
        }

        processor.runWithoutProgress()
    }
}
