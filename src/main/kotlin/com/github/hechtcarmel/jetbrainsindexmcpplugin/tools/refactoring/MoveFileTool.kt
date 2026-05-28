package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.dotnet.RiderBackendSemanticService
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.RefactoringResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PluginDetectors
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ProjectUtils
import com.intellij.ide.IdeEventQueue
import com.intellij.ide.projectView.ProjectView
import com.intellij.codeInsight.actions.OptimizeImportsProcessor
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.move.moveFilesOrDirectories.MoveFilesOrDirectoriesProcessor
import com.intellij.util.containers.MultiMap
import com.intellij.usageView.UsageInfo
import java.awt.AWTEvent
import java.awt.Component
import java.awt.Container
import java.awt.KeyboardFocusManager
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.AWTEventListener
import java.awt.event.KeyEvent
import java.awt.event.WindowEvent
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.swing.AbstractButton
import javax.swing.JDialog
import javax.swing.JTree
import javax.swing.JList
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.text.JTextComponent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Move file tool that uses the IDE's Move refactoring to relocate files
 * while delegating semantic updates to the IDE language plugin when supported.
 *
 * Most languages use [MoveFilesOrDirectoriesProcessor], which delegates file-move
 * semantics to language-specific [com.intellij.refactoring.move.moveFilesOrDirectories.MoveFileHandler]s.
 * PHP class files are special: PhpStorm exposes a dedicated class-move refactoring
 * processor. We route those files through that processor instead of the generic
 * file-move backend.
 *
 * Three-phase approach:
 * 1. **Read Action**: Validate source file exists
 * 2. **EDT Write Action**: Ensure destination directory exists (create if needed)
 * 3. **EDT**: Execute the appropriate move backend
 */
open class MoveFileTool : AbstractRefactoringTool() {

    private val log = logger<MoveFileTool>()

    companion object {
        private const val RIDER_DIALOG_AUTOMATION_TIMEOUT_MS = 15_000L
        private const val RIDER_DIALOG_READINESS_TIMEOUT_MS = 10_000L
        private const val RIDER_DIALOG_READINESS_POLL_MS = 200
        private const val RIDER_DIALOG_SECOND_STAGE_TIMEOUT_MS = 5_000L
        private const val RIDER_PROJECT_VIEW_FOCUS_SETTLE_DELAY_MS = 500
        private const val RIDER_REFACTOR_POPUP_TIMEOUT_MS = 10_000L
        private const val RIDER_REFACTOR_POPUP_POLL_MS = 200
        private val RIDER_MOVE_TO_FOLDER_LABELS = listOf(
            "Move to Folder",
            "Move to folder"
        )

        internal fun shouldAwaitSecondDialogAfterPrimarySubmit(buttonText: String?): Boolean {
            return buttonText?.trim()?.equals("Next", ignoreCase = true) == true
        }

        internal fun isSecondDialogCandidateTitle(title: String): Boolean {
            if (title.isBlank()) return false
            return title.contains("Refactor", ignoreCase = true) ||
                title.contains("Preview", ignoreCase = true) ||
                title.contains("Conflict", ignoreCase = true) ||
                title.contains("Move", ignoreCase = true)
        }
    }

    override val name = "ide_move_file"

    override val description = """
        Move a file to a new directory using the IDE's refactoring engine. Applies language-aware reference and namespace/package updates when the IDE provides a semantic move backend for that file type.

        Use when relocating files to maintain correct imports and references.

        Parameters:
        - file (REQUIRED): Source file path relative to project root
        - destination (REQUIRED): Target directory path relative to project root. Created automatically if it doesn't exist.

        Returns: success status, list of affected files, and result message.

        Examples:
        - Move file: {"file": "src/main/java/com/old/MyClass.java", "destination": "src/main/java/com/new"}
        - Move config file: {"file": "config/old.yml", "destination": "config/archive"}
    """.trimIndent()

    override val inputSchema: JsonObject = SchemaBuilder.tool()
        .projectPath()
        .file(description = "Path to the source file to move, relative to project root. REQUIRED.")
        .stringProperty("destination", "Target directory path relative to project root. The file will be moved into this directory. Created automatically if it doesn't exist. REQUIRED.", required = true)
        .build()

    internal enum class MoveBackend {
        GENERIC_FILE_MOVE,
        PHP_SEMANTIC_MOVE
    }

    internal sealed class MoveBackendSelection {
        object GenericFileMove : MoveBackendSelection()
        data class PhpSemanticMove(
            val declarationPointer: SmartPsiElementPointer<PsiElement>,
            val declarationName: String
        ) : MoveBackendSelection()
        data class Unsupported(val message: String) : MoveBackendSelection()
    }

    internal data class MovePreparation(
        val psiFile: PsiFile,
        val targetDirectory: PsiDirectory,
        val sourceRelativePath: String,
        val destinationRelativePath: String,
        val backend: MoveBackend,
        val phpDeclarationPointer: SmartPsiElementPointer<PsiElement>? = null,
        val phpDeclarationName: String? = null
    )

    private sealed interface MoveChangeSnapshot {
        data class Unsaved(val paths: Set<String>) : MoveChangeSnapshot
    }

    private data class RiderMoveProjectViewLookup(
        val dispatchComponent: Component?,
        val treeComponent: Component?,
        val reason: String
    )

    private data class RiderMoveDialogAutomationSnapshot(
        val moveDialogSeen: Boolean,
        val refactorPopupSeen: Boolean,
        val handledDialogs: List<String>,
        val failureReason: String?
    )

    internal fun shouldUseRiderBackendMove(filePath: String): Boolean {
        return RiderBackendSemanticService.isDotNetFile(filePath)
    }

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val file = requiredStringArg(arguments, "file").getOrElse {
            return createErrorResult(it.message ?: "Missing required parameter: file")
        }
        val destination = requiredStringArg(arguments, "destination").getOrElse {
            return createErrorResult(it.message ?: "Missing required parameter: destination")
        }

        requireSmartMode(project)

        // ═══════════════════════════════════════════════════════════════════════
        // PHASE 1: VFS + READ ACTION - Validate source file
        // ═══════════════════════════════════════════════════════════════════════
        val sourceVirtualFile = resolveFile(project, file)
            ?: return createErrorResult("Source file not found: $file")
        val sourceInfo = suspendingReadAction {
            val psiFile = PsiManager.getInstance(project).findFile(sourceVirtualFile)
            if (psiFile == null || !psiFile.isPhysical) {
                return@suspendingReadAction null
            }
            val relativePath = psiFile.virtualFile?.let { getRelativePath(project, it) } ?: file
            Triple(psiFile, psiFile.name, relativePath)
        } ?: return createErrorResult("Source file not found: $file")

        val (_, fileName, sourceRelativePath) = sourceInfo

        // ═══════════════════════════════════════════════════════════════════════
        // PHASE 2: EDT WRITE ACTION - Ensure destination directory exists
        // ═══════════════════════════════════════════════════════════════════════
        val targetDir = ensureDestinationDirectory(project, destination)
            ?: return createErrorResult("Invalid destination '$destination': could not resolve or create directory")

