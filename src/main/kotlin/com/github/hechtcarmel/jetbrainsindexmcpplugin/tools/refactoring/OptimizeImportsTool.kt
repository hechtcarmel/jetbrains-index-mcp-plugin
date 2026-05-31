package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.dotnet.RiderBackendSemanticService
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.RefactoringResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import com.intellij.codeInsight.actions.OptimizeImportsProcessor
import com.intellij.ide.IdeEventQueue
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ActionUiKind
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
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import java.awt.Component
import java.awt.event.KeyEvent
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject

/**
 * Optimizes imports in a file without reformatting code.
 *
 * Equivalent to the IDE's "Optimize Imports" action (Ctrl+Alt+O / Cmd+Opt+O).
 * Removes unused imports and organizes remaining imports according to project style.
 *
 * Does NOT require smart mode -- import optimization doesn't need indexes.
 */
class OptimizeImportsTool : AbstractMcpTool() {

    companion object {
        private val LOG = logger<OptimizeImportsTool>()
        private const val RIDER_ACTION_ID_FALLBACK = "OptimizeImports"
        private const val RIDER_SHORTCUT_SETTLE_ATTEMPTS = 8
        private const val RIDER_SHORTCUT_SETTLE_DELAY_MS = 150L
    }

    private data class RiderOptimizeImportsEditorLookup(
        val editor: Editor?,
        val virtualFile: VirtualFile,
        val openedByTool: Boolean,
        val reason: String
    )

    private data class RiderOptimizeImportsInvocation(
        val lane: String,
        val shortcutFallbackUsed: Boolean
    )

    override val name = ToolNames.OPTIMIZE_IMPORTS

    override val description = """
        Optimize imports in a file: remove unused imports and organize remaining imports according to project code style. Equivalent to the IDE's "Optimize Imports" action (Ctrl+Alt+O / Cmd+Opt+O). Does NOT reformat code. Supports undo (Ctrl+Z).

        Returns: success status, affected file, and description of operation performed.

        Parameters: file (required).

        Example: {"file": "src/MyClass.java"}
    """.trimIndent()

    override val inputSchema: JsonObject = SchemaBuilder.tool()
        .projectPath()
        .file()
        .build()

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val file = requiredStringArg(arguments, ParamNames.FILE).getOrElse {
            return createErrorResult(it.message ?: "Missing required parameter: file")
        }

        val virtualFile = resolveFile(project, file)
            ?: return createErrorResult("File not found: $file")
        val beforeText = readCurrentFileText(project, virtualFile)
            ?: return createErrorResult("File not found: $file")