        // ═══════════════════════════════════════════════════════════════════════
        // PHASE 3: READ ACTION - Validate move (same dir, name conflict, get PSI dir)
        // ═══════════════════════════════════════════════════════════════════════
        val preparation = suspendingReadAction {
            val psiFile = PsiManager.getInstance(project).findFile(sourceVirtualFile)
                ?: return@suspendingReadAction null to "Source file no longer valid"

            val targetPsiDir = PsiManager.getInstance(project).findDirectory(targetDir)
                ?: return@suspendingReadAction null to "Could not find PSI directory for destination"

            val currentDir = psiFile.containingDirectory
            if (currentDir != null && currentDir.virtualFile.path == targetDir.path) {
                return@suspendingReadAction null to "File '$file' is already in the destination directory"
            }

            if (targetPsiDir.findFile(fileName) != null) {
                return@suspendingReadAction null to "A file named '$fileName' already exists in '$destination'"
            }

            when (val backendSelection = selectMoveBackend(project, psiFile)) {
                is MoveBackendSelection.Unsupported -> null to backendSelection.message
                MoveBackendSelection.GenericFileMove -> {
                    val destinationRelativePath = getRelativePath(project, targetDir)
                    MovePreparation(
                        psiFile = psiFile,
                        targetDirectory = targetPsiDir,
                        sourceRelativePath = sourceRelativePath,
                        destinationRelativePath = destinationRelativePath,
                        backend = MoveBackend.GENERIC_FILE_MOVE
                    ) to null
                }
                is MoveBackendSelection.PhpSemanticMove -> {
                    val destinationRelativePath = getRelativePath(project, targetDir)
                    MovePreparation(
                        psiFile = psiFile,
                        targetDirectory = targetPsiDir,
                        sourceRelativePath = sourceRelativePath,
                        destinationRelativePath = destinationRelativePath,
                        backend = MoveBackend.PHP_SEMANTIC_MOVE,
                        phpDeclarationPointer = backendSelection.declarationPointer,
                        phpDeclarationName = backendSelection.declarationName
                    ) to null
                }
            }
        }

        val (movePrep, error) = preparation
        if (movePrep == null) {
            return createErrorResult(error ?: "Unknown validation error")
        }

        if (shouldUseRiderBackendMove(file) && RiderBackendSemanticService.isRiderEnvironment()) {
            log.info("Routing Rider .NET move through Move to Folder dialog automation for '$file' -> '$destination'")
            val riderPreparationError = prepareDestinationDirectoryForRiderMove(project, targetDir)
            if (riderPreparationError != null) {
                return createErrorResult(riderPreparationError)
            }
            return executeRiderMoveWithDialogAutomation(project, movePrep, sourceVirtualFile, targetDir)
        }

        // ═══════════════════════════════════════════════════════════════════════
        // PHASE 4: EDT - Execute move processor (manages its own write actions)
        // ═══════════════════════════════════════════════════════════════════════
        return executeMove(project, movePrep)
    }

    private class RiderMoveDialogAutomationController(
        private val destinationAbsolutePath: String,
    ) {
        private val moveDialogSeen = AtomicBoolean(false)
        private val refactorPopupSeen = AtomicBoolean(false)
        private val waitingForSecondDialog = AtomicBoolean(false)
        private val automationCompleted = AtomicBoolean(false)
        private val moveDialogAutomated = CountDownLatch(1)
        private val handledDialogs = ConcurrentLinkedQueue<String>()
        private val failureReason = AtomicReference<String?>(null)
        private val listenerRegistered = AtomicBoolean(false)
        private val primaryDialogTitle = AtomicReference<String?>(null)
        private var moveDialogReadinessTimer: Timer? = null
        private var secondDialogTimeoutTimer: Timer? = null
        private var refactorPopupTimer: Timer? = null

        private val listener = AWTEventListener { event ->
            when (event.id) {
                WindowEvent.WINDOW_OPENED -> handleWindowOpened(event.source as? Window)
                WindowEvent.WINDOW_CLOSED -> handleWindowClosed(event.source as? Window)
            }
        }

        fun install() {
            Toolkit.getDefaultToolkit().addAWTEventListener(listener, AWTEvent.WINDOW_EVENT_MASK)
            listenerRegistered.set(true)
        }

        fun dispose() {
            cancelRefactorPopupTimer()
            cancelMoveDialogReadinessTimer()
            cancelSecondDialogTimeout()
            if (listenerRegistered.compareAndSet(true, false)) {
                Toolkit.getDefaultToolkit().removeAWTEventListener(listener)
            }
        }

        fun snapshot(): RiderMoveDialogAutomationSnapshot {
            return RiderMoveDialogAutomationSnapshot(
                moveDialogSeen = moveDialogSeen.get(),
                refactorPopupSeen = refactorPopupSeen.get(),
                handledDialogs = handledDialogs.toList(),
                failureReason = failureReason.get()
            )
        }

        fun startRefactorPopupPolling() {
            cancelRefactorPopupTimer()
            val deadline = System.currentTimeMillis() + RIDER_REFACTOR_POPUP_TIMEOUT_MS
            refactorPopupTimer = Timer(RIDER_REFACTOR_POPUP_POLL_MS.toInt()) { timerEvent ->
                if (failureReason.get() != null || automationCompleted.get()) {
                    (timerEvent.source as? Timer)?.stop()
                    refactorPopupTimer = null
                    return@Timer
                }
                if (tryAutomateRefactorPopup()) {
                    (timerEvent.source as? Timer)?.stop()
                    refactorPopupTimer = null
                    return@Timer
                }
                if (System.currentTimeMillis() >= deadline) {
                    (timerEvent.source as? Timer)?.stop()
                    refactorPopupTimer = null
                    fail(
                        "Refactor This popup did not expose a 'Move to Folder' action within ${RIDER_REFACTOR_POPUP_TIMEOUT_MS}ms",
                        null
                    )
                }
            }.apply {
                initialDelay = RIDER_REFACTOR_POPUP_POLL_MS
                isRepeats = true
                start()
            }
        }

        fun failTimeout() {
            fail("timed out waiting for Rider move dialog automation after ${RIDER_DIALOG_AUTOMATION_TIMEOUT_MS}ms", null)
        }

        fun failInvocation(reason: String) {
            fail(reason, null)
        }

        fun awaitMoveDialogAutomation(timeoutMs: Long): Boolean {
            return moveDialogAutomated.await(timeoutMs, TimeUnit.MILLISECONDS)
        }

        fun cancelOutstandingDialogs() {
            Window.getWindows()
                .filterIsInstance<JDialog>()
                .filter { it.isShowing }
                .forEach { dialog ->
                    val title = dialog.title?.trim().orEmpty()
                    if (
                        title.contains("Move", ignoreCase = true) ||
                            title.contains("Refactor", ignoreCase = true) ||
                            title.contains("Preview", ignoreCase = true) ||
                            title.contains("Conflict", ignoreCase = true)
                    ) {
                        cancelDialog(dialog)
                    }
                }
        }

        private fun handleWindowOpened(window: Window?) {
            if (window == null || !window.isShowing || failureReason.get() != null || automationCompleted.get()) {
                return
            }

            if (!moveDialogSeen.get() && tryAutomateRefactorPopup(window)) {
                return
            }

            val dialog = window as? JDialog ?: return

            val title = dialog.title?.trim().orEmpty().ifBlank { "<untitled>" }
            when {
                waitingForSecondDialog.get() -> automateFollowUpDialog(dialog, title)
                isMoveDialogTitle(title) -> automateMoveDialog(dialog, title)
            }
        }

        private fun handleWindowClosed(window: Window?) {
            val dialog = window as? JDialog ?: return
            if (!waitingForSecondDialog.get() || automationCompleted.get()) {
                return
            }

            val title = dialog.title?.trim().orEmpty().ifBlank { "<untitled>" }
            if (!title.equals(primaryDialogTitle.get(), ignoreCase = true)) {
                return
            }
        }

        private fun tryAutomateRefactorPopup(window: Window? = null): Boolean {
            if (refactorPopupSeen.get() || moveDialogSeen.get()) {
                return false
            }

            val candidates = if (window != null) listOf(window) else Window.getWindows().filter { it.isShowing }
            for (candidate in candidates) {
                if (candidate is JDialog) continue
                if (trySelectMoveToFolder(candidate)) {
                    refactorPopupSeen.set(true)
                    cancelRefactorPopupTimer()
                    handledDialogs += "Refactor This -> Move to Folder"
                    return true
                }
            }
            return false
        }

        private fun automateMoveDialog(dialog: JDialog, title: String) {
            if (!moveDialogSeen.compareAndSet(false, true)) {
                return
            }

            try {
                if (attemptMoveDialogAutomationWhenReady(dialog, title)) {
                    return
                }
                startMoveDialogReadinessPolling(dialog, title)
            } catch (t: Throwable) {
                fail("Move dialog automation failed: ${t.message ?: t.javaClass.name}", dialog)
            }
        }

        private fun attemptMoveDialogAutomationWhenReady(dialog: JDialog, title: String): Boolean {
            val textField = findDestinationTextComponent(dialog)
            val button = findButton(dialog, listOf("Next", "Refactor", "Move", "OK"))
            if (textField == null || !textField.isShowing || button == null) {
                return false
            }

            // Rider keeps "Next" disabled until the destination field is CHANGED.
            // Set the destination text FIRST, then wait for the button to enable.
            if (!button.isEnabled) {
                textField.text = destinationAbsolutePath
                textField.caretPosition = destinationAbsolutePath.length
                // Give Rider a moment to validate the new path and enable the button
                return false
            }

            performMoveDialogAutomation(title, textField, button)
            return true
        }

        private fun startMoveDialogReadinessPolling(dialog: JDialog, title: String) {
            cancelMoveDialogReadinessTimer()
            val deadline = System.currentTimeMillis() + RIDER_DIALOG_READINESS_TIMEOUT_MS
            moveDialogReadinessTimer = Timer(200) { timerEvent ->
                if (!dialog.isShowing || failureReason.get() != null || automationCompleted.get()) {
                    (timerEvent.source as? Timer)?.stop()
                    moveDialogReadinessTimer = null
                    return@Timer
                }
                if (attemptMoveDialogAutomationWhenReady(dialog, title)) {
                    (timerEvent.source as? Timer)?.stop()
                    moveDialogReadinessTimer = null
                    return@Timer
                }
                if (System.currentTimeMillis() >= deadline) {
                    (timerEvent.source as? Timer)?.stop()
                    moveDialogReadinessTimer = null
                    fail(
                        "Move dialog content did not become ready within ${RIDER_DIALOG_READINESS_TIMEOUT_MS}ms",
                        dialog
                    )
                }
            }.apply {
                initialDelay = RIDER_DIALOG_READINESS_POLL_MS
                isRepeats = true
                start()
            }
        }

        private fun performMoveDialogAutomation(
            title: String,
            textField: JTextComponent,
            button: AbstractButton
        ) {
            cancelMoveDialogReadinessTimer()
            textField.text = destinationAbsolutePath
            textField.caretPosition = destinationAbsolutePath.length
            primaryDialogTitle.set(title)
            handledDialogs += title
            val shouldAwaitSecondDialog = shouldAwaitSecondDialogAfterPrimarySubmit(button.text)
            if (shouldAwaitSecondDialog) {
                waitingForSecondDialog.set(true)
                scheduleSecondDialogTimeout()
            }
            button.doClick()
            if (!shouldAwaitSecondDialog) {
                completeAutomation()
            }
        }

        private fun automateFollowUpDialog(dialog: JDialog, title: String) {
            if (!isSecondDialogCandidateTitle(title)) {
                return
            }
            val button = when {
                title.contains("Refactor", ignoreCase = true) ||
                    title.contains("Preview", ignoreCase = true) ||
                    title.contains("Conflict", ignoreCase = true) -> {
                    findButton(dialog, listOf("Do Refactor", "Refactor", "Continue", "Move", "OK"))
                }

                else -> findButton(dialog, listOf("Do Refactor", "Refactor", "Continue", "Move", "OK"))
            } ?: return

            handledDialogs += title
            cancelSecondDialogTimeout()
            waitingForSecondDialog.set(false)
            button.doClick()
            completeAutomation()
        }

        private fun isMoveDialogTitle(title: String): Boolean {
            return title.equals("Move to Folder", ignoreCase = true) ||
                title.equals("Move", ignoreCase = true) ||
                (title.contains("Move", ignoreCase = true) && title.contains("Folder", ignoreCase = true))
        }

        private fun fail(reason: String, dialog: JDialog?) {
            if (!failureReason.compareAndSet(null, reason)) {
                return
            }
            cancelRefactorPopupTimer()
            cancelMoveDialogReadinessTimer()
            waitingForSecondDialog.set(false)
            cancelSecondDialogTimeout()
            completeLatch()
            dialog?.let(::cancelDialog)
        }

        private fun scheduleSecondDialogTimeout() {
            cancelSecondDialogTimeout()
            secondDialogTimeoutTimer = Timer(RIDER_DIALOG_SECOND_STAGE_TIMEOUT_MS.toInt()) {
                if (!waitingForSecondDialog.compareAndSet(true, false) || failureReason.get() != null) {
                    return@Timer
                }
                completeAutomation()
            }.apply {
                isRepeats = false
                start()
            }
        }

        private fun cancelSecondDialogTimeout() {
            secondDialogTimeoutTimer?.stop()
            secondDialogTimeoutTimer = null
        }

        private fun cancelRefactorPopupTimer() {
            refactorPopupTimer?.stop()
            refactorPopupTimer = null
        }

        private fun cancelMoveDialogReadinessTimer() {
            moveDialogReadinessTimer?.stop()
            moveDialogReadinessTimer = null
        }

        private fun completeAutomation() {
            cancelMoveDialogReadinessTimer()
            waitingForSecondDialog.set(false)
            cancelSecondDialogTimeout()
            if (automationCompleted.compareAndSet(false, true)) {
                completeLatch()
            }
        }

        private fun completeLatch() {
            moveDialogAutomated.countDown()
        }

        private fun cancelDialog(dialog: JDialog) {
            findButton(dialog, listOf("Cancel", "Close"))?.let { cancelButton ->
                cancelButton.doClick()
                return
            }
            dialog.dispose()
        }

        private fun trySelectMoveToFolder(container: Container): Boolean {
            val allLists = mutableListOf<JList<*>>()
            walkComponents(container) { component ->
                if (component is JList<*>) allLists.add(component)
            }

            for (list in allLists) {
                val targetIndex = findMoveToFolderIndex(list)
                if (targetIndex >= 0) {
                    list.selectedIndex = targetIndex
                    list.ensureIndexIsVisible(targetIndex)
                    list.requestFocusInWindow()

                    // Try multiple activation strategies for Rider's PopupItem
                    // Strategy 1: Simulate mouse double-click on the list cell
                    val cellBounds = list.getCellBounds(targetIndex, targetIndex)
                    if (cellBounds != null) {
                        val centerX = cellBounds.x + cellBounds.width / 2
                        val centerY = cellBounds.y + cellBounds.height / 2
                        val now = System.currentTimeMillis()
                        val clickEvent = java.awt.event.MouseEvent(
                            list, java.awt.event.MouseEvent.MOUSE_CLICKED, now,
                            0, centerX, centerY, 2, false, java.awt.event.MouseEvent.BUTTON1
                        )
                        list.dispatchEvent(clickEvent)
                    }

                    // Strategy 2: Also dispatch ENTER via IdeEventQueue as fallback
                    val now2 = System.currentTimeMillis()
                    com.intellij.ide.IdeEventQueue.getInstance().dispatchEvent(
                        KeyEvent(list, KeyEvent.KEY_PRESSED, now2, 0, KeyEvent.VK_ENTER, '\n')
                    )
                    com.intellij.ide.IdeEventQueue.getInstance().dispatchEvent(
                        KeyEvent(list, KeyEvent.KEY_RELEASED, now2 + 10, 0, KeyEvent.VK_ENTER, '\n')
                    )
                    return true
                }
            }

            return findButton(container, RIDER_MOVE_TO_FOLDER_LABELS)?.let { button ->
                button.doClick()
                true
            } == true
        }

        @Suppress("UNCHECKED_CAST")
        private fun tryGetRenderedText(list: JList<*>, item: Any?, index: Int): String? {
            return try {
                val renderer = list.cellRenderer ?: return null
                val component = (renderer as javax.swing.ListCellRenderer<Any?>)
                    .getListCellRendererComponent(list as JList<Any?>, item, index, false, false)
                when (component) {
                    is javax.swing.JLabel -> component.text
                    is javax.swing.text.JTextComponent -> component.text
                    else -> component.accessibleContext?.accessibleName
                }
            } catch (_: Exception) {
                null
            }
        }

        private fun findMoveToFolderIndex(list: JList<*>): Int {
            for (index in 0 until list.model.size) {
                val item = list.model.getElementAt(index)
                val label = describePopupItem(item)
                val renderedText = tryGetRenderedText(list, item, index)
                if (isMoveToFolderLabel(label) || (renderedText != null && isMoveToFolderLabel(renderedText))) {
                    return index
                }
            }
            return -1
        }

        private fun describePopupItem(item: Any?): String {
            return buildList {
                when (item) {
                    null -> Unit
                    is Component -> {
                        add(item.name)
                        add(item.accessibleContext?.accessibleName)
                        add(item.accessibleContext?.accessibleDescription)
                    }
                    else -> {
                        add(item.toString())
                        // IntelliJ popup items are often ActionItem wrappers — extract action text via reflection
                        tryExtractActionText(item)?.let { add(it) }
                    }
                }
            }
                .filterNotNull()
                .joinToString(" ")
                .trim()
        }

        private fun tryExtractActionText(item: Any): String? {
            return try {
                // Try getAction().getTemplatePresentation().getText()
                val action = item.javaClass.methods
                    .firstOrNull { it.name == "getAction" && it.parameterCount == 0 }
                    ?.invoke(item) ?: return null
                val presentation = action.javaClass.methods
                    .firstOrNull { it.name == "getTemplatePresentation" && it.parameterCount == 0 }
                    ?.invoke(action) ?: return null
                presentation.javaClass.methods
                    .firstOrNull { it.name == "getText" && it.parameterCount == 0 }
                    ?.invoke(presentation) as? String
            } catch (_: Exception) {
                null
            }
        }

        private fun isMoveToFolderLabel(label: String): Boolean {
            val normalized = label.trim()
            return RIDER_MOVE_TO_FOLDER_LABELS.any { candidate ->
                normalized.equals(candidate, ignoreCase = true) ||
                    normalized.contains(candidate, ignoreCase = true)
            }
        }

        private fun findButton(container: Container, labels: List<String>): AbstractButton? {
            return findComponent(container, AbstractButton::class.java) { button ->
                val text = button.text?.trim().orEmpty()
                labels.any { candidate -> text.equals(candidate, ignoreCase = true) }
            }
        }

        private fun findDestinationTextComponent(container: Container): JTextComponent? {
            val candidates = mutableListOf<JTextComponent>()
            walkComponents(container) { component ->
                val textComponent = component as? JTextComponent ?: return@walkComponents
                if (textComponent.isShowing && textComponent.isEnabled) {
                    candidates += textComponent
                }
            }
            return candidates.firstOrNull(::looksLikeDestinationField) ?: candidates.firstOrNull()
        }

        private fun looksLikeDestinationField(component: JTextComponent): Boolean {
            val hints = buildList {
                add(component.name)
                add(component.toolTipText)
                add(component.accessibleContext?.accessibleName)
                add(component.accessibleContext?.accessibleDescription)
                add(component.parent?.name)
            }
                .filterNotNull()
                .joinToString(" ")
            return hints.contains("destination", ignoreCase = true) ||
                hints.contains("folder", ignoreCase = true) ||
                hints.contains("target", ignoreCase = true) ||
                hints.contains("path", ignoreCase = true)
        }

        private fun <T : Component> findComponent(
            container: Container,
            clazz: Class<T>,
            predicate: (T) -> Boolean = { true }
        ): T? {
            var found: T? = null
            walkComponents(container) { component ->
                if (found == null && clazz.isInstance(component)) {
                    val cast = clazz.cast(component)
                    if (predicate(cast)) {
                        found = cast
                    }
                }
            }
            return found
        }

        private fun walkComponents(container: Container, consumer: (Component) -> Unit) {
            for (component in container.components) {
                consumer(component)
                if (component is Container) {
                    walkComponents(component, consumer)
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun executeRiderMoveWithDialogAutomation(
        project: Project,
        preparation: MovePreparation,
        sourceVirtualFile: VirtualFile,
        targetDir: VirtualFile
    ): ToolCallResult {
        commitDocuments(project)
        edtAction { FileDocumentManager.getInstance().saveAllDocuments() }

        val projectViewLookup = lookupRiderMoveProjectViewComponent(project, preparation.psiFile, sourceVirtualFile)
        if (projectViewLookup.dispatchComponent == null) {
            return createErrorResult(
                "Move failed: Rider move dialog automation requires the source file to be selected in Rider's Project Explorer, but ${projectViewLookup.reason}."
            )
            }

            // Rider's Move to Folder dialog expects project-relative paths with backslashes
            // e.g. "RagasaWebServices\Models\IndexMcpSmokeMove" not absolute paths
            val projectBasePath = project.basePath?.let { java.io.File(it).canonicalPath } ?: ""
        val targetAbsPath = java.io.File(targetDir.path).canonicalPath
        val riderDestinationPath = if (targetAbsPath.startsWith(projectBasePath)) {
            targetAbsPath.removePrefix(projectBasePath).removePrefix(java.io.File.separator)
            } else {
                targetAbsPath
            }.replace('/', '\\')
        val dialogAutomation = RiderMoveDialogAutomationController(riderDestinationPath)
        val sourceAbsolutePath = sourceVirtualFile.path
        val newAbsolutePath = Path.of(targetDir.path, preparation.psiFile.name).normalize().toString()

        try {
            edtAction {
                focusRiderMoveInvocationComponent(projectViewLookup.dispatchComponent)
                dialogAutomation.install()
                ApplicationManager.getApplication().invokeLater(
                    {
                        dialogAutomation.startRefactorPopupPolling()
                        scheduleRiderRefactorThisShortcut(projectViewLookup.dispatchComponent, dialogAutomation)
                    },
                    ModalityState.any()
                )
            }

            val completedInTime = dialogAutomation.awaitMoveDialogAutomation(RIDER_DIALOG_AUTOMATION_TIMEOUT_MS)
            if (!completedInTime) {
                edtAction {
                    dialogAutomation.failTimeout()
                    dialogAutomation.cancelOutstandingDialogs()
                }
                return createErrorResult(
                    "Move failed: Rider move dialog automation timed out after ${RIDER_DIALOG_AUTOMATION_TIMEOUT_MS}ms"
                )
            }

            val automationSnapshot = dialogAutomation.snapshot()
            if (automationSnapshot.failureReason != null) {
                edtAction { dialogAutomation.cancelOutstandingDialogs() }
                return createErrorResult("Move failed: ${automationSnapshot.failureReason}")
            }
            if (!automationSnapshot.refactorPopupSeen) {
                return createErrorResult(
                    "Move failed: simulated Ctrl+Shift+R did not expose a selectable Move to Folder popup entry"
                )
            }
            if (!automationSnapshot.moveDialogSeen) {
                return createErrorResult(
                    "Move failed: simulated Ctrl+Shift+R completed without showing the expected Move to Folder dialog"
                )
            }

            refreshAffectedFiles(listOf(sourceAbsolutePath, newAbsolutePath, targetDir.path))
            refreshProjectRootsAndCommit(project)
            commitDocuments(project)
            edtAction { FileDocumentManager.getInstance().saveAllDocuments() }

            if (Files.exists(Path.of(sourceAbsolutePath)) || !Files.exists(Path.of(newAbsolutePath))) {
                return createErrorResult(
                    "Move failed: Rider move dialog automation completed, but the file-path transition could not be confirmed for '${preparation.sourceRelativePath}'"
                )
            }

            val newRelativePath = if (preparation.destinationRelativePath.isBlank()) {
                preparation.psiFile.name
            } else {
                "${preparation.destinationRelativePath}/${preparation.psiFile.name}"
            }
            val affectedFiles = linkedSetOf(preparation.sourceRelativePath, newRelativePath)
            findContainingCsproj(project, sourceVirtualFile)?.let(affectedFiles::add)

            FileDocumentManager.getInstance().saveAllDocuments()

            return createJsonResult(
                RefactoringResult(
                    success = true,
                    affectedFiles = affectedFiles.toList(),
                    changesCount = affectedFiles.size,
                    message = "Successfully moved '${preparation.sourceRelativePath}' to '$newRelativePath' using Rider semantic refactoring; namespace and .csproj updated. Note: additional consumer files may have been updated with 'using' statement changes by Rider"
                )
            )
        } finally {
            edtAction {
                dialogAutomation.dispose()
            }
        }
    }

    private fun focusRiderMoveInvocationComponent(component: Component) {
        component.requestFocusInWindow()
    }

    private fun scheduleRiderRefactorThisShortcut(
        invocationComponent: Component,
        dialogAutomation: RiderMoveDialogAutomationController
    ) {
        // Dispatch the shortcut repeatedly (every 1s) until the popup appears or we give up.
        // Focus on the Project Explorer tree is unreliable — retrying increases success rate.
        val retryIntervalMs = 1000
        Timer(RIDER_PROJECT_VIEW_FOCUS_SETTLE_DELAY_MS.toInt()) { timerEvent ->
            if (dialogAutomation.snapshot().refactorPopupSeen || dialogAutomation.snapshot().moveDialogSeen ||
                dialogAutomation.snapshot().failureReason != null) {
                (timerEvent.source as? Timer)?.stop()
                return@Timer
            }
            try {
                focusRiderMoveInvocationComponent(invocationComponent)
                dispatchRiderRefactorThisShortcut(invocationComponent)
            } catch (t: Throwable) {
                dialogAutomation.failInvocation(
                    "Simulated Ctrl+Shift+R dispatch failed before dialog automation could complete: ${t.message ?: t.javaClass.name}"
                )
                (timerEvent.source as? Timer)?.stop()
            }
        }.apply {
            isRepeats = true
            delay = retryIntervalMs
            start()
        }
    }

    private fun dispatchRiderRefactorThisShortcut(invocationComponent: Component) {
        invocationComponent.requestFocusInWindow()
        val modifiers = KeyEvent.CTRL_DOWN_MASK or KeyEvent.SHIFT_DOWN_MASK
        val now = System.currentTimeMillis()
        val eventQueue = IdeEventQueue.getInstance()
        eventQueue.dispatchEvent(
            KeyEvent(
                invocationComponent,
                KeyEvent.KEY_PRESSED,
                now,
                modifiers,
                KeyEvent.VK_R,
                'R'
            )
        )
        eventQueue.dispatchEvent(
            KeyEvent(
                invocationComponent,
                KeyEvent.KEY_RELEASED,
                now + 10,
                modifiers,
                KeyEvent.VK_R,
                'R'
            )
        )
    }

    private suspend fun lookupRiderMoveProjectViewComponent(
        project: Project,
        psiFile: PsiFile,
        sourceVirtualFile: VirtualFile
    ): RiderMoveProjectViewLookup {
        return edtAction {
            // Bring the IDE window to the foreground — required when Rider is in background
            val ideFrame = com.intellij.openapi.wm.WindowManager.getInstance().getFrame(project)
            if (ideFrame != null) {
                ideFrame.toFront()
                ideFrame.requestFocus()
            }

            // Ensure the Project Explorer / Solution Explorer tool window is visible and focused
            // before attempting to select the file. Without this, Ctrl+Shift+R won't fire in the right context.
            val toolWindowManager = com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
            // Try Rider's "Explorer" first, then IntelliJ's "Project"
            val explorerWindow = toolWindowManager.getToolWindow("Explorer")
                ?: toolWindowManager.getToolWindow("Project")
            explorerWindow?.activate(null, true, true)

            val projectView = ProjectView.getInstance(project)

            fun resolveLookup(reasonPrefix: String): RiderMoveProjectViewLookup {
                val treeComponent = resolveProjectViewTreeComponent(projectView)
                treeComponent?.requestFocusInWindow()
                val focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
                val dispatchComponent = when {
                    focusOwner != null && focusOwner.isShowing && treeComponent != null &&
                        (focusOwner === treeComponent || SwingUtilities.isDescendingFrom(focusOwner, treeComponent)) -> focusOwner
                    treeComponent != null && treeComponent.isShowing -> treeComponent
                    focusOwner != null && focusOwner.isShowing -> focusOwner
                    else -> treeComponent
                }

                val reason = when {
                    dispatchComponent == null -> "$reasonPrefix no focused Project Explorer component could be resolved"
                    dispatchComponent === focusOwner -> "$reasonPrefix selected '${sourceVirtualFile.name}' in Project Explorer and will dispatch Ctrl+Shift+R to ${dispatchComponent.javaClass.simpleName}"
                    else -> "$reasonPrefix selected '${sourceVirtualFile.name}' in Project Explorer and will dispatch Ctrl+Shift+R to ${dispatchComponent.javaClass.simpleName} as a fallback"
                }

                return RiderMoveProjectViewLookup(
                    dispatchComponent = dispatchComponent,
                    treeComponent = treeComponent,
                    reason = reason
                )
            }

            runCatching {
                projectView.select(psiFile, sourceVirtualFile, true)
            }
                .onFailure { error ->
                    log.warn("Failed to select '${sourceVirtualFile.path}' in Rider Project Explorer via ProjectView.select", error)
                }

            resolveLookup("ProjectView.select requested focus on Project Explorer;").takeIf { it.dispatchComponent != null }
                ?.let { return@edtAction it }

            runCatching {
                projectView.javaClass.methods
                    .firstOrNull { it.name == "selectPsiElement" && it.parameterCount == 2 }
                    ?.invoke(projectView, psiFile, true)
            }
                .onFailure { error ->
                    log.warn("Fallback Project Explorer selection failed for '${sourceVirtualFile.path}'", error)
                }

            resolveLookup("ProjectView.selectPsiElement fallback requested focus on Project Explorer;")
        }
    }

    private fun resolveProjectViewTreeComponent(projectView: ProjectView): Component? {
        val pane = runCatching {
            projectView.javaClass.methods
                .firstOrNull { it.name == "getCurrentProjectViewPane" && it.parameterCount == 0 }
                ?.invoke(projectView)
        }.getOrNull() ?: return null

        val tree = runCatching {
            pane.javaClass.methods
                .firstOrNull { it.name == "getTree" && it.parameterCount == 0 }
                ?.invoke(pane) as? JTree
        }.getOrNull()
        if (tree != null) {
            return tree
        }

        return runCatching {
            pane.javaClass.methods
                .firstOrNull { it.name == "getComponent" && it.parameterCount == 0 }
                ?.invoke(pane) as? Component
        }.getOrNull()
    }

    private suspend fun prepareDestinationDirectoryForRiderMove(
        project: Project,
        targetDir: VirtualFile
    ): String? {
        return try {
            Files.createDirectories(Path.of(targetDir.path))
            LocalFileSystem.getInstance().refreshAndFindFileByPath(targetDir.path)
            VfsUtil.markDirtyAndRefresh(false, true, true, targetDir)
            refreshProjectRootsAndCommit(project)
            commitDocuments(project)
            edtAction { FileDocumentManager.getInstance().saveAllDocuments() }
            null
        } catch (e: Exception) {
            "Move failed: Rider destination directory '${targetDir.path}' could not be prepared before dialog automation: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    /**
     * Ensures the destination directory exists, creating it on EDT if needed.
     * Uses [VfsUtil.createDirectoryIfMissing] which is the standard IDE API for this.
     */
    private suspend fun ensureDestinationDirectory(project: Project, destination: String): VirtualFile? {
        // First try resolving without creating (fast path, no write action needed)
        val existing = resolveFile(project, destination)
        if (existing != null && existing.isDirectory) {
            return existing
        }

        // Create directory on EDT inside a write action
        val basePath = project.basePath ?: return null
        val baseDir = LocalFileSystem.getInstance().findFileByPath(basePath) ?: return null

        var created: VirtualFile? = null
        edtAction {
            WriteCommandAction.writeCommandAction(project)
                .withName("Create Directory: $destination")
                .withGroupId("MCP Refactoring")
                .run<Throwable> {
                    created = try {
                        VfsUtil.createDirectoryIfMissing(baseDir, destination)
                    } catch (_: Exception) {
                        null
                    }
                }
        }
        return created
    }

    /**
     * Executes the move refactoring on EDT using MoveFilesOrDirectoriesProcessor.
     *
     * The processor manages its own WriteCommandAction internally, so we do NOT
     * wrap it in another WriteCommandAction. We only ensure it runs on EDT.
     */
    private suspend fun executeMove(
        project: Project,
        preparation: MovePreparation
    ): ToolCallResult {
        var success = false
        var errorMessage: String? = null
        var affectedFiles = linkedSetOf<String>()
        var namespaceUpdated = false
        val fileName = preparation.psiFile.name
        val originalSourcePath = preparation.psiFile.virtualFile.path

        edtAction {
            try {
                if (!preparation.psiFile.isValid || !preparation.targetDirectory.isValid) {
                    errorMessage = "Source file or target directory is no longer valid"
                    return@edtAction
                }

                val filePointer = SmartPointerManager.createPointer(preparation.psiFile)
                val modifiedFilesBeforeMove = captureMoveChangeSnapshot(project)

                when (preparation.backend) {
                    MoveBackend.GENERIC_FILE_MOVE -> executeGenericFileMove(preparation)
                    MoveBackend.PHP_SEMANTIC_MOVE -> executePhpSemanticMove(project, preparation)
                }

                if (preparation.backend == MoveBackend.PHP_SEMANTIC_MOVE) {
                    cleanupMovedPhpFileImports(filePointer.element)
                }
                namespaceUpdated = updateMovedCSharpNamespace(project, preparation, fileName)

                PsiDocumentManager.getInstance(project).commitAllDocuments()
                verifyMovedFileState(preparation, fileName, originalSourcePath)
                affectedFiles = collectAffectedFiles(project, preparation, filePointer, fileName, modifiedFilesBeforeMove)
                if (namespaceUpdated) {
                    preparation.targetDirectory.findFile(fileName)?.virtualFile?.let { affectedFiles.add(getRelativePath(project, it)) }
                }
                FileDocumentManager.getInstance().saveAllDocuments()

                success = true
            } catch (e: Exception) {
                errorMessage = e.message
            }
        }

        return if (success) {
            val newPath = if (preparation.destinationRelativePath.isBlank()) {
                fileName
            } else {
                "${preparation.destinationRelativePath}/$fileName"
            }
            val backendNote = when (preparation.backend) {
                MoveBackend.GENERIC_FILE_MOVE -> if (fileName.endsWith(".cs", ignoreCase = true)) {
                    if (namespaceUpdated) {
                        " using IDE file move semantics; C# namespace updated"
                    } else {
                        " using IDE file move semantics; no C# namespace change was needed"
                    }
                } else {
                    " using IDE file move semantics"
                }
                MoveBackend.PHP_SEMANTIC_MOVE -> " using PhpStorm semantic PHP move"
            }
            refreshProjectRootsAndCommit(project)
            edtAction { FileDocumentManager.getInstance().saveAllDocuments() }
            createJsonResult(
                RefactoringResult(
                    success = true,
                    affectedFiles = affectedFiles.toList(),
                    changesCount = affectedFiles.size,
                    message = "Successfully moved '${preparation.sourceRelativePath}' to '$newPath'$backendNote"
                )
            )
        } else {
            createErrorResult("Move failed: ${errorMessage ?: "Unknown error"}")
        }
    }

    private fun verifyMovedFileState(preparation: MovePreparation, fileName: String, originalSourcePath: String) {
        val movedFile = preparation.targetDirectory.findFile(fileName)
        val movedVirtualFile = movedFile?.virtualFile
        val moveChangedPath = movedVirtualFile != null && movedVirtualFile.path != originalSourcePath
        val sourcePathStillExists = Files.exists(Path.of(originalSourcePath))
        if (movedVirtualFile == null || !movedVirtualFile.isValid || !moveChangedPath || sourcePathStillExists) {
            error(
                "IDE move processor did not produce the expected file relocation for '${preparation.sourceRelativePath}'"
            )
        }
    }

    internal open fun selectMoveBackend(
        project: Project,
        psiFile: PsiFile
    ): MoveBackendSelection {
        if (!PluginDetectors.php.isAvailable || !isPhpFile(psiFile)) {
            return MoveBackendSelection.GenericFileMove
        }

        val phpDeclarations = findNamedPhpDeclarations(psiFile)
        if (phpDeclarations.isEmpty()) {
            return MoveBackendSelection.GenericFileMove
        }
        if (phpDeclarations.size > 1) {
            return MoveBackendSelection.Unsupported(
                "PHP semantic move is ambiguous for '${psiFile.name}' because it contains multiple named PHP declarations. " +
                    "Use PhpStorm's interactive Move refactoring for this file."
            )
        }

        val declaration = phpDeclarations.single()
        val declarationName = (declaration as? PsiNamedElement)?.name ?: psiFile.name
        val pointer = SmartPointerManager.getInstance(project).createSmartPsiElementPointer(declaration)
        return MoveBackendSelection.PhpSemanticMove(pointer, declarationName)
    }

    internal open fun executeGenericFileMove(
        preparation: MovePreparation
    ) {
        val processor = HeadlessMoveProcessor(
            preparation.psiFile.project,
            arrayOf<PsiElement>(preparation.psiFile),
            preparation.targetDirectory,
            true,
            false, // searchInComments
            false, // searchInNonJavaFiles
            null,  // moveCallback
            null   // prepareSuccessfulCallback
        )

        processor.setPreviewUsages(false)
        processor.run()
    }

    private fun updateMovedCSharpNamespace(
        project: Project,
        preparation: MovePreparation,
        fileName: String
    ): Boolean {
        val movedFile = preparation.targetDirectory.findFile(fileName)
        if (movedFile == null || movedFile.virtualFile?.extension?.lowercase() != "cs") return false

        val document = PsiDocumentManager.getInstance(project).getDocument(movedFile) ?: return false
        val text = document.text
        val namespaceRegex = Regex("""(?m)^(\s*namespace\s+)([A-Za-z_][A-Za-z0-9_.]*)(\s*[;{])""")
        val matches = namespaceRegex.findAll(text).toList()
        if (matches.isEmpty()) return false
        // Refuse to silently rewrite only the first namespace when the file declares multiple
        // (e.g. multiple top-level scoped namespaces). Rider's refactoring engine is the right
        // place to handle that; the heuristic is only safe for the common single-namespace case.
        if (matches.size > 1) {
            log.info(
                "Skipping C# namespace update for '${preparation.destinationRelativePath}/$fileName': " +
                    "file declares ${matches.size} namespaces; manual or Rider-driven update required."
            )
            return false
        }
        val match = matches.single()
        val oldNamespace = match.groupValues[2]
        val newNamespace = inferMovedCSharpNamespace(oldNamespace, preparation) ?: return false
        if (newNamespace == oldNamespace) return false

        WriteCommandAction.writeCommandAction(project)
            .withName("Update C# Namespace")
            .withGroupId("MCP Refactoring")
            .run<Throwable> {
                document.replaceString(
                    match.groups[2]!!.range.first,
                    match.groups[2]!!.range.last + 1,
                    newNamespace
                )
                PsiDocumentManager.getInstance(project).commitDocument(document)
            }
        return true
    }

    /**
     * Infers a destination C# namespace from the file's source namespace and the move's
     * old/new directory paths. Heuristic — does not consult `.csproj`/RootNamespace and assumes:
     *   * `src`, `main`, `test`, `csharp`, and `cs` are infrastructure folders (filtered out).
     *   * The original namespace's trailing segments mirror the source directory's trailing
     *     segments (common .NET convention). When that holds, the matching tail is replaced
     *     by the new directory tail; otherwise a single trailing segment is dropped.
     * Returns null if no plausible new namespace can be derived (e.g. moving to a project root).
     */
    private fun inferMovedCSharpNamespace(
        oldNamespace: String,
        preparation: MovePreparation
    ): String? {
        val oldDirSegments = preparation.sourceRelativePath
            .substringBeforeLast('/', "")
            .split('/', '\\')
            .filter { it.isNotBlank() && it != "src" && it != "main" && it != "test" }
            .filterNot { it.equals("csharp", ignoreCase = true) || it.equals("cs", ignoreCase = true) }
        val newDirSegments = preparation.destinationRelativePath
            .split('/', '\\')
            .filter { it.isNotBlank() && it != "src" && it != "main" && it != "test" }
            .filterNot { it.equals("csharp", ignoreCase = true) || it.equals("cs", ignoreCase = true) }

        if (newDirSegments.isEmpty()) return null
        val oldNamespaceSegments = oldNamespace.split('.').filter { it.isNotBlank() }
        val trailingMatchCount = commonTrailingSegmentCount(oldNamespaceSegments, oldDirSegments)
        val namespacePrefixSegments = if (trailingMatchCount > 0) {
            oldNamespaceSegments.dropLast(trailingMatchCount)
        } else {
            oldNamespaceSegments.dropLast(1)
        }
        val newNamespaceSegments = if (
            namespacePrefixSegments.isNotEmpty() &&
            newDirSegments.take(namespacePrefixSegments.size) == namespacePrefixSegments
        ) {
            newDirSegments
        } else {
            namespacePrefixSegments + newDirSegments
        }
        return newNamespaceSegments.distinctAdjacent().joinToString(".")
    }

    private fun commonTrailingSegmentCount(left: List<String>, right: List<String>): Int {
        var count = 0
        while (
            count < left.size &&
            count < right.size &&
            left[left.lastIndex - count] == right[right.lastIndex - count]
        ) {
            count++
        }
        return count
    }

    private fun List<String>.distinctAdjacent(): List<String> =
        filterIndexed { index, segment -> index == 0 || this[index - 1] != segment }

    internal open fun executePhpSemanticMove(project: Project, preparation: MovePreparation) {
        val declaration = preparation.phpDeclarationPointer?.element
            ?: error("PHP declaration for semantic move is no longer valid")
        val phpClassClass = loadPhpClassClass()
        if (!phpClassClass.isInstance(declaration)) {
            error(
                "PhpStorm semantic move is unavailable for PHP declaration '${preparation.phpDeclarationName ?: preparation.psiFile.name}'. " +
                    "Only PHP class-like declarations are supported."
            )
        }

        val destinationNamespace = determinePhpDestinationNamespace(project, preparation)
            ?: error(
                "PhpStorm semantic move could not determine the destination namespace for '${preparation.destinationRelativePath}'. " +
                    "Use a PSR-mapped source directory or PhpStorm's interactive Move refactoring."
            )

        runPhpMoveClassProcessor(project, preparation, phpClassClass.cast(declaration), destinationNamespace)
    }

    private fun runPhpMoveClassProcessor(
        project: Project,
        preparation: MovePreparation,
        phpClass: PsiElement,
        destinationNamespace: String
    ) {
        try {
            val phpClassClass = loadPhpClassClass()
            val className = (phpClass as? PsiNamedElement)?.name
                ?: error("PHP declaration name is no longer available")

            val phpFileCreationInfoClass = Class.forName("com.jetbrains.php.refactoring.PhpFileCreationInfo")
            val moveClassSettingsClass = Class.forName("com.jetbrains.php.refactoring.move.clazz.PhpMoveClassSettings")
            val moveClassProcessorClass = Class.forName("com.jetbrains.php.refactoring.move.clazz.PhpMoveClassProcessor")
            val moveClassDialogClass = Class.forName("com.jetbrains.php.refactoring.move.clazz.PhpMoveClassDialog")

            val generateConfiguration = phpFileCreationInfoClass.getMethod(
                "generateConfiguration",
                Project::class.java,
                String::class.java,
                String::class.java
            )
            val fileCreationInfo = generateConfiguration.invoke(
                null,
                project,
                preparation.targetDirectory.virtualFile.path,
                "$className.php"
            )

            val scopeContainsMultipleClasses = moveClassDialogClass
                .getMethod("isScopeHolderContainsMultipleClasses", phpClassClass)
                .invoke(null, phpClass) as Boolean

            val settings = moveClassSettingsClass
                .getConstructor(
                    phpClassClass,
                    String::class.java,
                    phpFileCreationInfoClass,
                    Boolean::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType
                )
                .newInstance(phpClass, destinationNamespace, fileCreationInfo, scopeContainsMultipleClasses, true)

            val processor = moveClassProcessorClass
                .getConstructor(
                    Project::class.java,
                    moveClassSettingsClass,
                    Boolean::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType
                )
                .newInstance(project, settings, false, false)

            processor.javaClass.methods.firstOrNull { it.name == "setPreviewUsages" && it.parameterCount == 1 }
                ?.invoke(processor, false)
            processor.javaClass.getMethod("run").invoke(processor)
        } catch (e: ReflectiveOperationException) {
            throw IllegalStateException(
                "PhpStorm semantic move is unavailable in this IDE build: ${e.cause?.message ?: e.message}",
                e
            )
        }
    }

    private fun determinePhpDestinationNamespace(project: Project, preparation: MovePreparation): String? {
        val suggested = suggestPhpNamespaceFromProvider(preparation.targetDirectory)
        if (!suggested.isNullOrBlank()) {
            return normalizePhpNamespace(suggested)
        }

        val composerNamespace = determinePhpNamespaceFromComposer(project, preparation.targetDirectory)
        if (!composerNamespace.isNullOrBlank()) {
            return composerNamespace
        }

        return derivePhpNamespaceFromSourceRoot(project, preparation)
    }

    private fun suggestPhpNamespaceFromProvider(targetDirectory: PsiDirectory): String? {
        return try {
            val providerClass = Class.forName("com.jetbrains.php.roots.PhpNamespaceByPsrProvider")
            val suggestMethod = providerClass.getMethod("suggestNamespaceWithPsrRootsDetection", PsiDirectory::class.java)
            suggestMethod.invoke(null, targetDirectory) as? String
        } catch (_: ReflectiveOperationException) {
            null
        }
    }

    private fun determinePhpNamespaceFromComposer(project: Project, targetDirectory: PsiDirectory): String? {
        val projectBasePath = project.basePath ?: return null
        val composerFile = Path.of(projectBasePath, "composer.json")
        if (!Files.isRegularFile(composerFile)) {
            return null
        }

        return try {
            val root = Json.parseToJsonElement(Files.readString(composerFile)).jsonObject
            val mappings = buildList {
                addAll(extractComposerPsrMappings(root["autoload"] as? JsonObject, projectBasePath))
                addAll(extractComposerPsrMappings(root["autoload-dev"] as? JsonObject, projectBasePath))
            }
            val targetPath = targetDirectory.virtualFile.path
            val mapping = mappings
                .filter { (namespacePrefix, directoryPath) ->
                    targetPath == directoryPath || targetPath.startsWith("$directoryPath/")
                }
                .maxByOrNull { (_, directoryPath) -> directoryPath.length }
                ?: return null

            val relativePath = targetPath.removePrefix(mapping.second).trimStart('/')
            val suffix = relativePath
                .split('/')
                .filter { it.isNotBlank() }
                .joinToString("\\")

            normalizePhpNamespace(
                listOf(mapping.first, suffix)
                    .filter { it.isNotBlank() }
                    .joinToString("\\")
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun extractComposerPsrMappings(section: JsonObject?, projectBasePath: String): List<Pair<String, String>> {
        val psr4 = section?.get("psr-4") as? JsonObject ?: return emptyList()
        return buildList {
            for ((namespacePrefix, locationValue) in psr4) {
                val namespace = normalizePhpNamespace(namespacePrefix)
                when (locationValue) {
                    is JsonPrimitive -> {
                        add(namespace to resolveComposerPath(projectBasePath, locationValue.content))
                    }
                    else -> {
                        locationValue.jsonArray.forEach { entry ->
                            val entryValue = entry as? JsonPrimitive ?: return@forEach
                            add(namespace to resolveComposerPath(projectBasePath, entryValue.content))
                        }
                    }
                }
            }
        }
    }

    private fun resolveComposerPath(projectBasePath: String, rawPath: String): String {
        return Path.of(projectBasePath)
            .resolve(rawPath)
            .normalize()
            .toString()
            .replace('\\', '/')
            .removeSuffix("/")
    }

    private fun derivePhpNamespaceFromSourceRoot(project: Project, preparation: MovePreparation): String? {
        val currentDirectory = preparation.psiFile.containingDirectory ?: return null
        val currentNamespace = extractPhpNamespace(preparation.phpDeclarationPointer?.element ?: preparation.psiFile) ?: return null

        val fileIndex = ProjectRootManager.getInstance(project).fileIndex
        val currentSourceRoot = fileIndex.getSourceRootForFile(currentDirectory.virtualFile) ?: return null
        if (!VfsUtil.isAncestor(currentSourceRoot, preparation.targetDirectory.virtualFile, false)) {
            return null
        }

        val currentRelativeDir = VfsUtil.getRelativePath(currentDirectory.virtualFile, currentSourceRoot, '/')
            ?.takeIf { it.isNotEmpty() }
        val targetRelativeDir = VfsUtil.getRelativePath(preparation.targetDirectory.virtualFile, currentSourceRoot, '/')
            ?: return null

        val suffix = currentRelativeDir?.replace('/', '\\')
        val prefix = when {
            suffix.isNullOrEmpty() -> currentNamespace
            currentNamespace == suffix -> ""
            currentNamespace.endsWith("\\$suffix") -> currentNamespace.removeSuffix("\\$suffix")
            else -> return null
        }

        val targetSuffix = targetRelativeDir
            .takeIf { it.isNotEmpty() }
            ?.replace('/', '\\')
            .orEmpty()
        return normalizePhpNamespace(listOf(prefix, targetSuffix).filter { it.isNotBlank() }.joinToString("\\"))
    }

    private fun extractPhpNamespace(element: PsiElement): String? {
        return try {
            val method = element.javaClass.methods.firstOrNull {
                it.name == "getNamespaceName" && it.parameterCount == 0 && it.returnType == String::class.java
            } ?: return null
            method.invoke(element) as? String
        } catch (_: ReflectiveOperationException) {
            null
        }
    }

    private fun normalizePhpNamespace(namespace: String): String {
        return namespace.trim().removePrefix("\\").removeSuffix("\\")
    }

    private fun loadPhpClassClass(): Class<out PsiElement> {
        @Suppress("UNCHECKED_CAST")
        return Class.forName("com.jetbrains.php.lang.psi.elements.PhpClass") as Class<out PsiElement>
    }

    private fun collectAffectedFiles(
        project: Project,
        preparation: MovePreparation,
        movedFilePointer: SmartPsiElementPointer<PsiFile>,
        fileName: String,
        modifiedFilesBeforeMove: MoveChangeSnapshot?
    ): LinkedHashSet<String> {
        val affectedFiles = linkedSetOf<String>()
        affectedFiles.add(preparation.sourceRelativePath)

        val modifiedFilesAfterMove = captureMoveChangeSnapshot(project)
        when {
            modifiedFilesBeforeMove is MoveChangeSnapshot.Unsaved && modifiedFilesAfterMove is MoveChangeSnapshot.Unsaved -> {
                affectedFiles.addAll(modifiedFilesAfterMove.paths - modifiedFilesBeforeMove.paths)
            }
        }

        val movedFile = movedFilePointer.element?.virtualFile
            ?: LocalFileSystem.getInstance().findFileByPath("${preparation.targetDirectory.virtualFile.path}/$fileName")
        if (movedFile != null) {
            affectedFiles.add(getRelativePath(project, movedFile))
        }

        return affectedFiles
    }

    private fun collectUnsavedProjectFiles(project: Project): Set<String> {
        val fileDocumentManager = FileDocumentManager.getInstance()
        return fileDocumentManager.unsavedDocuments
            .mapNotNull(fileDocumentManager::getFile)
            .filter { ProjectUtils.isProjectFile(project, it) }
            .map { getRelativePath(project, it) }
            .toSet()
    }

    private fun captureMoveChangeSnapshot(project: Project): MoveChangeSnapshot? {
        val unsavedFiles = collectUnsavedProjectFiles(project)
        return unsavedFiles.takeIf { it.isNotEmpty() }?.let { MoveChangeSnapshot.Unsaved(it) }
    }

    private fun toRelativeProjectPath(project: Project, absolutePath: String): String {
        return try {
            val virtualFile = LocalFileSystem.getInstance().findFileByPath(absolutePath)
            if (virtualFile != null) {
                ProjectUtils.getToolFilePath(project, virtualFile)
            } else {
                absolutePath.replace('\\', '/')
            }
        } catch (_: Exception) {
            absolutePath.replace('\\', '/')
        }
    }

    private fun findContainingCsproj(project: Project, sourceVirtualFile: VirtualFile): String? {
        val sourcePath = runCatching { Path.of(sourceVirtualFile.path) }.getOrNull() ?: return null
        var directory = sourcePath.parent
        while (directory != null) {
            val csprojPath = runCatching {
                Files.list(directory).use { entries ->
                    entries
                        .filter { path -> Files.isRegularFile(path) && path.fileName.toString().endsWith(".csproj", ignoreCase = true) }
                        .findFirst()
                        .orElse(null)
                }
            }.getOrNull()
            if (csprojPath != null) {
                return toRelativeProjectPath(project, csprojPath.toString())
            }
            directory = directory.parent
        }
        return null
    }

    private fun refreshAffectedFiles(paths: List<String>) {
        val virtualFiles = paths.mapNotNull { path ->
            LocalFileSystem.getInstance().refreshAndFindFileByPath(path.replace('\\', File.separatorChar))
        }
        if (virtualFiles.isNotEmpty()) {
            VfsUtil.markDirtyAndRefresh(false, false, false, *virtualFiles.toTypedArray())
        }
    }

    private fun isPhpFile(psiFile: PsiFile): Boolean {
        return psiFile.language.id == "PHP" || psiFile.viewProvider.languages.any { it.id == "PHP" }
    }

    private fun findNamedPhpDeclarations(psiFile: PsiFile): List<PsiElement> {
        val phpClassClass = try {
            loadPhpClassClass()
        } catch (_: ClassNotFoundException) {
            return emptyList()
        }

        return PsiTreeUtil.findChildrenOfType(psiFile, phpClassClass)
            .filter { it.containingFile == psiFile }
            .filter { (it as? PsiNamedElement)?.name != null }
            .sortedBy { it.textOffset }
    }

    private fun cleanupMovedPhpFileImports(movedFile: PsiFile?) {
        if (movedFile == null || !movedFile.isValid || !isPhpFile(movedFile)) {
            return
        }

        runCatching {
            OptimizeImportsProcessor(movedFile.project, movedFile).runWithoutProgress()
        }
    }
}

/**
 * Headless move processor that suppresses conflict dialogs for autonomous operation.
 *
 * Overrides [showConflicts] to always proceed (return true) instead of showing
 * a modal dialog that would block the MCP tool execution.
 */
private class HeadlessMoveProcessor(
    project: Project,
    elements: Array<PsiElement>,
    newParent: PsiDirectory,
    searchForReferences: Boolean,
    searchInComments: Boolean,
    searchInNonJavaFiles: Boolean,
    moveCallback: com.intellij.refactoring.move.MoveCallback?,
    prepareSuccessfulCallback: Runnable?
) : MoveFilesOrDirectoriesProcessor(
    project, elements, newParent, searchForReferences,
    searchInComments, searchInNonJavaFiles, moveCallback, prepareSuccessfulCallback
) {
    override fun showConflicts(conflicts: MultiMap<PsiElement, String>, usages: Array<out UsageInfo>?): Boolean {
        return true
    }
}