        var openedByTool = false
        return try {
            val invocation = if (shouldUseRiderFrontendOptimizeImports(file)) {
                val editorLookup = resolveRiderOptimizeImportsEditor(project, virtualFile)
                openedByTool = editorLookup.openedByTool
                val editor = editorLookup.editor ?: return createErrorResult(
                    "Optimize imports failed: Rider optimize imports requires a focused text editor for '$file' (${editorLookup.reason})"
                )
                executeRiderFrontendOptimizeImports(project, virtualFile, editor)
            } else {
                val psiFile = suspendingReadAction { PsiManager.getInstance(project).findFile(virtualFile) }
                    ?: return createErrorResult("File not found: $file")
                edtAction {
                    executeOptimizeImports(project, psiFile)
                }
                RiderOptimizeImportsInvocation(
                    lane = "IntelliJ OptimizeImportsProcessor",
                    shortcutFallbackUsed = false
                )
            }

            commitDocuments(project)
            edtAction { FileDocumentManager.getInstance().saveAllDocuments() }

            if (shouldUseRiderFrontendOptimizeImports(file)) {
                awaitObservableOptimizeImportsMutation(project, virtualFile, beforeText)
            }

            val afterText = readCurrentFileText(project, virtualFile)
                ?: return createErrorResult("Optimize imports failed: file disappeared after invocation: $file")
            val changed = beforeText != afterText

            if (invocation.shortcutFallbackUsed && !changed) {
                return createErrorResult(
                    "Optimize imports failed: no stable Rider optimize-imports action id was available, and the Alt+Shift+O fallback produced no observable change for '$file'."
                )
            }

            val laneSuffix = " using ${invocation.lane}"
            createJsonResult(
                RefactoringResult(
                    success = changed,
                    affectedFiles = if (changed) listOf(file) else emptyList(),
                    changesCount = if (changed) 1 else 0,
                    message = if (changed) {
                        "Optimized imports in $file$laneSuffix"
                    } else {
                        "Optimize imports made no changes to $file$laneSuffix"
                    },
                    status = if (changed) "success" else "no_op"
                )
            )
        } catch (e: Exception) {
            LOG.warn("Optimize imports failed for $file", e)
            createErrorResult("Optimize imports failed: ${e.message ?: "Unknown error during import optimization"}")
        } finally {
            if (openedByTool) {
                edtAction {
                    FileEditorManager.getInstance(project).closeFile(virtualFile)
                }
            }
        }
    }

    private fun shouldUseRiderFrontendOptimizeImports(file: String): Boolean {
        return RiderBackendSemanticService.isRiderEnvironment() && RiderBackendSemanticService.isDotNetFile(file)
    }

    private suspend fun readCurrentFileText(project: Project, virtualFile: VirtualFile): String? {
        val document = edtAction { FileDocumentManager.getInstance().getDocument(virtualFile) }
        if (document != null) {
            return document.text
        }
        return suspendingReadAction { PsiManager.getInstance(project).findFile(virtualFile)?.text }
    }

    private suspend fun resolveRiderOptimizeImportsEditor(
        project: Project,
        virtualFile: VirtualFile
    ): RiderOptimizeImportsEditorLookup {
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
                return@edtAction RiderOptimizeImportsEditorLookup(
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

            RiderOptimizeImportsEditorLookup(
                editor = editor,
                virtualFile = virtualFile,
                openedByTool = editor != null && !wasAlreadyOpen,
                reason = if (editor != null) {
                    if (wasAlreadyOpen) "focused already-open file for Rider optimize imports" else "auto-opened file for Rider optimize imports"
                } else {
                    "no selected text editor matched target file after attempting to open it"
                }
            )
        }
    }

    private suspend fun executeRiderFrontendOptimizeImports(
        project: Project,
        virtualFile: VirtualFile,
        editor: Editor
    ): RiderOptimizeImportsInvocation {
        commitDocuments(project)
        edtAction { FileDocumentManager.getInstance().saveAllDocuments() }

        val dataContext = buildRiderOptimizeImportsDataContext(project, virtualFile, editor)
        val actionManager = ActionManager.getInstance()
        val candidateActionIds = resolveRiderOptimizeImportsActionIds()

        for (actionId in candidateActionIds) {
            val action = actionManager.getAction(actionId) ?: continue
            edtAction {
                invokeRiderOptimizeImportsAction(action, actionId, dataContext)
            }
            return RiderOptimizeImportsInvocation(
                lane = "Rider IDE action '$actionId'",
                shortcutFallbackUsed = false
            )
        }

        edtAction {
            dispatchRiderOptimizeImportsShortcut(editor.contentComponent)
        }
        return RiderOptimizeImportsInvocation(
            lane = "Rider Alt+Shift+O shortcut fallback",
            shortcutFallbackUsed = true
        )
    }

    private suspend fun buildRiderOptimizeImportsDataContext(
        project: Project,
        virtualFile: VirtualFile,
        editor: Editor
    ): DataContext {
        val psiFile = suspendingReadAction { PsiManager.getInstance(project).findFile(virtualFile) }
            ?: throw IllegalStateException("Rider optimize imports could not resolve PSI for '${virtualFile.path}'")
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

    private fun resolveRiderOptimizeImportsActionIds(): LinkedHashSet<String> {
        val actionIds = linkedSetOf<String>()
        resolveIdeActionsOptimizeImportsActionId()?.let(actionIds::add)
        actionIds.add(RIDER_ACTION_ID_FALLBACK)
        return actionIds
    }

    private fun resolveIdeActionsOptimizeImportsActionId(): String? {
        return runCatching {
            IdeActions::class.java.getField("ACTION_OPTIMIZE_IMPORTS").get(null) as? String
        }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    }

    private suspend fun awaitObservableOptimizeImportsMutation(
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

    private fun invokeRiderOptimizeImportsAction(
        action: AnAction,
        actionId: String,
        dataContext: DataContext
    ) {
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

    /**
     * Executes import optimization using IntelliJ's [OptimizeImportsProcessor].
     * Must run on EDT.
     *
     * Uses [OptimizeImportsProcessor.runWithoutProgress] instead of [OptimizeImportsProcessor.run]
     * because `run()` dispatches via `ProgressManager` as a background task in non-headless mode,
     * returning before processing completes. `runWithoutProgress()` executes synchronously,
     * ensuring the document is fully updated before we commit and save.
     */
    private fun executeOptimizeImports(project: Project, psiFile: com.intellij.psi.PsiFile) {
        OptimizeImportsProcessor(project, psiFile).runWithoutProgress()
    }
}
