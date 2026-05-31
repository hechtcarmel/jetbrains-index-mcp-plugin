package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.dotnet.RiderBackendSemanticService
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ProjectUtils
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.dotnet.MODEL_PKG
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.dotnet.RdProtocolBridge
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.MutationVerification
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.RefactoringResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import com.intellij.lang.LanguageNamesValidation
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.refactoring.RefactoringFactory
import com.intellij.refactoring.rename.RenameProcessor
import com.intellij.refactoring.rename.RenameHandler
import com.intellij.refactoring.rename.RenameHandlerRegistry
import com.intellij.refactoring.rename.PsiElementRenameHandler
import com.intellij.refactoring.rename.RenamePsiElementProcessor
import com.intellij.refactoring.rename.naming.AutomaticRenamerFactory
import com.intellij.util.containers.MultiMap
import java.awt.AWTEvent
import java.awt.Component
import java.awt.Container
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.AWTEventListener
import java.awt.event.WindowEvent
import java.io.File
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.swing.AbstractButton
import javax.swing.JDialog
import javax.swing.Timer
import javax.swing.JTextField
import javax.swing.text.JTextComponent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Universal rename tool that works across all languages supported by JetBrains IDEs.
 *
 * This tool uses IntelliJ's `RenameProcessor` which is language-agnostic and delegates
 * to language-specific `RenamePsiElementProcessor` implementations. This enables:
 * - Java/Kotlin: getter/setter renaming, overriding methods, test classes
 * - Python: function/class/variable renaming
 * - JavaScript/TypeScript: symbol renaming across files
 * - Go: function/type/variable renaming
 * - And more languages via their respective plugins
 *
 * The tool uses a two-phase approach:
 * 1. **Background Phase**: Find element and validate (read action)
 * 2. **EDT Phase**: Execute rename via RenameProcessor (handles all references)
 */
class RenameSymbolTool : AbstractMcpTool() {

    companion object {
        private val LOG = logger<RenameSymbolTool>()
        private const val STATUS_UNSUPPORTED = "unsupported"
        private const val STATUS_SUCCESS = "success"
        private const val STATUS_NO_OP = "no_op"
        private const val STATUS_FAILED = "failed"
        private const val STATUS_UNSUPPORTED_CONTEXT = "unsupported_context"
        private const val STATUS_NEEDS_ACTIVE_EDITOR = "needs_active_editor"
        private const val STATUS_CONFLICT = "conflict"
        private const val TRACE_PARENT_LIMIT = 6
        private const val TRACE_REFERENCE_LIMIT = 8
        private const val TRACE_SNIPPET_LIMIT = 80
        private const val RENAME_TRACE_FILE_NAME = "indexmcp-rename-trace.log"
        private const val RENAME_TRACE_SYSTEM_PROPERTY = "indexmcp.rename.trace"
        private const val RENAME_TRACE_ENV_VAR = "INDEXMCP_RENAME_TRACE"
        private const val RENAME_TRACE_VALUE_LIMIT = 160
        private const val RIDER_DIALOG_AUTOMATION_TIMEOUT_MS = 15_000L
        private const val RIDER_DIALOG_READINESS_TIMEOUT_MS = 10_000L
        private const val RIDER_DIALOG_READINESS_POLL_MS = 200
        private const val RIDER_DIALOG_SECOND_STAGE_TIMEOUT_MS = 5_000L
        private val EXCLUDED_SNAPSHOT_DIRS = setOf(
            "bin", "obj", "out", "target", "node_modules", "build", "dist", ".gradle"
        )
        private const val RIDER_MUTATION_VERIFICATION_TIMEOUT_MS = 2_000L
        private const val RIDER_MUTATION_VERIFICATION_INITIAL_POLL_MS = 50L
        private const val RIDER_MUTATION_VERIFICATION_MAX_POLL_MS = 400L

        internal data class RenameTargetResolution(
            val candidate: PsiElement?,
            val candidateName: String?,
            val selectedReason: String,
            val leafDescription: String,
            val parentChainDescription: String,
            val referenceDescription: String
        )

        internal data class PreferredRiderActionLanePlan(
            val shouldInvoke: Boolean,
            val reason: String
        )

        internal data class RefactoringFactoryFallbackPlan(
            val shouldInvoke: Boolean,
            val reason: String
        )

        private enum class RiderRenameExecutionLane {
            BLOCKED,
            HIGH_LEVEL_HANDLER,
            // RefactoringFactory.createRename(...) remains a second choice lane and is currently refused unless a safe non-modal proof exists.
        }

        private data class RiderFrontendEditorLookup(
            val editor: Editor?,
            val reason: String,
            val virtualFile: VirtualFile?,
            val openedByTool: Boolean
        )

        private data class RiderDeterministicDataContext(
            val availableKeys: List<String>,
            val dataContext: DataContext?
        )

        internal data class RiderFrontendFeasibility(
            val canProceed: Boolean,
            val reason: String
        )

        private data class RiderFrontendPolicyEvaluation(
            val shouldInvoke: Boolean,
            val reason: String
        )

        private data class RiderRenameExecutionPlan(
            val availableHandlerTitles: List<String>,
            val selectedHandler: RenameHandler?,
            val selectedHandlerClassName: String?,
            val selectedHandlerTitle: String?,
            val editorLookup: RiderFrontendEditorLookup,
            val dataContext: RiderDeterministicDataContext,
            val policy: RiderFrontendPolicyEvaluation,
            val lane: RiderRenameExecutionLane,
            val blockReason: String?
        )

        internal data class FrontendRenameFailureClassification(
            val status: String?,
            val userMessage: String
        )

        internal data class RiderRenameFollowUpDialogPlan(
            val action: String,
            val submitButtonLabel: String?,
            val shouldCompleteAutomationAfterSubmit: Boolean,
            val shouldDisableRelatedSymbols: Boolean,
            val requiresRenameTextInput: Boolean,
            val failureReason: String?
        )

        internal data class RiderSecondStageTimeoutDecision(
            val shouldCompleteSuccess: Boolean,
            val failureReason: String?
        )

        internal data class RiderAutomationTimeoutDecision(
            val shouldDeferToMutationVerification: Boolean,
            val shouldFail: Boolean,
            val traceStatus: String,
            val failureReason: String?
        )

        internal data class RiderPrimaryDialogSubmitPlan(
            val shouldClick: Boolean,
            val shouldAwaitSecondDialog: Boolean,
            val failureReason: String?
        )

        internal enum class RelatedSymbolsDisableFailureKind {
            NONE,
            NO_SAFE_TOGGLE,
            ACTIONABLE_CONTROL_REMAINED_SELECTED,
            UNKNOWN
        }

        internal data class RelatedSymbolsDisableOutcome(
            val attempted: Boolean,
            val succeeded: Boolean,
            val method: String,
            val changedCount: Int,
            val failureKind: RelatedSymbolsDisableFailureKind,
            val failureReason: String?
        )

        internal data class FrontendRenameMutationCheck(
            val verified: Boolean,
            val reason: String,
            val observedFilePath: String? = null
        )

        internal data class FrontendRenamePathEvidence(
            val originalFilePath: String?,
            val originalPathExists: Boolean,
            val observedFilePath: String?,
            val candidatePaths: List<String> = emptyList()
        )

        internal fun frontendRenamePathEvidence(
            originalFilePath: String?,
            originalPathExists: Boolean,
            observedFilePath: String?,
            candidatePaths: List<String> = emptyList()
        ): FrontendRenamePathEvidence = FrontendRenamePathEvidence(
            originalFilePath = originalFilePath,
            originalPathExists = originalPathExists,
            observedFilePath = observedFilePath,
            candidatePaths = candidatePaths
        )

        internal data class FrontendRenameMutationPollResult(
            val check: FrontendRenameMutationCheck,
            val attemptCount: Int,
            val totalWaitMs: Long
        )

        private data class RenameCandidateInspection(
            val name: String?,
            val identifierRange: TextRange?,
            val isPsiNamedElement: Boolean,
            val isNameIdentifierOwner: Boolean,
            val canProcessElement: Boolean?,
            val processorClassName: String?,
            val containerLike: Boolean,
            val declarationLike: Boolean,
            val traceDescription: String
        )

        @Suppress("UNUSED_PARAMETER")
        internal fun shouldUseRiderFrontendRenameAutomation(
            file: String,
            isFileRename: Boolean,
            relatedRenamingStrategy: String
        ): Boolean {
            return !isFileRename &&
                RiderBackendSemanticService.isDotNetFile(file)
        }

        internal fun planRiderPrimaryDialogSubmit(
            buttonText: String?,
            relatedRenamingStrategy: String,
            relatedDisableSucceeded: Boolean,
            relatedDisableFailureKind: RelatedSymbolsDisableFailureKind,
            relatedDisableFailureReason: String?
        ): RiderPrimaryDialogSubmitPlan {
            val shouldAwaitSecondDialog = shouldAwaitSecondDialogAfterPrimarySubmit(buttonText)
            if (shouldAwaitSecondDialog && relatedRenamingStrategy == "none" && !relatedDisableSucceeded &&
                !shouldDeferPrimaryRelatedDisableFailureToFollowUp(relatedDisableFailureKind)) {
                return RiderPrimaryDialogSubmitPlan(
                    shouldClick = false,
                    shouldAwaitSecondDialog = true,
                    failureReason = relatedDisableFailureReason
                        ?: "Rider rename dialog could not disable related-symbol renames safely before continuing."
                )
            }
            return RiderPrimaryDialogSubmitPlan(
                shouldClick = true,
                shouldAwaitSecondDialog = shouldAwaitSecondDialog,
                failureReason = null
            )
        }

        internal fun shouldDeferPrimaryRelatedDisableFailureToFollowUp(
            relatedDisableFailureKind: RelatedSymbolsDisableFailureKind
        ): Boolean {
            return relatedDisableFailureKind == RelatedSymbolsDisableFailureKind.NO_SAFE_TOGGLE
        }

        internal fun shouldDisableRelatedSymbolsCheckbox(text: String?): Boolean {
            val normalized = text?.trim()?.lowercase() ?: return false
            if (normalized.isBlank()) {
                return false
            }
            return normalized.contains("related symbols")
        }

        internal fun shouldTreatRenameNextFollowUpAsRelatedSymbolsDialog(
            title: String?,
            visibleTexts: List<String>,
            visibleButtonLabels: List<String>,
            relatedRenamingStrategy: String
        ): Boolean {
            if (relatedRenamingStrategy != "none" || !isSecondDialogCandidateTitle(title)) {
                return false
            }
            val normalizedTexts = visibleTexts.mapNotNull { it.trim().takeIf(String::isNotBlank) }
            val normalizedButtons = visibleButtonLabels.mapNotNull { it.trim().takeIf(String::isNotBlank) }
            val combinedText = normalizedTexts.joinToString("\n")
            val hasBackButton = normalizedButtons.any { it.equals("Back", ignoreCase = true) }
            val hasNextButton = normalizedButtons.any { it.equals("Next", ignoreCase = true) }
            return combinedText.contains(
                "There are declarations that seem to have names related to the name of the type to be renamed",
                ignoreCase = true
            ) || normalizedTexts.any(::shouldDisableRelatedSymbolsCheckbox) ||
                (title?.trim()?.equals("Rename", ignoreCase = true) == true && hasNextButton && hasBackButton)
        }

        internal fun shouldAwaitSecondDialogAfterPrimarySubmit(buttonText: String?): Boolean {
            return buttonText?.trim()?.equals("Next", ignoreCase = true) == true
        }

        internal fun isSecondDialogCandidateTitle(title: String?): Boolean {
            val normalized = title?.trim().orEmpty()
            if (normalized.isBlank()) {
                return false
            }
            return normalized.equals("Rename", ignoreCase = true) ||
                normalized.contains("Refactor", ignoreCase = true) ||
                normalized.contains("Preview", ignoreCase = true) ||
                normalized.contains("Conflict", ignoreCase = true)
        }

        internal fun isTerminalRiderFollowUpSubmitButton(buttonText: String?): Boolean {
            return when (buttonText?.trim()?.lowercase()) {
                "do refactor", "refactor", "finish", "rename", "continue", "ok" -> true
                else -> false
            }
        }

        internal fun riderFrontendMutationCheckDelayMs(usedAsyncFollowUpClick: Boolean): Long {
            return if (usedAsyncFollowUpClick) RIDER_DIALOG_READINESS_POLL_MS.toLong() else 0L
        }

        internal fun planRiderRenameFollowUpDialog(
            title: String?,
            visibleTexts: List<String>,
            visibleButtonLabels: List<String>,
            relatedRenamingStrategy: String
        ): RiderRenameFollowUpDialogPlan {
            if (!isSecondDialogCandidateTitle(title)) {
                return RiderRenameFollowUpDialogPlan(
                    action = "ignore",
                    submitButtonLabel = null,
                    shouldCompleteAutomationAfterSubmit = false,
                    shouldDisableRelatedSymbols = false,
                    requiresRenameTextInput = false,
                    failureReason = null
                )
            }

            val normalizedTexts = visibleTexts.mapNotNull { it.trim().takeIf(String::isNotBlank) }
            val normalizedButtons = visibleButtonLabels.mapNotNull { it.trim().takeIf(String::isNotBlank) }
            val combinedText = normalizedTexts.joinToString("\n")

            if (combinedText.contains("operation is not supported", ignoreCase = true)) {
                return RiderRenameFollowUpDialogPlan(
                    action = "fail_unsupported",
                    submitButtonLabel = null,
                    shouldCompleteAutomationAfterSubmit = false,
                    shouldDisableRelatedSymbols = false,
                    requiresRenameTextInput = false,
                    failureReason = "Rename: Operation is not supported"
                )
            }

            val relatedSymbolsDialog = shouldTreatRenameNextFollowUpAsRelatedSymbolsDialog(
                title = title,
                visibleTexts = normalizedTexts,
                visibleButtonLabels = normalizedButtons,
                relatedRenamingStrategy = relatedRenamingStrategy
            )

            val submitButtonLabel = when {
                relatedSymbolsDialog -> normalizedButtons.firstOrNull {
                    it.equals("Next", ignoreCase = true) ||
                        it.equals("Refactor", ignoreCase = true) ||
                        it.equals("Rename", ignoreCase = true)
                }

                else -> normalizedButtons.firstOrNull {
                    it.equals("Do Refactor", ignoreCase = true) ||
                        it.equals("Refactor", ignoreCase = true) ||
                        it.equals("Continue", ignoreCase = true) ||
                        it.equals("Next", ignoreCase = true) ||
                        it.equals("Rename", ignoreCase = true) ||
                        it.equals("OK", ignoreCase = true)
                }
            }

            if (submitButtonLabel == null) {
                return RiderRenameFollowUpDialogPlan(
                    action = "ignore",
                    submitButtonLabel = null,
                    shouldCompleteAutomationAfterSubmit = false,
                    shouldDisableRelatedSymbols = false,
                    requiresRenameTextInput = false,
                    failureReason = null
                )
            }

            return RiderRenameFollowUpDialogPlan(
                action = "continue",
                submitButtonLabel = submitButtonLabel,
                shouldCompleteAutomationAfterSubmit = isTerminalRiderFollowUpSubmitButton(submitButtonLabel) &&
                    !submitButtonLabel.equals("Next", ignoreCase = true),
                shouldDisableRelatedSymbols = relatedSymbolsDialog && relatedRenamingStrategy == "none",
                requiresRenameTextInput = false,
                failureReason = null
            )
        }

        internal fun evaluateRiderSecondStageTimeout(visibleDialogTitles: List<String>): RiderSecondStageTimeoutDecision {
            val visibleCandidateTitles = visibleDialogTitles
                .map { it.trim() }
                .filter(::isSecondDialogCandidateTitle)

            if (visibleCandidateTitles.isNotEmpty()) {
                return RiderSecondStageTimeoutDecision(
                    shouldCompleteSuccess = false,
                    failureReason = "Follow-up Rider rename dialog remained visible after waiting ${RIDER_DIALOG_SECOND_STAGE_TIMEOUT_MS}ms: ${visibleCandidateTitles.joinToString(", ")}."
                )
            }

            return RiderSecondStageTimeoutDecision(
                shouldCompleteSuccess = true,
                failureReason = null
            )
        }

        internal fun evaluateRiderAutomationTimeout(
            waitingForSecondDialog: Boolean,
            usedAsyncFollowUpClick: Boolean,
            visibleDialogTitles: List<String>
        ): RiderAutomationTimeoutDecision {
            if (usedAsyncFollowUpClick) {
                val secondStageDecision = evaluateRiderSecondStageTimeout(visibleDialogTitles)
                if (secondStageDecision.shouldCompleteSuccess) {
                    return RiderAutomationTimeoutDecision(
                        shouldDeferToMutationVerification = true,
                        shouldFail = false,
                        traceStatus = "defer-to-mutation-check",
                        failureReason = null
                    )
                }

                return RiderAutomationTimeoutDecision(
                    shouldDeferToMutationVerification = false,
                    shouldFail = true,
                    traceStatus = "timeout-visible-dialog",
                    failureReason = secondStageDecision.failureReason
                )
            }

            return RiderAutomationTimeoutDecision(
                shouldDeferToMutationVerification = false,
                shouldFail = true,
                traceStatus = "timeout",
                failureReason = "Rider rename dialog automation timed out after ${RIDER_DIALOG_AUTOMATION_TIMEOUT_MS}ms"
            )
        }

        internal fun attemptRequiredRelatedSymbolsDisable(container: Container): RelatedSymbolsDisableOutcome {
            fun walk(container: Container, consumer: (Component) -> Unit) {
                for (component in container.components) {
                    consumer(component)
                    if (component is Container) {
                        walk(component, consumer)
                    }
                }
            }

            fun describe(component: Component): List<String> {
                val texts = mutableListOf<String>()
                when (component) {
                    is AbstractButton -> {
                        component.text?.trim()?.takeIf { it.isNotEmpty() }?.let(texts::add)
                        component.actionCommand?.trim()?.takeIf { it.isNotEmpty() }?.let(texts::add)
                    }

                    is javax.swing.JLabel -> component.text?.trim()?.takeIf { it.isNotEmpty() }?.let(texts::add)
                }
                component.accessibleContext?.accessibleName?.trim()?.takeIf { it.isNotEmpty() }?.let(texts::add)
                component.accessibleContext?.accessibleDescription?.trim()?.takeIf { it.isNotEmpty() }?.let(texts::add)
                return texts.distinct()
            }

            fun tryDisable(button: AbstractButton, method: String): RelatedSymbolsDisableOutcome? {
                if (!button.isShowing || !button.isEnabled || !button.isSelected || !describe(button).any(::shouldDisableRelatedSymbolsCheckbox)) {
                    return null
                }
                button.doClick()
                return if (!button.isSelected) {
                    RelatedSymbolsDisableOutcome(true, true, method, 1, RelatedSymbolsDisableFailureKind.NONE, null)
                } else {
                    RelatedSymbolsDisableOutcome(
                        attempted = true,
                        succeeded = false,
                        method = method,
                        changedCount = 0,
                        failureKind = RelatedSymbolsDisableFailureKind.ACTIONABLE_CONTROL_REMAINED_SELECTED,
                        failureReason = "Rider rename dialog kept the related-symbol toggle selected after attempting to disable it."
                    )
                }
            }

            var resolvedOutcome: RelatedSymbolsDisableOutcome? = null
            walk(container) { component ->
                if (resolvedOutcome != null) {
                    return@walk
                }
                val button = component as? AbstractButton ?: return@walk
                val outcome = tryDisable(button, if (button.text.isNullOrBlank()) "accessible-button" else "checkbox")
                if (outcome != null) {
                    resolvedOutcome = outcome
                }
            }
            resolvedOutcome?.let { return it }

            walk(container) { component ->
                if (resolvedOutcome != null) {
                    return@walk
                }
                val label = component as? javax.swing.JLabel ?: return@walk
                if (!label.isShowing || !shouldDisableRelatedSymbolsCheckbox(label.text)) {
                    return@walk
                }
                val labeledButton = label.labelFor as? AbstractButton ?: return@walk
                val outcome = tryDisable(labeledButton, "label-for-button")
                if (outcome != null) {
                    resolvedOutcome = outcome
                }
            }
            resolvedOutcome?.let { return it }

            return RelatedSymbolsDisableOutcome(
                attempted = true,
                succeeded = false,
                method = "no-safe-toggle",
                changedCount = 0,
                failureKind = RelatedSymbolsDisableFailureKind.NO_SAFE_TOGGLE,
                failureReason = "Rider rename dialog could not disable related-symbol renames with a verified safe control, so the request failed closed before submitting the follow-up step."
            )
        }

        internal fun isUnsupportedRiderFrontendFallbackTargetClass(className: String?): Boolean {
            val normalized = className?.trim()?.lowercase() ?: return true
            return normalized.contains("directory") ||
                normalized.contains("package") ||
                normalized.contains("namespace") ||
                normalized.contains("folder")
        }

        internal fun summarizeFrontendRenameResult(
            oldName: String,
            newName: String,
            relatedRenamesCount: Int,
            affectedFiles: List<String>,
            changesCount: Int,
            riderFallbackStatus: String? = null,
            mutationCheck: FrontendRenameMutationCheck? = null
        ): RefactoringResult {
            val normalizedFiles = affectedFiles.distinct()
            val normalizedChangesCount = changesCount.coerceAtLeast(0)
            val cameFromRiderFallback = !riderFallbackStatus.isNullOrBlank()
            val hasObservableChanges = normalizedChangesCount > 0 || normalizedFiles.isNotEmpty()

            if (cameFromRiderFallback && !hasObservableChanges) {
                return RefactoringResult(
                    success = false,
                    affectedFiles = emptyList(),
                    changesCount = 0,
                    message = "Rider frontend rename did not apply any observable rename for '$oldName' (status='$riderFallbackStatus'), so the request failed closed.",
                    status = STATUS_UNSUPPORTED_CONTEXT
                )
            }

            if (cameFromRiderFallback && hasObservableChanges && mutationCheck?.verified == false) {
                return RefactoringResult(
                    success = false,
                    affectedFiles = emptyList(),
                    changesCount = 0,
                    message = "Rider frontend rename reported rename changes for '$oldName', but no real source mutation was verified: ${mutationCheck.reason}.",
                    status = STATUS_NO_OP
                )
            }

            if (cameFromRiderFallback && hasObservableChanges && mutationCheck == null) {
                return RefactoringResult(
                    success = false,
                    affectedFiles = normalizedFiles,
                    changesCount = normalizedChangesCount,
                    message = "Rider frontend rename reported rename changes for '$oldName', but mutation proof could not be collected for verification.",
                    status = STATUS_FAILED,
                    verification = MutationVerification(
                        status = STATUS_FAILED,
                        checksRun = listOf("rename_execution", "mutation_proof"),
                        warnings = listOf("Mutation proof could not be collected after Rider frontend rename execution")
                    )
                )
            }

            val relatedNote = if (relatedRenamesCount > 0) {
                " (also renamed $relatedRenamesCount related element(s))"
            } else {
                ""
            }

            return RefactoringResult(
                success = true,
                affectedFiles = normalizedFiles,
                changesCount = normalizedChangesCount,
                message = "Successfully renamed '$oldName' to '$newName'$relatedNote"
            )
        }

        internal fun evaluatePreferredRiderActionLane(
            availableHandlerTitles: List<String>,
            selectedHandlerClassName: String?,
            selectedHandlerIsKnownSafeNonModal: Boolean,
            selectedHandlerBlockReason: String?
        ): PreferredRiderActionLanePlan {
            if (availableHandlerTitles.isEmpty()) {
                return PreferredRiderActionLanePlan(false, "no rename handler available")
            }
            if (availableHandlerTitles.size > 1) {
                return PreferredRiderActionLanePlan(false, "multiple rename handlers would require chooser UI")
            }
            if (selectedHandlerClassName.isNullOrBlank()) {
                return PreferredRiderActionLanePlan(false, "rename handler selection did not resolve a concrete handler")
            }
            if (!selectedHandlerIsKnownSafeNonModal) {
                return PreferredRiderActionLanePlan(
                    false,
                    selectedHandlerBlockReason ?: "selected rename handler is not a proven non-modal production lane"
                )
            }
            return PreferredRiderActionLanePlan(true, "single deterministic non-modal rename handler is available")
        }

        internal fun assessPreferredRiderRenameHandlerClassName(selectedHandlerClassName: String?): PreferredRiderActionLanePlan {
            return when (selectedHandlerClassName) {
                null, "" -> PreferredRiderActionLanePlan(
                    shouldInvoke = false,
                    reason = "rename handler selection did not resolve a concrete handler"
                )

                "com.jetbrains.rdclient.actions.impl.BackendRenameHandler" -> PreferredRiderActionLanePlan(
                    shouldInvoke = true,
                    reason = "selected rename handler is on the proven Rider non-modal allowlist"
                )

                PsiElementRenameHandler::class.java.name -> PreferredRiderActionLanePlan(
                    shouldInvoke = false,
                    reason = "selected rename handler would show modal UI outside unit test mode"
                )

                else -> PreferredRiderActionLanePlan(
                    shouldInvoke = false,
                    reason = "selected rename handler is not on the proven non-modal allowlist: $selectedHandlerClassName"
                )
            }
        }

        internal fun evaluateSecondChoiceRiderRefactoringFactoryLane(
            hasDeclarationEditor: Boolean,
            canComposeDataContext: Boolean,
            preferredLaneWasDeterministic: Boolean
        ): RefactoringFactoryFallbackPlan {
            val apiName = "${RefactoringFactory::class.java.simpleName}.createRename(...)"
            if (!hasDeclarationEditor || !canComposeDataContext) {
                return RefactoringFactoryFallbackPlan(
                    shouldInvoke = false,
                    reason = "$apiName second choice still requires the same editor/data-context preconditions"
                )
            }

            if (preferredLaneWasDeterministic) {
                return RefactoringFactoryFallbackPlan(
                    shouldInvoke = false,
                    reason = "$apiName second choice is unnecessary when the preferred handler lane is deterministic"
                )
            }

            return RefactoringFactoryFallbackPlan(
                shouldInvoke = false,
                reason = "$apiName second choice is refused because this path still lacks a proven non-modal execution contract for Rider symbol rename"
            )
        }

        internal fun classifyFrontendRenameFailure(
            message: String?,
            exceptionClassName: String?,
            riderFallbackStatus: String?
        ): FrontendRenameFailureClassification {
            val normalizedMessage = message?.trim().orEmpty().ifBlank { "Unknown error during rename" }
            val normalizedExceptionClass = exceptionClassName?.trim().orEmpty()
            val unsupportedSignal = normalizedMessage.contains("not supported", ignoreCase = true) ||
                normalizedMessage.contains("unsupported", ignoreCase = true) ||
                normalizedExceptionClass.endsWith("UnsupportedOperationException")

            if (!riderFallbackStatus.isNullOrBlank() && unsupportedSignal) {
                return FrontendRenameFailureClassification(
                    status = STATUS_UNSUPPORTED_CONTEXT,
                    userMessage = "Rider frontend fallback reported an unsupported rename target: $normalizedMessage"
                )
            }

            return FrontendRenameFailureClassification(
                status = null,
                userMessage = "Rename failed: $normalizedMessage"
            )
        }

        internal fun buildBlockedRiderFrontendFallbackResult(
            oldName: String,
            actionReason: String
        ): RefactoringResult {
            val normalizedReason = actionReason.trim().lowercase()
            val mappedStatus = when {
                normalizedReason.contains("active editor") -> STATUS_NEEDS_ACTIVE_EDITOR
                normalizedReason.contains("chooser ui") ||
                    normalizedReason.contains("modal ui") ||
                    normalizedReason.contains("preview") ||
                    normalizedReason.contains("user interaction") ||
                    normalizedReason.contains("conflict") -> STATUS_CONFLICT
                else -> STATUS_UNSUPPORTED_CONTEXT
            }
            return RefactoringResult(
                success = false,
                affectedFiles = emptyList(),
                changesCount = 0,
                message = "Rider frontend fallback was blocked for '$oldName': $actionReason.",
                status = mappedStatus
            )
        }

        internal fun verifyRiderFrontendMutation(
            beforeName: String?,
            afterName: String?,
            newName: String,
            beforeFileText: String?,
            afterFileText: String?,
            pathEvidence: FrontendRenamePathEvidence? = null
        ): FrontendRenameMutationCheck {
            // The oracle must confirm that the REQUESTED rename happened, not merely that
            // "something changed". A name change to an unrelated identifier, or an arbitrary
            // text edit, must NOT be treated as proof of the requested rename.
            val requestedName = newName.trim().substringAfterLast('/').substringAfterLast('\\')

            // Primary signal: the resolved target now carries the requested name.
            if (!afterName.isNullOrBlank()) {
                return if (afterName == requestedName) {
                    FrontendRenameMutationCheck(true, "target name is now '$afterName' matching the requested rename")
                } else {
                    // We resolved a concrete name and it is NOT the requested one -> fail closed.
                    FrontendRenameMutationCheck(
                        false,
                        "target name '$afterName' does not match the requested rename '$requestedName'"
                    )
                }
            }

            // afterName unavailable (smart pointer invalidated). This is typical for C# type
            // renames that also rename the containing file: trust path evidence that the
            // container moved to the renamed location.
            if (pathEvidence != null && !pathEvidence.originalPathExists && !pathEvidence.observedFilePath.isNullOrBlank()) {
                return FrontendRenameMutationCheck(
                    verified = true,
                    reason = "original file path disappeared and renamed container file was observed at '${pathEvidence.observedFilePath}'",
                    observedFilePath = pathEvidence.observedFilePath
                )
            }

            // Last-resort textual fallback, used ONLY when the resolved name is unavailable and
            // the file did not move: require the requested identifier to now be present and the
            // previous identifier to be gone. This is far stricter than "any text changed".
            if (afterFileText != null &&
                afterFileText != beforeFileText &&
                afterFileText.contains(requestedName) &&
                !beforeName.isNullOrBlank() &&
                !afterFileText.contains(beforeName)
            ) {
                return FrontendRenameMutationCheck(
                    true,
                    "resolved name unavailable but file now contains '$requestedName' and no longer contains '$beforeName'"
                )
            }

            val missingPathReason = pathEvidence
                ?.takeIf { !it.originalPathExists && it.originalFilePath != null }
                ?.let { evidence ->
                    "original file path '${evidence.originalFilePath}' disappeared but no renamed container file evidence was found"
                }

            return FrontendRenameMutationCheck(
                false,
                missingPathReason ?: "could not verify the requested rename to '$requestedName'"
            )
        }

        internal fun collectRiderFrontendMutationPathEvidence(
            project: Project,
            targetFilePath: String?,
            newName: String,
            afterElement: PsiElement?
        ): FrontendRenamePathEvidence? {
            if (targetFilePath.isNullOrBlank()) {
                return null
            }

            val localFileSystem = LocalFileSystem.getInstance()
            val originalVirtualFile = localFileSystem.findFileByPath(targetFilePath)
            val originalPathExists = originalVirtualFile != null
            val candidatePaths = linkedSetOf<String>()

            afterElement?.containingFile?.virtualFile?.path
                ?.takeIf { it != targetFilePath }
                ?.let(candidatePaths::add)

            val inferredSiblingPath = inferRenamedSiblingPath(targetFilePath, newName)
            if (!inferredSiblingPath.isNullOrBlank()) {
                localFileSystem.findFileByPath(inferredSiblingPath)?.path?.let(candidatePaths::add)
            }

            if (!originalPathExists) {
                findSiblingRenameCandidates(localFileSystem, targetFilePath, newName)
                    .mapTo(candidatePaths) { it.path }

                val inferredFileName = inferredSiblingPath?.let(::fileNameFromPath)
                if (!inferredFileName.isNullOrBlank()) {
                    FilenameIndex.getVirtualFilesByName(project, inferredFileName, GlobalSearchScope.projectScope(project))
                        .asSequence()
                        .map { it.path }
                        .filter { it != targetFilePath }
                        .forEach(candidatePaths::add)
                }
            }

            return FrontendRenamePathEvidence(
                originalFilePath = targetFilePath,
                originalPathExists = originalPathExists,
                observedFilePath = candidatePaths.firstOrNull(),
                candidatePaths = candidatePaths.toList()
            )
        }

        private fun inferRenamedSiblingPath(targetFilePath: String, newName: String): String? {
            val originalFileName = fileNameFromPath(targetFilePath) ?: return null
            val originalExtension = originalFileName.substringAfterLast('.', "")
            val normalizedNewName = newName.trim().substringAfterLast('/').substringAfterLast('\\')
            if (normalizedNewName.isBlank()) {
                return null
            }
            val expectedFileName = if (originalExtension.isNotBlank() && !normalizedNewName.endsWith(".$originalExtension")) {
                "$normalizedNewName.$originalExtension"
            } else {
                normalizedNewName
            }
            val parentPath = targetFilePath.replace('\\', '/').substringBeforeLast('/', "")
            return if (parentPath.isBlank()) expectedFileName else "$parentPath/$expectedFileName"
        }

        private fun fileNameFromPath(path: String): String? {
            val normalized = path.replace('\\', '/')
            return normalized.substringAfterLast('/', "").takeIf { it.isNotBlank() }
        }

        private fun findSiblingRenameCandidates(
            localFileSystem: LocalFileSystem,
            targetFilePath: String,
            newName: String
        ): List<VirtualFile> {
            val normalizedTargetPath = targetFilePath.replace('\\', '/')
            val parentPath = normalizedTargetPath.substringBeforeLast('/', "")
            if (parentPath.isBlank()) {
                return emptyList()
            }

            val parent = localFileSystem.findFileByPath(parentPath) ?: return emptyList()
            val targetFileName = fileNameFromPath(normalizedTargetPath) ?: return emptyList()
            val extension = targetFileName.substringAfterLast('.', "")
            val expectedBaseName = newName.trim().substringAfterLast('/').substringAfterLast('\\')
            if (expectedBaseName.isBlank()) {
                return emptyList()
            }

            return parent.children
                .asSequence()
                .filter { !it.isDirectory }
                .filter { it.path != normalizedTargetPath }
                .filter { child ->
                    child.nameWithoutExtension == expectedBaseName &&
                        (extension.isBlank() || child.extension == extension)
                }
                .toList()
        }

        internal suspend fun pollRiderFrontendMutationVerification(
            initialDelayMs: Long,
            timeoutMs: Long = RIDER_MUTATION_VERIFICATION_TIMEOUT_MS,
            initialPollIntervalMs: Long = RIDER_MUTATION_VERIFICATION_INITIAL_POLL_MS,
            maxPollIntervalMs: Long = RIDER_MUTATION_VERIFICATION_MAX_POLL_MS,
            sleep: (Long) -> Unit = Thread::sleep,
            verify: suspend () -> FrontendRenameMutationCheck
        ): FrontendRenameMutationPollResult {
            var totalWaitMs = 0L
            var attemptCount = 0
            var nextPollIntervalMs = initialPollIntervalMs.coerceAtLeast(1L)

            if (initialDelayMs > 0) {
                sleep(initialDelayMs)
                totalWaitMs += initialDelayMs
            }

            var check = verify()
            attemptCount += 1
            while (!check.verified && totalWaitMs < timeoutMs) {
                val remainingMs = timeoutMs - totalWaitMs
                val waitMs = nextPollIntervalMs.coerceAtMost(maxPollIntervalMs).coerceAtMost(remainingMs)
                if (waitMs <= 0L) {
                    break
                }
                sleep(waitMs)
                totalWaitMs += waitMs
                check = verify()
                attemptCount += 1
                nextPollIntervalMs = (nextPollIntervalMs * 2).coerceAtMost(maxPollIntervalMs)
            }

            return FrontendRenameMutationPollResult(
                check = check,
                attemptCount = attemptCount,
                totalWaitMs = totalWaitMs
            )
        }

        internal fun verifyDotNetFileRenameDeclaredTypeIdentity(
            beforeFileText: String?,
            afterFileText: String?
        ): MutationVerification? {
            val declaredTypeNamesBefore = extractDotNetDeclaredTypeNames(beforeFileText)
            val declaredTypeNamesAfter = extractDotNetDeclaredTypeNames(afterFileText)

            if (declaredTypeNamesBefore == declaredTypeNamesAfter) {
                return null
            }

            return MutationVerification(
                status = STATUS_FAILED,
                checksRun = listOf("rename_execution", "declared_type_identity"),
                warnings = listOf(
                    "Declared type identity changed during .NET file rename: before [${declaredTypeNamesBefore.joinToString(", ").ifBlank { "<none>" }}], after [${declaredTypeNamesAfter.joinToString(", ").ifBlank { "<none>" }}]"
                )
            )
        }

        private fun extractDotNetDeclaredTypeNames(fileText: String?): List<String> {
            if (fileText.isNullOrBlank()) {
                return emptyList()
            }

            val declarationPattern = Regex(
                """\b(?:class|struct|interface|enum|delegate|record(?:\s+class|\s+struct)?)\s+(@?[A-Za-z_][A-Za-z0-9_]*)"""
            )

            return declarationPattern.findAll(fileText)
                .map { match -> match.groupValues[1].trim().trimStart('@') }
                .filter { it.isNotBlank() }
                .distinct()
                .sorted()
                .toList()
        }

        internal fun evaluateRiderFrontendFeasibility(
            hasDeclarationEditor: Boolean,
            canComposeDataContext: Boolean
        ): RiderFrontendFeasibility {
            return when {
                !hasDeclarationEditor -> RiderFrontendFeasibility(
                    canProceed = false,
                    reason = "active editor is required for Rider rename lane"
                )

                !canComposeDataContext -> RiderFrontendFeasibility(
                    canProceed = false,
                    reason = "deterministic data context could not be composed for Rider rename lane"
                )

                else -> RiderFrontendFeasibility(
                    canProceed = true,
                    reason = "declaration editor and deterministic data context are available"
                )
            }
        }

        internal fun resolveNamedElementCandidateForRename(element: PsiElement): PsiNamedElement? {
            return inspectNamedElementCandidateForRename(element).candidate as? PsiNamedElement
        }

        internal fun inspectNamedElementCandidateForRename(
            element: PsiElement,
            requestedOffset: Int? = null,
            processorSupportOverride: ((PsiElement) -> Boolean)? = null
        ): RenameTargetResolution {
            var current: PsiElement? = element
            var depth = 0
            val parentChain = mutableListOf<String>()
            val referenceAttempts = mutableListOf<String>()
            var localCandidate: PsiElement? = null
            var localCandidateName: String? = null
            var localCandidateReason: String? = null
            var localCandidateDirectlyUnderCursor = false
            var referenceCandidate: PsiElement? = null
            var referenceCandidateName: String? = null
            var referenceCandidateReason: String? = null

            while (current != null) {
                val currentInspection = inspectRenameCandidateSupport(current, processorSupportOverride)
                if (depth < TRACE_PARENT_LIMIT) {
                    parentChain += "d=$depth ${currentInspection.traceDescription}"
                }

                val currentRange = runCatching { current.textRange }.getOrNull()
                val enclosesOffset = requestedOffset == null || currentRange?.containsOffset(requestedOffset) == true

                val currentSupported = current !is PsiFile &&
                    !currentInspection.name.isNullOrBlank() &&
                    !currentInspection.containerLike &&
                    enclosesOffset &&
                    (
                        currentInspection.isPsiNamedElement ||
                            (currentInspection.declarationLike && currentInspection.canProcessElement == true)
                        )

                if (currentSupported && localCandidate == null) {
                    localCandidate = current
                    localCandidateName = currentInspection.name
                    localCandidateDirectlyUnderCursor = requestedOffset != null &&
                        currentInspection.identifierRange?.containsOffset(requestedOffset) == true
                    localCandidateReason = if (currentInspection.isPsiNamedElement) {
                        "selected enclosing named element at depth=$depth"
                    } else {
                        "selected processable declaration-like element at depth=$depth"
                    }
                } else if (currentInspection.declarationLike) {
                    when {
                        currentInspection.containerLike -> {
                            parentChain += "d=$depth rejected=container-like"
                        }
                        !enclosesOffset -> {
                            parentChain += "d=$depth rejected=offset-outside"
                        }
                        currentInspection.name.isNullOrBlank() -> {
                            parentChain += "d=$depth rejected=no-name"
                        }
                        !currentInspection.isPsiNamedElement && currentInspection.canProcessElement != true -> {
                            parentChain += "d=$depth rejected=processor-unsupported"
                        }
                    }
                }

                current.references.forEachIndexed { index, reference ->
                    if (referenceAttempts.size >= TRACE_REFERENCE_LIMIT) return@forEachIndexed

                    val referenceRange = runCatching {
                        reference.rangeInElement.shiftRight(currentRange?.startOffset ?: 0)
                    }.getOrNull()
                    val matchesOffset = requestedOffset == null || referenceRange?.containsOffset(requestedOffset) == true
                    val resolved = runCatching { reference.resolve() }.getOrNull()
                    val resolvedElement = resolved as? PsiElement
                    val resolvedInspection = resolvedElement?.let {
                        inspectRenameCandidateSupport(it, processorSupportOverride)
                    }
                    val resolvedSupported = resolvedElement != null &&
                        resolvedElement !is PsiFile &&
                        !resolvedInspection?.name.isNullOrBlank() &&
                        resolvedInspection?.containerLike == false &&
                        (
                            resolvedInspection?.isPsiNamedElement == true ||
                                (resolvedInspection?.declarationLike == true && resolvedInspection.canProcessElement == true)
                            )

                    referenceAttempts += buildString {
                        append("d=")
                        append(depth)
                        append(" ref#=")
                        append(index)
                        append(" range=")
                        append(describeRange(referenceRange))
                        append(" matchesOffset=")
                        append(matchesOffset)
                        append(" resolved=")
                        append(describeElementForTraceValue(resolved))
                        if (resolvedInspection != null) {
                            append(" support={")
                            append(resolvedInspection.traceDescription)
                            append("}")
                        }
                    }

                    if (referenceCandidate == null && matchesOffset && resolvedSupported) {
                        referenceCandidate = resolvedElement
                        referenceCandidateName = resolvedInspection?.name
                        referenceCandidateReason = "selected reference-resolved declaration from depth=$depth ref#=$index"
                    }
                }

                current = current.parent
                depth++
            }

            val noCandidateReason = "no supported non-container named element found"
            val selectedCandidate: PsiElement?
            val selectedCandidateName: String?
            val selectedReason: String?
            when {
                // (1) Direct declaration under the cursor: a declaration whose own name identifier
                //     contains the requested offset. This always wins, even if the leaf also carries a
                //     resolvable reference (e.g. constructors/overrides/partials).
                requestedOffset != null && localCandidate != null && localCandidateDirectlyUnderCursor -> {
                    selectedCandidate = localCandidate
                    selectedCandidateName = localCandidateName
                    selectedReason = localCandidateReason ?: noCandidateReason
                }
                // (2) Reference-resolved declaration under the cursor beats an enclosing-declaration
                //     fallback (cursor sits on a usage like `target.process()`).
                requestedOffset != null && !localCandidateDirectlyUnderCursor && referenceCandidate != null -> {
                    selectedCandidate = referenceCandidate
                    selectedCandidateName = referenceCandidateName
                    selectedReason = referenceCandidateReason ?: noCandidateReason
                }
                // (3) Enclosing declaration fallback. Also the offset-less path, which preserves the
                //     historical `localCandidate ?: referenceCandidate` ordering.
                localCandidate != null -> {
                    selectedCandidate = localCandidate
                    selectedCandidateName = localCandidateName
                    selectedReason = localCandidateReason ?: noCandidateReason
                }
                referenceCandidate != null -> {
                    selectedCandidate = referenceCandidate
                    selectedCandidateName = referenceCandidateName
                    selectedReason = referenceCandidateReason ?: noCandidateReason
                }
                else -> {
                    selectedCandidate = null
                    selectedCandidateName = null
                    selectedReason = noCandidateReason
                }
            }

            return RenameTargetResolution(
                candidate = selectedCandidate,
                candidateName = selectedCandidateName,
                selectedReason = selectedReason,
                leafDescription = describeElementForTraceValue(element),
                parentChainDescription = if (parentChain.isEmpty()) "none" else parentChain.joinToString(" | "),
                referenceDescription = if (referenceAttempts.isEmpty()) "none" else referenceAttempts.joinToString(" | ")
            )
        }

        private fun inspectRenameCandidateSupport(
            element: PsiElement,
            processorSupportOverride: ((PsiElement) -> Boolean)? = null
        ): RenameCandidateInspection {
            val isPsiNamedElement = element is PsiNamedElement
            val isNameIdentifierOwner = element is PsiNameIdentifierOwner
            val identifierElement = when {
                isNameIdentifierOwner -> runCatching { element.nameIdentifier }.getOrNull()
                else -> findDeclarationIdentifierLikeChild(element)
            }
            val identifierRange = runCatching { identifierElement?.textRange }.getOrNull()
            val directName = (element as? PsiNamedElement)?.name?.takeIf { !it.isNullOrBlank() }
            val identifierText = runCatching { identifierElement?.text }
                .getOrNull()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            val candidateName = directName ?: identifierText
            val processorInfo = safeCanProcessRenameElement(element, processorSupportOverride)
            val containerLike = isUnsupportedRiderFrontendFallbackTargetClass(element.javaClass.name)
            val declarationLike = isPsiNamedElement ||
                isNameIdentifierOwner ||
                element.javaClass.name.contains("declaration", ignoreCase = true) ||
                identifierElement != null

            val trace = buildString {
                append("class=")
                append(element.javaClass.name)
                append(" range=")
                append(describeRange(runCatching { element.textRange }.getOrNull()))
                append(" isPsiNamedElement=")
                append(isPsiNamedElement)
                append(" name=")
                append(directName ?: "<unnamed>")
                append(" candidateName=")
                append(candidateName ?: "<unnamed>")
                append(" isNameIdentifierOwner=")
                append(isNameIdentifierOwner)
                append(" identifierRange=")
                append(describeRange(identifierRange))
                append(" declarationLike=")
                append(declarationLike)
                append(" canProcessElement=")
                append(processorInfo.first?.toString() ?: "error")
                append(" processor=")
                append(processorInfo.second ?: "unknown")
                append(" containerLike=")
                append(containerLike)
            }

            return RenameCandidateInspection(
                name = candidateName,
                identifierRange = identifierRange,
                isPsiNamedElement = isPsiNamedElement,
                isNameIdentifierOwner = isNameIdentifierOwner,
                canProcessElement = processorInfo.first,
                processorClassName = processorInfo.second,
                containerLike = containerLike,
                declarationLike = declarationLike,
                traceDescription = trace
            )
        }

        private fun safeCanProcessRenameElement(
            element: PsiElement,
            processorSupportOverride: ((PsiElement) -> Boolean)? = null
        ): Pair<Boolean?, String?> {
            if (processorSupportOverride != null) {
                return runCatching { processorSupportOverride(element) to "override" }
                    .getOrElse { false to "override-error:${it::class.java.simpleName}" }
            }

            return runCatching {
                val processor = RenamePsiElementProcessor.forElement(element)
                processor.canProcessElement(element) to processor.javaClass.name
            }.getOrElse {
                false to "error:${it::class.java.simpleName}"
            }
        }

        private fun findDeclarationIdentifierLikeChild(element: PsiElement): PsiElement? {
            return runCatching {
                element.children.firstOrNull { child ->
                    child.javaClass.name.contains("identifier", ignoreCase = true) &&
                        !child.text.isNullOrBlank()
                }
            }.getOrNull()
        }

        private fun describeRange(range: TextRange?): String {
            return range?.let { "${it.startOffset}..${it.endOffset}" } ?: "unknown"
        }

        private fun safeSnippet(element: PsiElement?): String {
            val raw = runCatching { element?.text }.getOrNull()
                ?.replace("\n", "\\n")
                ?.replace("\r", "\\r")
                ?.replace(Regex("\\s+"), " ")
                ?.trim()
                .orEmpty()

            if (raw.isEmpty()) return ""
            return if (raw.length <= TRACE_SNIPPET_LIMIT) raw else raw.take(TRACE_SNIPPET_LIMIT) + "…"
        }

        internal fun describeElementForTraceValue(element: PsiElement?): String {
            if (element == null) return "null"
            val className = element.javaClass.name
            val name = (element as? PsiNamedElement)?.name ?: "<unnamed>"
            val snippet = safeSnippet(element)
            return buildString {
                append("class=")
                append(className)
                append(" name=")
                append(name)
                append(" range=")
                append(describeRange(runCatching { element.textRange }.getOrNull()))
                if (snippet.isNotEmpty()) {
                    append(" snippet=")
                    append(snippet)
                }
            }
        }

        private val renameTraceSequence = AtomicInteger(0)
        private val renameTraceWriteLock = Any()
        private val renameTraceTimestampFormatter: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT

        internal fun defaultRenameTraceLogFile(): File = File(System.getProperty("java.io.tmpdir"), RENAME_TRACE_FILE_NAME)

        internal fun isRenameTraceEnabled(
            systemPropertyValue: String? = System.getProperty(RENAME_TRACE_SYSTEM_PROPERTY),
            envValue: String? = System.getenv(RENAME_TRACE_ENV_VAR)
        ): Boolean {
            fun String?.isTruthy(): Boolean {
                return when (this?.trim()?.lowercase()) {
                    "1", "true", "yes", "on" -> true
                    else -> false
                }
            }
            return systemPropertyValue.isTruthy() || envValue.isTruthy()
        }

        internal fun newRenameTraceSession(
            enabled: Boolean = isRenameTraceEnabled(),
            logFile: File = defaultRenameTraceLogFile()
        ): RenameTraceSession {
            val correlationId = "[IndexMcp.Rename #${renameTraceSequence.incrementAndGet()}]"
            return RenameTraceSession(correlationId = correlationId, logFile = logFile, enabled = enabled)
        }

        internal fun formatRenameTraceLine(
            correlationId: String,
            event: String,
            fields: Map<String, Any?>,
            timestamp: Instant = Instant.now()
        ): String {
            val normalizedEvent = sanitizeRenameTraceValue(event)
            val suffix = if (fields.isEmpty()) {
                ""
            } else {
                fields.entries.joinToString(separator = " ", prefix = " ") { (key, value) ->
                    "${key.trim()}=${sanitizeRenameTraceValue(value)}"
                }
            }
            return "${renameTraceTimestampFormatter.format(timestamp)} $correlationId $normalizedEvent$suffix"
        }

        private fun sanitizeRenameTraceValue(value: Any?): String {
            val normalized = when (value) {
                null -> "null"
                is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]") { sanitizeRenameTraceScalar(it) }
                is Array<*> -> value.joinToString(prefix = "[", postfix = "]") { sanitizeRenameTraceScalar(it) }
                else -> sanitizeRenameTraceScalar(value)
            }
            return if (normalized.length <= RENAME_TRACE_VALUE_LIMIT) normalized else normalized.take(RENAME_TRACE_VALUE_LIMIT) + "…"
        }

        private fun sanitizeRenameTraceScalar(value: Any?): String {
            val raw = value?.toString().orEmpty()
                .replace("\\", "\\\\")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\t", "\\t")
                .replace(Regex("\\s+"), " ")
                .trim()
                .ifEmpty { "<blank>" }
            val limited = if (raw.length <= RENAME_TRACE_VALUE_LIMIT) raw else raw.take(RENAME_TRACE_VALUE_LIMIT) + "…"
            return if (limited.matches(Regex("[A-Za-z0-9_./:#\\-]+"))) limited else '"' + limited + '"'
        }

        internal class RenameTraceSession internal constructor(
            val correlationId: String,
            val logFile: File,
            val enabled: Boolean
        ) {
            fun event(event: String, vararg fields: Pair<String, Any?>) {
                event(event, linkedMapOf(*fields))
            }

            fun event(event: String, fields: Map<String, Any?>) {
                if (!enabled) {
                    return
                }
                val line = formatRenameTraceLine(correlationId = correlationId, event = event, fields = fields)
                runCatching {
                    synchronized(renameTraceWriteLock) {
                        logFile.parentFile?.mkdirs()
                        logFile.appendText(line + System.lineSeparator())
                    }
                }.onFailure { traceFailure ->
                    LOG.debug("Failed to append Rider rename trace log", traceFailure)
                }
            }
        }
    }

    override val name = "ide_refactor_rename"

    override val description = """
        Rename a symbol or file and update all references across the project. Use instead of find-and-replace for safe, semantic renaming that handles all usages correctly. Supports undo (Ctrl+Z).

        Two modes:
        - **Symbol rename** (file + line + column + newName): Rename a symbol at a specific position.
        - **File rename** (file + newName, WITHOUT line/column): Rename the file itself. Works for all file types including binary files (images, etc.). Especially useful for Android resource files (.webp, .png, .xml in res/) where it updates all resource references across the project.

        Automatically renames related elements: getters/setters, overriding methods, constructor parameters ↔ fields, test classes.

        When renaming a method that overrides a base method, the `overrideStrategy` parameter controls behavior:
        - "rename_base" (default): Automatically renames the base method and all overrides. No dialog shown.
        - "rename_only_current": Renames only the current method, leaving the base and other overrides unchanged.
        - "ask": Shows the IDE's built-in dialog to let the user choose interactively.

        The `relatedRenamingStrategy` parameter controls automatic renaming of related symbols (e.g., same-named properties on unrelated classes, getters/setters, test classes, variables):
        - "all" (default): Automatically rename all related symbols. Current behavior.
        - "none": Rename only the targeted symbol. Skip all automatic related renames.
        - "accessors_and_tests": Only rename getters/setters and test classes/methods. Skip variables, inheritors, overloads, and parameters on unrelated classes.
        - "ask": Show the IDE dialog for each related rename for interactive choice.

        Returns: affected files list and change count. Modifies source files.

        Parameters: file + newName (required). line + column (optional — omit for file rename). overrideStrategy + relatedRenamingStrategy (optional).

        Examples:
        - Symbol rename: {"file": "src/UserService.java", "line": 15, "column": 18, "newName": "CustomerService"}
        - File rename: {"file": "res/mipmap-hdpi/ic_launcher.webp", "newName": "ic_app_icon.webp"}
    """.trimIndent()

    override val inputSchema: JsonObject = SchemaBuilder.tool()
        .projectPath()
        .file(description = "Path to file relative to project root. REQUIRED.")
        .intProperty("line", "1-based line number. Required for symbol rename, omit for file rename.")
        .intProperty("column", "1-based column number. Required for symbol rename, omit for file rename.")
        .stringProperty("newName", "The new name for the symbol or file. REQUIRED. For file renames, include the file extension (e.g., 'new_name.webp').", required = true)
        .enumProperty(
            "overrideStrategy",
            "Strategy when renaming a method that overrides a base method. " +
                "'rename_base' (default): rename the base method and all overrides automatically. " +
                "'rename_only_current': rename only the current method. " +
                "'ask': show the IDE dialog for interactive choice.",
            listOf("rename_base", "rename_only_current", "ask")
        )
        .enumProperty(
            "relatedRenamingStrategy",
            "Strategy for automatic renaming of related symbols (same-named properties, getters/setters, test classes, variables). " +
                "'all' (default): automatically rename all related symbols. " +
                "'none': rename only the targeted symbol, skip all automatic related renames. " +
                "'accessors_and_tests': only rename getters/setters and test classes/methods. " +
                "'ask': show the IDE dialog for each related rename for interactive choice.",
            listOf("all", "none", "accessors_and_tests", "ask")
        )
        .build()

    /**
     * Data class holding validated rename parameters from Phase 1.
     */
    private data class RenameValidation(
        val element: PsiElement,
        val oldName: String,
        val error: String? = null
    )

    internal data class RenameModeResolution(
        val isFileRename: Boolean,
        val line: Int?,
        val column: Int?,
        val error: String? = null
    )

    internal fun resolveRenameMode(arguments: JsonObject): RenameModeResolution {
        val linePresent = optionalStringArg(arguments, "line") != null
        val columnPresent = optionalStringArg(arguments, "column") != null
        val line = optionalIntArg(arguments, "line")
        val column = optionalIntArg(arguments, "column")

        return when {
            // Both omitted/blank -> file rename.
            !linePresent && !columnPresent -> RenameModeResolution(
                isFileRename = true,
                line = null,
                column = null
            )

            // Exactly one coordinate supplied -> incomplete symbol target.
            linePresent != columnPresent -> RenameModeResolution(
                isFileRename = false,
                line = line?.takeIf { it > 0 },
                column = column?.takeIf { it > 0 },
                error = "Both 'line' and 'column' must be provided for symbol rename, or both omitted for file rename."
            )

            // Both present and valid -> symbol rename.
            line != null && line > 0 && column != null && column > 0 -> RenameModeResolution(
                isFileRename = false,
                line = line,
                column = column
            )

            // Both present, but at least one is non-positive or non-numeric. An explicit 0/negative
            // position is an invalid input, NOT a request for a (destructive) file rename: fail closed.
            else -> RenameModeResolution(
                isFileRename = false,
                line = line?.takeIf { it > 0 },
                column = column?.takeIf { it > 0 },
                error = "Invalid position: 'line' and 'column' must be positive integers (1-based). " +
                    "Omit both fields entirely for a file rename."
            )
        }
    }

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val trace = newRenameTraceSession()
        fun finish(
            result: ToolCallResult,
            success: Boolean,
            status: String?,
            changesCount: Int?,
            affectedFilesCount: Int?,
            message: String?
        ): ToolCallResult {
            trace.event(
                "final.result",
                "success" to success,
                "status" to status,
                "changesCount" to changesCount,
                "affectedFilesCount" to affectedFilesCount,
                "message" to message
            )
            return result
        }

        val file = requiredStringArg(arguments, "file").getOrElse {
            val message = it.message ?: "Missing required parameter: file"
            return finish(createErrorResult(message), false, STATUS_FAILED, 0, 0, message)
        }
        val newName = arguments["newName"]?.jsonPrimitive?.content
            ?: return finish(createErrorResult("Missing required parameter: newName"), false, STATUS_FAILED, 0, 0, "Missing required parameter: newName")
        val renameMode = resolveRenameMode(arguments)
        val line = renameMode.line
        val column = renameMode.column

        val overrideStrategy = arguments["overrideStrategy"]?.jsonPrimitive?.content ?: "rename_base"
        if (overrideStrategy !in listOf("rename_base", "rename_only_current", "ask")) {
            val message = "Invalid overrideStrategy: '$overrideStrategy'. Must be 'rename_base', 'rename_only_current', or 'ask'."
            return finish(createErrorResult(message), false, STATUS_FAILED, 0, 0, message)
        }

        val relatedRenamingStrategy = arguments["relatedRenamingStrategy"]?.jsonPrimitive?.content ?: "all"
        if (relatedRenamingStrategy !in listOf("all", "none", "accessors_and_tests", "ask")) {
            val message = "Invalid relatedRenamingStrategy: '$relatedRenamingStrategy'. Must be 'all', 'none', 'accessors_and_tests', or 'ask'."
            return finish(createErrorResult(message), false, STATUS_FAILED, 0, 0, message)
        }

        val isFileRename = renameMode.isFileRename
        val isDotNet = RiderBackendSemanticService.isDotNetFile(file)
        trace.event(
            "request.start",
            "file" to file,
            "line" to line,
            "column" to column,
            "newName" to newName,
            "overrideStrategy" to overrideStrategy,
            "relatedRenamingStrategy" to relatedRenamingStrategy,
            "isDotNet" to isDotNet,
            "isFileRename" to isFileRename
        )

        if (newName.isBlank()) {
            return finish(createErrorResult("newName cannot be blank"), false, STATUS_FAILED, 0, 0, "newName cannot be blank")
        }

        if (renameMode.error != null) {
            return finish(createErrorResult(renameMode.error), false, STATUS_FAILED, 0, 0, renameMode.error)
        }

        requireSmartMode(project)

        val shouldUseRiderFrontendAutomation = shouldUseRiderFrontendRenameAutomation(file, isFileRename, relatedRenamingStrategy)
        // frontend.file.resolve / frontend.offset.resolve / frontend.resolve.start / frontend.resolve.end
        trace.event(
            "route.selected",
            "route" to if (shouldUseRiderFrontendAutomation) "rider-frontend-automation" else "generic",
            "reason" to if (shouldUseRiderFrontendAutomation) {
                "dotnet symbol rename must use rider frontend automation"
            } else {
                "standard rename processor lane"
            }
        )

        val riderFrontendExecutionRequested = shouldUseRiderFrontendAutomation
        val riderFrontendStatus = if (shouldUseRiderFrontendAutomation) STATUS_UNSUPPORTED else null

        // ═══════════════════════════════════════════════════════════════════════
        // PHASE 1: BACKGROUND - Find element and validate (suspending read action)
        // ═══════════════════════════════════════════════════════════════════════
        val validation = suspendingReadAction {
            if (isFileRename) {
                validateAndPrepareFileRename(project, file, newName)
            } else {
                validateAndPrepare(
                    project,
                    file,
                    line!!,
                    column!!,
                    newName,
                    riderFrontendExecutionRequested
                )
            }
        }

        if (validation.error != null) {
            return if (riderFrontendExecutionRequested) {
                finish(createJsonResult(
                    RefactoringResult(
                        success = false,
                        affectedFiles = emptyList(),
                        changesCount = 0,
                        message = validation.error,
                        status = STATUS_UNSUPPORTED_CONTEXT
                    )
                ), false, STATUS_UNSUPPORTED_CONTEXT, 0, 0, validation.error)
            } else {
                finish(createErrorResult(validation.error), false, STATUS_FAILED, 0, 0, validation.error)
            }
        }

        val element = validation.element
        val oldName = validation.oldName
        val dotNetFileRenameProbe = if (isFileRename && RiderBackendSemanticService.isDotNetFile(file)) {
            suspendingReadAction {
                (element as? PsiFile)?.let { psiFile ->
                    DotNetFileRenameProbe(
                        pointer = SmartPointerManager.getInstance(project).createSmartPsiElementPointer(psiFile),
                        beforeFileText = psiFile.text
                    )
                }
            }
        } else {
            null
        }

        var riderExecutionPlan: RiderRenameExecutionPlan? = null

        if (riderFrontendExecutionRequested) {
            try {
                val actionPlan = planProductionRiderRename(project, element, newName)
                riderExecutionPlan = actionPlan
                trace.event(
                    "frontend.handler.invoke.start",
                    "handlerClass" to actionPlan.selectedHandlerClassName,
                    "handlerTitle" to actionPlan.selectedHandlerTitle,
                    "targetClass" to element.javaClass.name,
                    "targetName" to (element as? PsiNamedElement)?.name,
                    "availableHandlers" to actionPlan.availableHandlerTitles,
                    "lane" to actionPlan.lane.name,
                    "policy" to actionPlan.policy.reason
                )
                // frontend.action.end
                if (!actionPlan.policy.shouldInvoke) {
                    // reason=${actionPlan.policy.reason}
                    // Close any editor we auto-opened during planning before failing closed.
                    actionPlan.editorLookup.takeIf { it.openedByTool }?.virtualFile?.let { virtualFile ->
                        edtAction { FileEditorManager.getInstance(project).closeFile(virtualFile) }
                    }
                    val refusalReason = actionPlan.blockReason ?: actionPlan.policy.reason
                    val blocked = buildBlockedRiderFrontendFallbackResult(
                        oldName = oldName,
                        actionReason = refusalReason
                    )
                    return finish(createJsonResult(blocked), false, blocked.status, blocked.changesCount, blocked.affectedFiles.size, blocked.message)
                }
            } catch (e: com.intellij.openapi.progress.ProcessCanceledException) {
                throw e
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Fail closed: a Rider C# rename must NOT silently fall through to the generic
                // rename lane when planning fails unexpectedly.
                LOG.warn("Rider frontend rename planning failed unexpectedly; failing closed", e)
                val message = "Rider C# rename could not be planned: ${e.message ?: e.javaClass.simpleName}. " +
                    "Ensure the symbol's declaration file can be opened in an editor and retry."
                return finish(
                    createJsonResult(
                        RefactoringResult(
                            success = false,
                            affectedFiles = emptyList(),
                            changesCount = 0,
                            message = message,
                            status = STATUS_UNSUPPORTED_CONTEXT
                        )
                    ), false, STATUS_UNSUPPORTED_CONTEXT, 0, 0, message
                )
            }
        }

        // ═══════════════════════════════════════════════════════════════════════
        // PHASE 2: EDT - Execute rename using RenameProcessor
        // ═══════════════════════════════════════════════════════════════════════
        var changesCount = 0
        val affectedFiles = mutableSetOf<String>()
        var relatedRenamesCount = 0
        var errorMessage: String? = null
        var errorClassName: String? = null
        var riderMutationCheck: FrontendRenameMutationCheck? = null
        var riderDialogAutomationSnapshot: RiderDialogAutomationSnapshot? = null
        val riderMutationProbe = riderFrontendExecutionRequested.takeIf { it }?.let {
            suspendingReadAction {
                RiderFrontendMutationProbe(
                    pointer = SmartPointerManager.getInstance(project).createSmartPsiElementPointer(element),
                    beforeName = (element as? PsiNamedElement)?.name,
                    targetFilePath = element.containingFile?.virtualFile?.path,
                    beforeFileText = element.containingFile?.text
                )
            }
        }
        val riderPlan = riderExecutionPlan
        val autoOpenedEditorFile = riderPlan?.editorLookup?.takeIf { it.openedByTool }?.virtualFile

        val highLevelHandlerLane = riderFrontendExecutionRequested && riderPlan?.lane == RiderRenameExecutionLane.HIGH_LEVEL_HANDLER
        // PRE snapshot must be taken before the rename mutates anything. The POST snapshot/diff
        // is deferred until after commit + saveAllDocuments so on-disk mtimes reflect the edits.
        val preRenameTimestamps: Map<String, Long> = if (highLevelHandlerLane) {
            snapshotProjectFileTimestamps(project)
        } else {
            emptyMap()
        }

        try {
            if (highLevelHandlerLane) {
                // frontend.action.end
                val selectedHandler = requireNotNull(riderPlan.selectedHandler) {
                    "Preferred Rider rename lane was selected without a concrete handler"
                }
                val editor = requireNotNull(riderPlan.editorLookup.editor) {
                    "Preferred Rider rename lane requires an active editor"
                }
                val psiFile = requireNotNull(element.containingFile) {
                    "Preferred Rider rename lane requires a containing file"
                }
                val dataContext = requireNotNull(riderPlan.dataContext.dataContext) {
                    "Preferred Rider rename lane requires a deterministic data context"
                }
                executeRiderHandlerWithDialogAutomation(
                    project = project,
                    newName = newName,
                    relatedRenamingStrategy = relatedRenamingStrategy,
                    selectedHandler = selectedHandler,
                    editor = editor,
                    psiFile = psiFile,
                    dataContext = dataContext,
                    targetElement = element,
                    trace = trace,
                ).also { riderDialogAutomationSnapshot = it }
            } else {
                edtAction {
                    // frontend.processor.start
                    val result = executeRename(project, element, newName, overrideStrategy, relatedRenamingStrategy, affectedFiles)
                    changesCount = result.first
                    relatedRenamesCount = result.second
                    // frontend.processor.end
                }
            }

            riderMutationCheck = riderMutationProbe?.let { probe ->
                val mutationCheckDelayMs = riderFrontendMutationCheckDelayMs(
                    usedAsyncFollowUpClick = riderDialogAutomationSnapshot?.usedAsyncFollowUpClick == true
                )
                val mutationCheckStartedAt = System.nanoTime()
                // frontend.verification.result
                trace.event("mutation.check.start", "beforeName" to probe.beforeName, "targetFilePath" to probe.targetFilePath)
                val mutationPoll = pollRiderFrontendMutationVerification(
                    initialDelayMs = mutationCheckDelayMs,
                    sleep = { delayMs ->
                        if (delayMs > 0) {
                            trace.event("mutation.check.delay", "delayMs" to delayMs, "reason" to "awaiting-rider-followup-close")
                            Thread.sleep(delayMs)
                        }
                    }
                ) {
                    suspendingReadAction {
                        val afterElement = probe.pointer.element
                        val pathEvidence = collectRiderFrontendMutationPathEvidence(
                            project = project,
                            targetFilePath = probe.targetFilePath,
                            newName = newName,
                            afterElement = afterElement
                        )
                        verifyRiderFrontendMutation(
                            beforeName = probe.beforeName,
                            afterName = (afterElement as? PsiNamedElement)?.name,
                            newName = newName,
                            beforeFileText = probe.beforeFileText,
                            afterFileText = probe.targetFilePath
                                ?.let(LocalFileSystem.getInstance()::findFileByPath)
                                ?.let(PsiManager.getInstance(project)::findFile)
                                ?.text,
                            pathEvidence = pathEvidence
                        )
                    }
                }
                val mutationCheck = mutationPoll.check
                if (riderDialogAutomationSnapshot?.timeoutDeferredToMutationCheck == true) {
                    trace.event(
                        if (mutationCheck.verified) "followup.progress.mutation-verified" else "followup.progress.mutation-missing",
                        "reason" to mutationCheck.reason
                    )
                    if (mutationCheck.verified) {
                        trace.event("automation.completed-after-mutation", "reason" to mutationCheck.reason)
                    }
                }
                trace.event(
                    "mutation.check.result",
                    "beforeName" to probe.beforeName,
                    "afterName" to probe.pointer.element.let { (it as? PsiNamedElement)?.name },
                    "observedFilePath" to mutationCheck.observedFilePath,
                    "verified" to mutationCheck.verified,
                    "reason" to mutationCheck.reason,
                    "attemptCount" to mutationPoll.attemptCount,
                    "waitedMs" to mutationPoll.totalWaitMs,
                    "timingMs" to ((System.nanoTime() - mutationCheckStartedAt) / 1_000_000)
                )
                mutationCheck
            }
        } catch (e: com.intellij.openapi.progress.ProcessCanceledException) {
            throw e
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            errorMessage = e.message ?: "Unknown error during rename"
            errorClassName = e::class.java.name
            // frontend.processor.failure
        } finally {
            autoOpenedEditorFile?.let { virtualFile ->
                edtAction {
                    FileEditorManager.getInstance(project).closeFile(virtualFile)
                }
            }
        }

        // Commit and save outside EDT block; commitDocuments switches to a
        // write-safe EDT modality.
        if (errorMessage == null) {
            commitDocuments(project)
            edtAction { FileDocumentManager.getInstance().saveAllDocuments() }
        }

        if (errorMessage == null &&
            riderFrontendExecutionRequested &&
            riderExecutionPlan?.lane == RiderRenameExecutionLane.HIGH_LEVEL_HANDLER &&
            riderMutationCheck?.verified == true
        ) {
            // Disk-level snapshot diff: the dialog-automation lane can mutate many files
            // (AXAML/code-behind pairs, partial classes, callers) but the mutation probe
            // only observes a single file. Without diffing, affectedFiles collapses to 1
            // (Reg 4). The POST snapshot is taken AFTER commit + saveAllDocuments so on-disk
            // mtimes reflect the in-memory edits; otherwise multi-file changes are missed.
            val postRenameTimestamps = snapshotProjectFileTimestamps(project)
            val changedPaths = diffSnapshotTimestamps(preRenameTimestamps, postRenameTimestamps)
            if (changedPaths.isNotEmpty()) {
                changesCount = changedPaths.size
                for (path in changedPaths) {
                    affectedFiles += toRelativeProjectPath(project, path)
                }
            } else {
                changesCount = 1
                val affectedPath = riderMutationCheck?.observedFilePath ?: riderMutationProbe?.targetFilePath
                affectedPath?.let { affectedFiles += toRelativeProjectPath(project, it) }
            }
        }

        val dotNetFileRenameVerification = if (errorMessage == null) {
            dotNetFileRenameProbe?.let { probe ->
                suspendingReadAction {
                    verifyDotNetFileRenameDeclaredTypeIdentity(
                        beforeFileText = probe.beforeFileText,
                        afterFileText = probe.pointer.element?.text
                    )
                }
            }
        } else {
            null
        }

        return if (errorMessage != null) {
            // frontend.execution.result / frontend.result
            val classification = classifyFrontendRenameFailure(errorMessage, errorClassName, riderFrontendStatus)
            if (classification.status == STATUS_UNSUPPORTED_CONTEXT) {
                finish(createJsonResult(
                    RefactoringResult(
                        success = false,
                        affectedFiles = emptyList(),
                        changesCount = 0,
                        message = classification.userMessage,
                        status = STATUS_UNSUPPORTED_CONTEXT
                    )
                ), false, STATUS_UNSUPPORTED_CONTEXT, 0, 0, classification.userMessage)
            } else {
                finish(createErrorResult(classification.userMessage), false, STATUS_FAILED, 0, 0, classification.userMessage)
            }
        } else {
            // frontend.execution.result / frontend.result
            val summary = summarizeFrontendRenameResult(
                oldName = oldName,
                newName = newName,
                relatedRenamesCount = relatedRenamesCount,
                affectedFiles = affectedFiles.toList(),
                changesCount = changesCount,
                riderFallbackStatus = riderFrontendStatus,
                mutationCheck = riderMutationCheck
            )

            if (dotNetFileRenameVerification != null) {
                finish(createJsonResult(
                    RefactoringResult(
                        success = false,
                        affectedFiles = affectedFiles.toList(),
                        changesCount = changesCount,
                        message = "Rename changed declared type identity during .NET file rename, so the request failed closed.",
                        status = STATUS_FAILED,
                        verification = dotNetFileRenameVerification
                    )
                ), false, STATUS_FAILED, changesCount, affectedFiles.size, "Rename changed declared type identity during .NET file rename, so the request failed closed.")
            } else {
                finish(createJsonResult(summary), summary.success, summary.status, summary.changesCount, summary.affectedFiles.size, summary.message)
            }
        }
    }

    private data class RiderFrontendMutationProbe(
        val pointer: com.intellij.psi.SmartPsiElementPointer<PsiElement>,
        val beforeName: String?,
        val targetFilePath: String?,
        val beforeFileText: String?
    )

    private data class DotNetFileRenameProbe(
        val pointer: com.intellij.psi.SmartPsiElementPointer<PsiFile>,
        val beforeFileText: String?
    )

    private data class RiderDialogAutomationSnapshot(
        val renameDialogSeen: Boolean,
        val handledDialogs: List<String>,
        val failureReason: String?,
        val usedAsyncFollowUpClick: Boolean,
        val timeoutDeferredToMutationCheck: Boolean
    )

    private class RiderRenameDialogAutomationController(
        private val newName: String,
        private val relatedRenamingStrategy: String,
        private val trace: RenameTraceSession,
    ) {
        private val renameDialogSeen = AtomicBoolean(false)
        private val waitingForSecondDialog = AtomicBoolean(false)
        private val automationCompleted = AtomicBoolean(false)
        private val renameDialogAutomated = CountDownLatch(1)
        private val handledDialogs = ConcurrentLinkedQueue<String>()
        private val failureReason = AtomicReference<String?>(null)
        private val listenerRegistered = AtomicBoolean(false)
        private val primaryDialogTitle = AtomicReference<String?>(null)
        private val pendingFollowUpContinuationSignature = AtomicReference<String?>(null)
        private val usedAsyncFollowUpClick = AtomicBoolean(false)
        private val timeoutDeferredToMutationCheck = AtomicBoolean(false)
        private var renameDialogReadinessTimer: Timer? = null
        private var followUpDialogReadinessTimer: Timer? = null
        private var secondDialogTimeoutTimer: Timer? = null
        private val followUpPollTickCount = AtomicInteger(0)

        private val listener = AWTEventListener { event ->
            val dialog = event.source as? JDialog ?: return@AWTEventListener
            when (event.id) {
                WindowEvent.WINDOW_OPENED -> handleDialogOpened(dialog)
                WindowEvent.WINDOW_CLOSED -> handleDialogClosed(dialog)
            }
        }

        fun install() {
            Toolkit.getDefaultToolkit().addAWTEventListener(listener, AWTEvent.WINDOW_EVENT_MASK)
            listenerRegistered.set(true)
            trace.event("dialog.listener.installed")
        }

        fun dispose() {
            cancelRenameDialogReadinessTimer()
            cancelFollowUpDialogReadinessTimer()
            cancelSecondDialogTimeout()
            if (listenerRegistered.compareAndSet(true, false)) {
                Toolkit.getDefaultToolkit().removeAWTEventListener(listener)
                trace.event("dialog.listener.removed")
            }
        }

        fun snapshot(): RiderDialogAutomationSnapshot {
            return RiderDialogAutomationSnapshot(
                renameDialogSeen = renameDialogSeen.get(),
                handledDialogs = handledDialogs.toList(),
                failureReason = failureReason.get(),
                usedAsyncFollowUpClick = usedAsyncFollowUpClick.get(),
                timeoutDeferredToMutationCheck = timeoutDeferredToMutationCheck.get()
            )
        }

        fun failTimeout() {
            trace.event("dialog.wait.result", "completed" to false, "reason" to "timeout")
            fail("timed out waiting for Rider rename dialog automation after ${RIDER_DIALOG_AUTOMATION_TIMEOUT_MS}ms", null)
        }

        fun resolveTimeoutOrDeferToMutationCheck(): RiderAutomationTimeoutDecision {
            val visibleDialogTitles = Window.getWindows()
                .filterIsInstance<JDialog>()
                .filter { it.isShowing }
                .map { it.title?.trim().orEmpty() }
            val decision = evaluateRiderAutomationTimeout(
                waitingForSecondDialog = waitingForSecondDialog.get(),
                usedAsyncFollowUpClick = usedAsyncFollowUpClick.get(),
                visibleDialogTitles = visibleDialogTitles
            )
            trace.event(
                "dialog.wait.timeout.decision",
                "status" to decision.traceStatus,
                "visibleTitles" to visibleDialogTitles,
                "waitingForSecondDialog" to waitingForSecondDialog.get(),
                "usedAsyncFollowUpClick" to usedAsyncFollowUpClick.get(),
                "failureReason" to decision.failureReason
            )
            if (decision.shouldDeferToMutationVerification) {
                timeoutDeferredToMutationCheck.set(true)
                trace.event("followup.poll.result", "status" to "timeout-deferred-no-visible-dialog")
                completeAutomation(details = "timeout deferred to mutation verification")
            }
            return decision
        }

        fun failInvocation(reason: String) {
            trace.event("frontend.handler.invoke.failure", "reason" to reason)
            fail(reason, null)
        }

        fun awaitRenameDialogAutomation(timeoutMs: Long): Boolean {
            return renameDialogAutomated.await(timeoutMs, TimeUnit.MILLISECONDS)
        }

        fun cancelOutstandingDialogs() {
            Window.getWindows()
                .filterIsInstance<JDialog>()
                .filter { it.isShowing }
                .forEach { dialog ->
                    val title = dialog.title?.trim().orEmpty()
                    if (
                        title.equals("Rename", ignoreCase = true) ||
                        title.contains("Refactor", ignoreCase = true) ||
                        title.contains("Preview", ignoreCase = true) ||
                        title.contains("Conflict", ignoreCase = true)
                    ) {
                        cancelDialog(dialog)
                    }
                }
        }

        private fun handleDialogOpened(dialog: JDialog) {
            val title = dialog.title?.trim().orEmpty().ifBlank { "<untitled>" }
            trace.event(
                "dialog.window.candidate",
                buildDialogEventFields(dialog, title)
            )

            if (automationCompleted.get() && isSecondDialogCandidateTitle(title)) {
                trace.event("unexpected.third-dialog.candidate", buildDialogEventFields(dialog, title))
            }

            if (!dialog.isShowing || failureReason.get() != null || automationCompleted.get()) {
                return
            }

            when {
                waitingForSecondDialog.get() -> {
                    trace.event("dialog.window.opened", buildDialogEventFields(dialog, title))
                    automateFollowUpDialog(dialog, title)
                }
                isRenameDialogTitle(title) -> {
                    trace.event("dialog.window.opened", buildDialogEventFields(dialog, title))
                    automateRenameDialog(dialog, title)
                }
            }
        }

        private fun handleDialogClosed(dialog: JDialog) {
            if (!waitingForSecondDialog.get() || automationCompleted.get()) {
                return
            }

            val title = dialog.title?.trim().orEmpty().ifBlank { "<untitled>" }
            if (!title.equals(primaryDialogTitle.get(), ignoreCase = true)) {
                if (isSecondDialogCandidateTitle(title)) {
                    trace.event("followup.poll.result", "title" to title, "status" to "dialog-closed")
                    scheduleFollowUpClosedStateCheck(title)
                }
                return
            }

            // frontend.dialog-automation.second-dialog.primary.closed
            trace.event("followup.poll.result", "title" to title, "status" to "primary-dialog-closed")
            scheduleFollowUpClosedStateCheck(title)
        }

        private fun scheduleFollowUpClosedStateCheck(title: String) {
            Timer(RIDER_DIALOG_READINESS_POLL_MS) {
                if (!waitingForSecondDialog.get() || failureReason.get() != null || automationCompleted.get()) {
                    return@Timer
                }
                val visibleCandidateTitles = Window.getWindows()
                    .filterIsInstance<JDialog>()
                    .filter { it.isShowing }
                    .map { it.title?.trim().orEmpty() }
                    .filter(::isSecondDialogCandidateTitle)
                if (visibleCandidateTitles.isEmpty()) {
                    trace.event("followup.poll.result", "title" to title, "status" to "closed-no-visible-followup")
                    completeAutomation(
                        details = "title=$title success=true newName=$newName secondDialog=false fallback=closed"
                    )
                } else {
                    trace.event("followup.poll.result", "title" to title, "status" to "closed-awaiting-next-state", "visibleTitles" to visibleCandidateTitles)
                }
            }.apply {
                isRepeats = false
                start()
            }
        }

        private fun automateRenameDialog(dialog: JDialog, title: String) {
            if (!renameDialogSeen.compareAndSet(false, true)) {
                return
            }

            trace.event("dialog.snapshot", buildDialogSnapshotFields(dialog, title))

            try {
                if (attemptRenameDialogAutomationWhenReady(dialog, title)) {
                    return
                }

                startRenameDialogReadinessPolling(dialog, title)
            } catch (t: Throwable) {
                fail("Rename dialog automation failed: ${t.message ?: t.javaClass.name}", dialog)
            }
        }

        private fun attemptRenameDialogAutomationWhenReady(dialog: JDialog, title: String): Boolean {
            val textField = findRenameTextComponent(dialog)
            val button = findButton(dialog, listOf("Next", "Refactor", "Rename", "OK"))
            if (textField == null || !textField.isShowing || button == null || !button.isEnabled) {
                return false
            }

            performRenameDialogAutomation(dialog, title, textField, button)
            return true
        }

        private fun startRenameDialogReadinessPolling(dialog: JDialog, title: String) {
            cancelRenameDialogReadinessTimer()
            val deadline = System.currentTimeMillis() + RIDER_DIALOG_READINESS_TIMEOUT_MS
            trace.event("dialog.wait.start", "title" to title, "timeoutMs" to RIDER_DIALOG_READINESS_TIMEOUT_MS, "phase" to "primary")
            renameDialogReadinessTimer = Timer(200) { timerEvent ->
                if (!dialog.isShowing || failureReason.get() != null || automationCompleted.get()) {
                    (timerEvent.source as? Timer)?.stop()
                    renameDialogReadinessTimer = null
                    return@Timer
                }
                // frontend.dialog-automation.readiness.waiting
                trace.event("dialog.wait.result", "title" to title, "phase" to "primary.poll", "status" to "tick")
                if (attemptRenameDialogAutomationWhenReady(dialog, title)) {
                    (timerEvent.source as? Timer)?.stop()
                    renameDialogReadinessTimer = null
                    // frontend.dialog-automation.readiness.ready
                    trace.event("dialog.wait.result", "title" to title, "phase" to "primary", "status" to "ready")
                    return@Timer
                }
                if (System.currentTimeMillis() >= deadline) {
                    (timerEvent.source as? Timer)?.stop()
                    renameDialogReadinessTimer = null
                    // frontend.dialog-automation.readiness.timeout
                    trace.event("dialog.wait.result", "title" to title, "phase" to "primary", "status" to "timeout")
                    fail(
                        "Rename dialog content did not become ready within ${RIDER_DIALOG_READINESS_TIMEOUT_MS}ms",
                        dialog
                    )
                }
            }.apply {
                initialDelay = RIDER_DIALOG_READINESS_POLL_MS
                isRepeats = true
                start()
            }
        }

        private fun performRenameDialogAutomation(
            dialog: JDialog,
            title: String,
            textField: JTextComponent,
            button: AbstractButton
        ) {
            cancelRenameDialogReadinessTimer()
            textField.text = newName
            val relatedDisableOutcome = disableRelatedSymbolsCheckboxes(dialog)
            val primarySubmitPlan = planRiderPrimaryDialogSubmit(
                buttonText = button.text,
                relatedRenamingStrategy = relatedRenamingStrategy,
                relatedDisableSucceeded = relatedDisableOutcome.succeeded,
                relatedDisableFailureKind = relatedDisableOutcome.failureKind,
                relatedDisableFailureReason = relatedDisableOutcome.failureReason
            )
            primaryDialogTitle.set(title)
            handledDialogs += title
            val shouldAwaitSecondDialog = primarySubmitPlan.shouldAwaitSecondDialog
            trace.event(
                "first.plan",
                "title" to title,
                "setText" to true,
                "checkboxDisableCount" to relatedDisableOutcome.changedCount,
                "checkboxDisableSucceeded" to relatedDisableOutcome.succeeded,
                "buttonLabel" to button.text,
                "awaitFollowUp" to shouldAwaitSecondDialog,
                "canClick" to primarySubmitPlan.shouldClick
            )
            if (!primarySubmitPlan.shouldClick) {
                trace.event(
                    "related.disable.failure",
                    "title" to title,
                    "method" to relatedDisableOutcome.method,
                    "changedCount" to relatedDisableOutcome.changedCount,
                    "reason" to primarySubmitPlan.failureReason
                )
                fail(
                    primarySubmitPlan.failureReason
                        ?: "Rider rename dialog could not disable related-symbol renames safely before continuing.",
                    dialog
                )
                return
            }
            if (shouldAwaitSecondDialog) {
                waitingForSecondDialog.set(true)
                scheduleSecondDialogTimeout(title)
            }
            trace.event(
                "first.action",
                "title" to title,
                "action" to "setText+click",
                "buttonLabelBeforeClick" to button.text,
                "awaitFollowUp" to shouldAwaitSecondDialog
            )
            button.doClick()
            if (shouldAwaitSecondDialog) {
                startFollowUpDialogReadinessPolling(dialog, title)
            }
            if (!shouldAwaitSecondDialog) {
                completeAutomation(
                    details = "title=$title success=true newName=$newName"
                )
            }
        }

        private fun automateFollowUpDialog(dialog: JDialog, title: String) {
            if (!isSecondDialogCandidateTitle(title)) {
                return
            }
            // frontend.dialog-automation.second-dialog.opened
            trace.event("dialog.snapshot", buildDialogSnapshotFields(dialog, title))
            trace.event("followup.poll.result", "title" to title, "status" to "dialog-opened")
            if (attemptFollowUpDialogAutomationWhenReady(dialog, title)) {
                return
            }
            startFollowUpDialogReadinessPolling(dialog, title)
        }

        private fun isRenameDialogTitle(title: String): Boolean {
            return title.equals("Rename", ignoreCase = true) || title.contains("Rename", ignoreCase = true)
        }

        private fun fail(reason: String, dialog: JDialog?) {
            if (!failureReason.compareAndSet(null, reason)) {
                return
            }
            trace.event("followup.action", "status" to "fail", "reason" to reason)
            cancelRenameDialogReadinessTimer()
            cancelFollowUpDialogReadinessTimer()
            waitingForSecondDialog.set(false)
            cancelSecondDialogTimeout()
            completeLatch()
            dialog?.let(::cancelDialog)
        }

        private fun scheduleSecondDialogTimeout(title: String) {
            cancelSecondDialogTimeout()
            secondDialogTimeoutTimer = Timer(RIDER_DIALOG_SECOND_STAGE_TIMEOUT_MS.toInt()) {
                if (!waitingForSecondDialog.compareAndSet(true, false) || failureReason.get() != null) {
                    return@Timer
                }
                val timeoutDecision = evaluateRiderSecondStageTimeout(
                    Window.getWindows()
                        .filterIsInstance<JDialog>()
                        .filter { it.isShowing }
                        .map { it.title?.trim().orEmpty() }
                )
                if (timeoutDecision.shouldCompleteSuccess) {
                    trace.event("followup.poll.result", "title" to title, "status" to "no-visible-followup")
                    completeAutomation(
                        details = "title=$title success=true newName=$newName secondDialog=false fallback=closed"
                    )
                } else {
                    trace.event("followup.poll.result", "title" to title, "status" to "timeout-visible-dialog", "reason" to timeoutDecision.failureReason)
                    fail(
                        timeoutDecision.failureReason
                            ?: "Follow-up Rider rename dialog did not complete within ${RIDER_DIALOG_SECOND_STAGE_TIMEOUT_MS}ms",
                        null
                    )
                }
            }.apply {
                isRepeats = false
                start()
            }
        }

        private fun cancelSecondDialogTimeout() {
            secondDialogTimeoutTimer?.stop()
            secondDialogTimeoutTimer = null
        }

        private fun cancelFollowUpDialogReadinessTimer() {
            followUpDialogReadinessTimer?.stop()
            followUpDialogReadinessTimer = null
        }

        private fun cancelRenameDialogReadinessTimer() {
            renameDialogReadinessTimer?.stop()
            renameDialogReadinessTimer = null
        }

        private fun completeAutomation(details: String) {
            cancelRenameDialogReadinessTimer()
            cancelFollowUpDialogReadinessTimer()
            pendingFollowUpContinuationSignature.set(null)
            waitingForSecondDialog.set(false)
            cancelSecondDialogTimeout()
            if (automationCompleted.compareAndSet(false, true)) {
                completeLatch()
            }
        }

        private fun completeLatch() {
            renameDialogAutomated.countDown()
        }

        private fun cancelDialog(dialog: JDialog) {
            findButton(dialog, listOf("Cancel", "Close"))?.let { cancelButton ->
                cancelButton.doClick()
                return
            }
            dialog.dispose()
        }

        private fun findButton(container: Container, labels: List<String>): AbstractButton? {
            return findComponent(container, AbstractButton::class.java) { button ->
                val text = button.text?.trim().orEmpty()
                labels.any { candidate -> text.equals(candidate, ignoreCase = true) }
            }
        }

        private fun findRenameTextComponent(container: Container): JTextComponent? {
            return findComponent(container, JTextComponent::class.java) { component ->
                component.isShowing && component.isEnabled && component.isEditable
            }
        }

        private fun buildFollowUpDialogSignature(
            title: String,
            visibleTexts: List<String>,
            visibleButtonLabels: List<String>
        ): String {
            return buildString {
                append(title.trim())
                append('|')
                append(visibleTexts.joinToString("|"))
                append('|')
                append(visibleButtonLabels.joinToString("|"))
            }
        }

        private fun disableRelatedSymbolsCheckboxes(container: Container): RelatedSymbolsDisableOutcome {
            if (relatedRenamingStrategy != "none") {
                return RelatedSymbolsDisableOutcome(
                    attempted = false,
                    succeeded = true,
                    method = "not-required",
                    changedCount = 0,
                    failureKind = RelatedSymbolsDisableFailureKind.NONE,
                    failureReason = null
                )
            }
            return RenameSymbolTool.attemptRequiredRelatedSymbolsDisable(container)
        }

        private fun describeRelatedSymbolsComponent(component: Component): List<String> {
            val texts = mutableListOf<String>()
            when (component) {
                is AbstractButton -> {
                    component.text?.trim()?.takeIf { it.isNotEmpty() }?.let(texts::add)
                    component.actionCommand?.trim()?.takeIf { it.isNotEmpty() }?.let(texts::add)
                }
                is javax.swing.JLabel -> component.text?.trim()?.takeIf { it.isNotEmpty() }?.let(texts::add)
            }
            component.accessibleContext?.accessibleName?.trim()?.takeIf { it.isNotEmpty() }?.let(texts::add)
            component.accessibleContext?.accessibleDescription?.trim()?.takeIf { it.isNotEmpty() }?.let(texts::add)
            return texts.distinct()
        }

        private fun matchesRelatedSymbolsComponent(component: Component): Boolean {
            return describeRelatedSymbolsComponent(component).any(::shouldDisableRelatedSymbolsCheckbox)
        }

        private fun tryDisableRelatedSymbolsAbstractButton(button: AbstractButton, method: String): RelatedSymbolsDisableOutcome? {
            if (!button.isShowing || !button.isEnabled || !button.isSelected || !matchesRelatedSymbolsComponent(button)) {
                return null
            }
            button.doClick()
            return if (!button.isSelected) {
                RelatedSymbolsDisableOutcome(
                    attempted = true,
                    succeeded = true,
                    method = method,
                    changedCount = 1,
                    failureKind = RelatedSymbolsDisableFailureKind.NONE,
                    failureReason = null
                )
            } else {
                RelatedSymbolsDisableOutcome(
                    attempted = true,
                    succeeded = false,
                    method = method,
                    changedCount = 0,
                    failureKind = RelatedSymbolsDisableFailureKind.ACTIONABLE_CONTROL_REMAINED_SELECTED,
                    failureReason = "Rider rename dialog kept the related-symbol toggle selected after attempting to disable it."
                )
            }
        }

        internal fun attemptRequiredRelatedSymbolsDisable(container: Container): RelatedSymbolsDisableOutcome {
            var resolvedOutcome: RelatedSymbolsDisableOutcome? = null
            walkComponents(container) { component ->
                if (resolvedOutcome != null) {
                    return@walkComponents
                }
                val button = component as? AbstractButton ?: return@walkComponents
                val outcome = tryDisableRelatedSymbolsAbstractButton(button, method = if (button.text.isNullOrBlank()) "accessible-button" else "checkbox")
                if (outcome != null) {
                    resolvedOutcome = outcome
                }
            }
            resolvedOutcome?.let { return it }

            walkComponents(container) { component ->
                if (resolvedOutcome != null) {
                    return@walkComponents
                }
                val label = component as? javax.swing.JLabel ?: return@walkComponents
                if (!label.isShowing || !shouldDisableRelatedSymbolsCheckbox(label.text)) {
                    return@walkComponents
                }
                val labeledButton = label.labelFor as? AbstractButton ?: return@walkComponents
                val outcome = tryDisableRelatedSymbolsAbstractButton(labeledButton, method = "label-for-button")
                if (outcome != null) {
                    resolvedOutcome = outcome
                }
            }
            resolvedOutcome?.let { return it }

            return RelatedSymbolsDisableOutcome(
                attempted = true,
                succeeded = false,
                method = "no-safe-toggle",
                changedCount = 0,
                failureKind = RelatedSymbolsDisableFailureKind.NO_SAFE_TOGGLE,
                failureReason = "Rider rename dialog could not disable related-symbol renames with a verified safe control, so the request failed closed before submitting the follow-up step."
            )
        }

        private fun attemptFollowUpDialogAutomationWhenReady(dialog: JDialog, title: String): Boolean {
            val visibleTexts = collectVisibleTexts(dialog)
            val visibleButtonLabels = collectVisibleButtonLabels(dialog)
            val plan = planRiderRenameFollowUpDialog(
                title = title,
                visibleTexts = visibleTexts,
                visibleButtonLabels = visibleButtonLabels,
                relatedRenamingStrategy = relatedRenamingStrategy
            )
            val dialogSignature = buildFollowUpDialogSignature(title, visibleTexts, visibleButtonLabels)
            val pendingSignature = pendingFollowUpContinuationSignature.get()
            if (pendingSignature != null && pendingSignature != dialogSignature) {
                pendingFollowUpContinuationSignature.set(null)
                trace.event("followup.poll.result", "title" to title, "status" to "state-advanced")
            }

            trace.event(
                // frontend.dialog-automation.second-dialog.components
                "followup.plan",
                "title" to title,
                "action" to plan.action,
                "submitButtonLabel" to plan.submitButtonLabel,
                "shouldCompleteAutomationAfterSubmit" to plan.shouldCompleteAutomationAfterSubmit,
                "shouldDisableRelatedSymbols" to plan.shouldDisableRelatedSymbols,
                "failure" to plan.failureReason
            )
            return when (plan.action) {
                "fail_unsupported" -> {
                    fail(plan.failureReason ?: "Rename dialog reported an unsupported operation", dialog)
                    true
                }

                "continue" -> {
                    if (!plan.shouldCompleteAutomationAfterSubmit && pendingSignature == dialogSignature) {
                        return false
                    }
                    val button = plan.submitButtonLabel?.let { findButton(dialog, listOf(it)) }
                    if (button == null || !button.isShowing || !button.isEnabled) {
                        false
                    } else {
                        cancelFollowUpDialogReadinessTimer()
                        handledDialogs += title
                        cancelSecondDialogTimeout()
                        waitingForSecondDialog.set(false)
                        val relatedDisableOutcome = if (plan.shouldDisableRelatedSymbols) {
                            trace.event("related.disable.attempt", "title" to title, "method" to "scan-safe-controls")
                            RenameSymbolTool.attemptRequiredRelatedSymbolsDisable(dialog)
                        } else {
                            RelatedSymbolsDisableOutcome(
                                attempted = false,
                                succeeded = true,
                                method = "not-required",
                                changedCount = 0,
                                failureKind = RelatedSymbolsDisableFailureKind.NONE,
                                failureReason = null
                            )
                        }
                        if (plan.shouldDisableRelatedSymbols) {
                            trace.event(
                                if (relatedDisableOutcome.succeeded) "related.disable.success" else "related.disable.failure",
                                "title" to title,
                                "method" to relatedDisableOutcome.method,
                                "changedCount" to relatedDisableOutcome.changedCount,
                                "reason" to relatedDisableOutcome.failureReason
                            )
                        }
                        if (plan.shouldDisableRelatedSymbols && !relatedDisableOutcome.succeeded) {
                            fail(
                                relatedDisableOutcome.failureReason
                                    ?: "Rider rename dialog could not disable related-symbol renames safely before continuing.",
                                dialog
                            )
                            true
                        } else {
                        trace.event(
                            // frontend.dialog-automation.second-dialog.button.clicked
                            "followup.action",
                            "title" to title,
                            "uncheckCount" to relatedDisableOutcome.changedCount,
                            "clickedButtonLabel" to button.text,
                            "status" to if (plan.shouldCompleteAutomationAfterSubmit) "complete" else "awaiting-next-state"
                        )
                        button.doClick()
                        if (plan.shouldCompleteAutomationAfterSubmit) {
                            pendingFollowUpContinuationSignature.set(null)
                            completeAutomation(
                                details = "title=$title success=true newName=$newName secondDialog=true"
                            )
                            true
                        } else {
                            usedAsyncFollowUpClick.set(true)
                            pendingFollowUpContinuationSignature.set(dialogSignature)
                            trace.event("followup.poll.result", "title" to title, "status" to "next-continues-waiting")
                            false
                        }
                        }
                    }
                }

                else -> false
            }
        }

        private fun startFollowUpDialogReadinessPolling(dialog: JDialog, title: String) {
            cancelFollowUpDialogReadinessTimer()
            val deadline = System.currentTimeMillis() + RIDER_DIALOG_READINESS_TIMEOUT_MS
            followUpPollTickCount.set(0)
            trace.event("followup.poll.start", "title" to title, "timeoutMs" to RIDER_DIALOG_READINESS_TIMEOUT_MS, "dialogIdentity" to dialogIdentity(dialog))
            followUpDialogReadinessTimer = Timer(200) { timerEvent ->
                if (!dialog.isShowing || failureReason.get() != null || automationCompleted.get()) {
                    (timerEvent.source as? Timer)?.stop()
                    followUpDialogReadinessTimer = null
                    return@Timer
                }

                trace.event(
                    "followup.poll.tick",
                    "title" to title,
                    "tick" to followUpPollTickCount.incrementAndGet(),
                    "dialogIdentity" to dialogIdentity(dialog)
                )
                if (attemptFollowUpDialogAutomationWhenReady(dialog, title)) {
                    (timerEvent.source as? Timer)?.stop()
                    followUpDialogReadinessTimer = null
                    trace.event("followup.poll.result", "title" to title, "status" to "ready")
                    return@Timer
                }

                if (System.currentTimeMillis() >= deadline) {
                    (timerEvent.source as? Timer)?.stop()
                    followUpDialogReadinessTimer = null
                    // frontend.dialog-automation.second-dialog.timeout
                    trace.event("followup.poll.result", "title" to title, "status" to "timeout")
                    fail(
                        "Follow-up dialog content did not become ready within ${RIDER_DIALOG_READINESS_TIMEOUT_MS}ms",
                        dialog
                    )
                }
            }.apply {
                initialDelay = RIDER_DIALOG_READINESS_POLL_MS
                isRepeats = true
                start()
            }
        }

        private fun collectVisibleButtonLabels(container: Container): List<String> {
            val labels = mutableListOf<String>()
            walkComponents(container) { component ->
                val button = component as? AbstractButton ?: return@walkComponents
                if (button.isShowing) {
                    button.text?.trim()?.takeIf { it.isNotEmpty() }?.let(labels::add)
                }
            }
            return labels
        }

        private fun collectVisibleTexts(container: Container): List<String> {
            val texts = mutableListOf<String>()
            walkComponents(container) { component ->
                when (component) {
                    is AbstractButton -> if (component.isShowing) {
                        component.text?.trim()?.takeIf { it.isNotEmpty() }?.let(texts::add)
                    }

                    is JTextComponent -> if (component.isShowing) {
                        component.text?.trim()?.takeIf { it.isNotEmpty() }?.let(texts::add)
                    }

                    is javax.swing.JLabel -> if (component.isShowing) {
                        component.text?.trim()?.takeIf { it.isNotEmpty() }?.let(texts::add)
                    }
                }
            }
            return texts
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

        private fun buildDialogEventFields(dialog: JDialog, title: String): Map<String, Any?> {
            return linkedMapOf(
                "title" to title,
                "class" to dialog.javaClass.name,
                "identity" to dialogIdentity(dialog),
                "visible" to dialog.isVisible,
                "showing" to dialog.isShowing
            )
        }

        private fun buildDialogSnapshotFields(dialog: JDialog, title: String): Map<String, Any?> {
            val labels = mutableListOf<String>()
            val buttons = mutableListOf<String>()
            val checkboxes = mutableListOf<String>()
            var textComponentCount = 0
            walkComponents(dialog) { component ->
                when (component) {
                    is javax.swing.JLabel -> if (component.isShowing) {
                        component.text?.trim()?.takeIf { it.isNotEmpty() }?.let { labels += truncateDialogText(it) }
                    }
                    is JTextComponent -> if (component.isShowing) {
                        textComponentCount++
                    }
                    is AbstractButton -> {
                        val state = "text=${truncateDialogText(component.text)}|selected=${component.isSelected}|enabled=${component.isEnabled}|visible=${component.isVisible}"
                        if (component.javaClass.name.contains("CheckBox", ignoreCase = true)) {
                            checkboxes += state
                        } else {
                            buttons += state
                        }
                    }
                }
            }
            return linkedMapOf(
                "title" to title,
                "labels" to labels,
                "buttons" to buttons,
                "checkboxes" to checkboxes,
                "textComponentCount" to textComponentCount
            )
        }

        private fun dialogIdentity(dialog: JDialog): String = Integer.toHexString(System.identityHashCode(dialog))

        private fun truncateDialogText(text: String?): String {
            val normalized = text?.replace(Regex("\\s+"), " ")?.trim().orEmpty().ifBlank { "<blank>" }
            return if (normalized.length <= 80) normalized else normalized.take(80) + "…"
        }

    }

    private suspend fun executeRiderHandlerWithDialogAutomation(
        project: Project,
        newName: String,
        relatedRenamingStrategy: String,
        selectedHandler: RenameHandler,
        editor: Editor,
        psiFile: PsiFile,
        dataContext: DataContext,
        targetElement: PsiElement,
        trace: RenameTraceSession,
    ): RiderDialogAutomationSnapshot {
        val dialogAutomation = RiderRenameDialogAutomationController(newName, relatedRenamingStrategy, trace)
        try {
            edtAction {
                dialogAutomation.install()
                trace.event(
                    "frontend.handler.invoke.dispatched",
                    "handlerClass" to selectedHandler.javaClass.name,
                    "targetClass" to targetElement.javaClass.name,
                    "targetName" to (targetElement as? PsiNamedElement)?.name,
                    "psiFile" to psiFile.virtualFile?.path
                )
                ApplicationManager.getApplication().invokeLater(
                    {
                        try {
                            trace.event(
                                "frontend.handler.invoke.start",
                                "handlerClass" to selectedHandler.javaClass.name,
                                "targetClass" to targetElement.javaClass.name,
                                "targetName" to (targetElement as? PsiNamedElement)?.name
                            )
                            selectedHandler.invoke(project, editor, psiFile, dataContext)
                        } catch (t: Throwable) {
                            trace.event(
                                "frontend.handler.invoke.failure",
                                "handlerClass" to selectedHandler.javaClass.name,
                                "targetClass" to targetElement.javaClass.name,
                                "targetName" to (targetElement as? PsiNamedElement)?.name,
                                "reason" to (t.message ?: t.javaClass.name)
                            )
                            dialogAutomation.failInvocation(
                                "Rider rename handler threw before dialog automation could complete: ${t.message ?: t.javaClass.name}",
                            )
                        }
                    },
                    ModalityState.any()
                )
            }

            trace.event("dialog.wait.start", "timeoutMs" to RIDER_DIALOG_AUTOMATION_TIMEOUT_MS, "phase" to "automation")
            val completedInTime = dialogAutomation.awaitRenameDialogAutomation(RIDER_DIALOG_AUTOMATION_TIMEOUT_MS)
            trace.event("dialog.wait.result", "completed" to completedInTime, "phase" to "automation")

            if (!completedInTime) {
                var timeoutDecision: RiderAutomationTimeoutDecision? = null
                edtAction {
                    timeoutDecision = dialogAutomation.resolveTimeoutOrDeferToMutationCheck()
                    if (timeoutDecision?.shouldFail != false) {
                        dialogAutomation.failTimeout()
                        dialogAutomation.cancelOutstandingDialogs()
                    }
                }
                if (timeoutDecision?.shouldFail != false) {
                    throw IllegalStateException(
                        timeoutDecision?.failureReason
                            ?: "Rider rename dialog automation timed out after ${RIDER_DIALOG_AUTOMATION_TIMEOUT_MS}ms"
                    )
                }
            }

            val automationSnapshot = dialogAutomation.snapshot()
            if (automationSnapshot.failureReason != null) {
                edtAction { dialogAutomation.cancelOutstandingDialogs() }
                throw IllegalStateException(automationSnapshot.failureReason)
            }
            if (!automationSnapshot.renameDialogSeen) {
                throw IllegalStateException(
                    "BackendRenameHandler completed without showing the expected Rider rename dialog"
                )
            }
            trace.event(
                "frontend.handler.invoke.success",
                "handlerClass" to selectedHandler.javaClass.name,
                "targetClass" to targetElement.javaClass.name,
                "targetName" to (targetElement as? PsiNamedElement)?.name,
                "handledDialogs" to automationSnapshot.handledDialogs
            )
            return automationSnapshot
        } finally {
            edtAction {
                dialogAutomation.dispose()
            }
        }
    }

    private fun assessPreferredRiderRenameHandler(selectedHandler: RenameHandler?): PreferredRiderActionLanePlan {
        return assessPreferredRiderRenameHandlerClassName(selectedHandler?.javaClass?.name)
    }

    private suspend fun planProductionRiderRename(
        project: Project,
        element: PsiElement,
        newName: String,
    ): RiderRenameExecutionPlan {
        // val editorLookup = lookupRiderFrontendEditor(project, element, trace)
        val editorLookup = lookupRiderFrontendEditor(project, element)
        try {
            val dataContext = composeDeterministicRiderDataContext(project, element, newName, editorLookup)
            val feasibility = evaluateRiderFrontendFeasibility(
                hasDeclarationEditor = editorLookup.editor != null,
                canComposeDataContext = dataContext.dataContext != null
            )

            if (!feasibility.canProceed) {
                return RiderRenameExecutionPlan(
                    availableHandlerTitles = emptyList(),
                    selectedHandler = null,
                    selectedHandlerClassName = null,
                    selectedHandlerTitle = null,
                    editorLookup = editorLookup,
                    dataContext = dataContext,
                    policy = RiderFrontendPolicyEvaluation(
                        shouldInvoke = false,
                        reason = feasibility.reason
                    ),
                    lane = RiderRenameExecutionLane.BLOCKED,
                    blockReason = feasibility.reason
                )
            }

            val registry = RenameHandlerRegistry.getInstance()
            val handlers = registry.getRenameHandlers(requireNotNull(dataContext.dataContext))
            val availableHandlerTitles = handlers.map(RenameHandlerRegistry::getHandlerTitle)
            val selectedHandler = handlers.singleOrNull() as? RenameHandler
            val handlerAssessment = assessPreferredRiderRenameHandler(selectedHandler)
            val preferredLanePlan = evaluatePreferredRiderActionLane(
                availableHandlerTitles = availableHandlerTitles,
                selectedHandlerClassName = selectedHandler?.javaClass?.name,
                selectedHandlerIsKnownSafeNonModal = handlerAssessment.shouldInvoke,
                selectedHandlerBlockReason = handlerAssessment.reason
            )
            val policy = RiderFrontendPolicyEvaluation(
                shouldInvoke = preferredLanePlan.shouldInvoke,
                reason = preferredLanePlan.reason
            )
            // frontend.factory.policy
            val factoryPlan = evaluateSecondChoiceRiderRefactoringFactoryLane(
                hasDeclarationEditor = editorLookup.editor != null,
                canComposeDataContext = dataContext.dataContext != null,
                preferredLaneWasDeterministic = policy.shouldInvoke
            )
            val lane = if (policy.shouldInvoke) {
                RiderRenameExecutionLane.HIGH_LEVEL_HANDLER
            } else {
                RiderRenameExecutionLane.BLOCKED
            }
            val blockReason = if (lane == RiderRenameExecutionLane.BLOCKED) {
                // frontend.factory.refused
                "${policy.reason}; second choice refused: ${factoryPlan.reason}"
            } else {
                null
            }

            return RiderRenameExecutionPlan(
                availableHandlerTitles = availableHandlerTitles,
                selectedHandler = selectedHandler,
                selectedHandlerClassName = selectedHandler?.javaClass?.name,
                selectedHandlerTitle = selectedHandler?.let(RenameHandlerRegistry::getHandlerTitle),
                editorLookup = editorLookup,
                dataContext = dataContext,
                policy = policy,
                lane = lane,
                blockReason = blockReason
            )
        } catch (e: com.intellij.openapi.progress.ProcessCanceledException) {
            throw e
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Fail closed: a Rider C# rename whose planning fails must NOT silently fall through
            // to the generic rename lane. Return a BLOCKED plan that preserves editorLookup so the
            // auto-opened editor can still be cleaned up by the caller.
            LOG.debug("Rider rename planning failed; failing closed to BLOCKED lane", e)
            val reason = "rider rename planning failed: ${e.message ?: e.javaClass.simpleName}"
            return RiderRenameExecutionPlan(
                availableHandlerTitles = emptyList(),
                selectedHandler = null,
                selectedHandlerClassName = null,
                selectedHandlerTitle = null,
                editorLookup = editorLookup,
                dataContext = RiderDeterministicDataContext(availableKeys = emptyList(), dataContext = null),
                policy = RiderFrontendPolicyEvaluation(shouldInvoke = false, reason = reason),
                lane = RiderRenameExecutionLane.BLOCKED,
                blockReason = reason
            )
        }
    }

    private suspend fun lookupRiderFrontendEditor(
        project: Project,
        element: PsiElement,
    ): RiderFrontendEditorLookup {
        val lookupSeed = suspendingReadAction {
            val containingFile = element.containingFile
            val virtualFile = containingFile?.virtualFile
            val navigationOffset = (element as? PsiNameIdentifierOwner)?.nameIdentifier?.textRange?.startOffset
                ?: element.textRange?.startOffset
            virtualFile to navigationOffset
        }

        val virtualFile = lookupSeed.first
        val navigationOffset = lookupSeed.second

        if (virtualFile == null) {
            val reason = "declaration file is unavailable for Rider rename lane"
            return RiderFrontendEditorLookup(
                editor = null,
                reason = reason,
                virtualFile = null,
                openedByTool = false
            )
        }

        val lookup = edtAction {
            val fileEditorManager = FileEditorManager.getInstance(project)
            val targetDocument = FileDocumentManager.getInstance().getDocument(virtualFile)

            fun selectedMatchingEditor(): Editor? {
                val document = targetDocument ?: return null
                return fileEditorManager.selectedTextEditor?.takeIf { selected ->
                    selected.document == document
                }
            }

            selectedMatchingEditor()?.let { selectedEditor ->
                return@edtAction RiderFrontendEditorLookup(
                    editor = selectedEditor,
                    reason = "selected text editor matches declaration file",
                    virtualFile = virtualFile,
                    openedByTool = false
                )
            }

            val wasAlreadyOpen = fileEditorManager.isFileOpen(virtualFile)
            fileEditorManager.openFile(virtualFile, true)
            try {
                val openedEditor = selectedMatchingEditor()
                if (openedEditor != null && navigationOffset != null) {
                    val boundedOffset = navigationOffset.coerceIn(0, openedEditor.document.textLength)
                    openedEditor.caretModel.moveToOffset(boundedOffset)
                }

                if (openedEditor != null) {
                    RiderFrontendEditorLookup(
                        editor = openedEditor,
                        reason = if (wasAlreadyOpen) {
                            "focused already-open declaration file for Rider rename lane"
                        } else {
                            "auto-opened declaration file for Rider rename lane"
                        },
                        virtualFile = virtualFile,
                        openedByTool = !wasAlreadyOpen
                    )
                } else {
                    RiderFrontendEditorLookup(
                        editor = null,
                        reason = "no selected text editor matched declaration file after attempting to open it",
                        virtualFile = virtualFile,
                        openedByTool = false
                    )
                }
            } catch (e: Throwable) {
                // If we auto-opened the file and configuring the lookup then failed, close it so we
                // do not leak an editor the caller never learns about.
                if (!wasAlreadyOpen) {
                    runCatching { fileEditorManager.closeFile(virtualFile) }
                }
                throw e
            }
        }
        return lookup
    }

    private suspend fun composeDeterministicRiderDataContext(
        project: Project,
        element: PsiElement,
        newName: String,
        editorLookup: RiderFrontendEditorLookup,
    ): RiderDeterministicDataContext {
        if (editorLookup.editor == null) {
            return RiderDeterministicDataContext(
                availableKeys = emptyList(),
                dataContext = null
            )
        }

        val psiFile = suspendingReadAction { element.containingFile }

        val entries = linkedMapOf(
            CommonDataKeys.PROJECT.name to project,
            CommonDataKeys.PSI_ELEMENT.name to element,
            PlatformCoreDataKeys.PSI_ELEMENT_ARRAY.name to arrayOf(element),
            CommonDataKeys.PSI_FILE.name to psiFile,
            PsiElementRenameHandler.DEFAULT_NAME.name to newName,
            CommonDataKeys.EDITOR.name to editorLookup.editor
        )
        val availableKeys = entries.filterValues { it != null }.keys.toList()
        return RiderDeterministicDataContext(
            availableKeys = availableKeys,
            dataContext = DataContext { dataId -> entries[dataId] }
        )
    }

    /**
     * Converts an absolute path returned by the Rider backend into a project-relative
     * tool-style path (forward slashes, relative to the matching content root).
     *
     * Falls back to the raw absolute path (with normalized separators) when the file is
     * outside any project content root or VFS lookup fails — avoids producing the ugly
     * `..\..\..` style that `Path.relativize` emits for files on different drives.
     */
    private fun toRelativeProjectPath(project: Project, absolutePath: String): String {
        return try {
            val virtualFile = LocalFileSystem.getInstance().findFileByPath(absolutePath)
            if (virtualFile != null) {
                ProjectUtils.getToolFilePath(project, virtualFile)
            } else {
                absolutePath.replace('\\', '/')
            }
        } catch (e: Exception) {
            LOG.debug("Failed to relativize '$absolutePath': ${e.message}")
            absolutePath.replace('\\', '/')
        }
    }

    /**
     * Snapshots project files (path → last-modified timestamp in ms) for diffing
     * after a rename that goes through the Rider HIGH_LEVEL_HANDLER dialog
     * automation lane.
     *
     * The dialog-automation lane mutates many files (AXAML/code-behind pairs,
     * partial classes, callers) but the in-process mutation probe only observes
     * a single file. We walk the project root once before and once after the
     * rename to discover every file the dialog touched.
     *
     * Filtered:
     *  - hidden directories (`.git`, `.idea`, `.vs`, `.gradle`)
     *  - build output (`bin`, `obj`, `out`, `target`, `node_modules`)
     *
     * Best-effort: any failure returns an empty map and callers fall back to
     * the single-file probe observation.
     */
    private fun snapshotProjectFileTimestamps(project: Project): Map<String, Long> {
        val basePath = project.basePath ?: return emptyMap()
        val root = try {
            java.nio.file.Paths.get(basePath)
        } catch (_: Exception) {
            return emptyMap()
        }
        if (!java.nio.file.Files.exists(root)) return emptyMap()
        val snapshot = HashMap<String, Long>(2048)
        try {
            java.nio.file.Files.walkFileTree(root, object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
                override fun preVisitDirectory(
                    dir: java.nio.file.Path,
                    attrs: java.nio.file.attribute.BasicFileAttributes
                ): java.nio.file.FileVisitResult {
                    val name = dir.fileName?.toString() ?: return java.nio.file.FileVisitResult.CONTINUE
                    if (name in EXCLUDED_SNAPSHOT_DIRS || name.startsWith(".")) {
                        return java.nio.file.FileVisitResult.SKIP_SUBTREE
                    }
                    return java.nio.file.FileVisitResult.CONTINUE
                }

                override fun visitFile(
                    file: java.nio.file.Path,
                    attrs: java.nio.file.attribute.BasicFileAttributes
                ): java.nio.file.FileVisitResult {
                    if (attrs.isRegularFile) {
                        try {
                            snapshot[file.toString()] = attrs.lastModifiedTime().toMillis()
                        } catch (_: Exception) {
                            // skip unreadable file
                        }
                    }
                    return java.nio.file.FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(
                    file: java.nio.file.Path,
                    exc: java.io.IOException
                ): java.nio.file.FileVisitResult = java.nio.file.FileVisitResult.CONTINUE
            })
        } catch (e: Exception) {
            LOG.debug("snapshotProjectFileTimestamps failed for $basePath: ${e.message}")
        }
        return snapshot
    }

    /**
     * Returns absolute paths that were added, removed, or whose last-modified
     * timestamp differs between [pre] and [post]. Ordering is undefined.
     */
    private fun diffSnapshotTimestamps(pre: Map<String, Long>, post: Map<String, Long>): List<String> {
        if (pre.isEmpty() && post.isEmpty()) return emptyList()
        val result = ArrayList<String>(8)
        for ((path, postTs) in post) {
            val preTs = pre[path]
            if (preTs == null || preTs != postTs) {
                result.add(path)
            }
        }
        for (path in pre.keys) {
            if (!post.containsKey(path)) {
                result.add(path)
            }
        }
        return result
    }

    /**
     * Validates rename parameters and prepares the element for renaming.
     * Runs in a read action (background thread).
     */
    private fun validateAndPrepare(
        project: Project,
        file: String,
        line: Int,
        column: Int,
        newName: String,
        strictRiderFallbackTargeting: Boolean = false
    ): RenameValidation {
        val psiFile = getPsiFile(project, file)
            ?: return RenameValidation(
                element = DummyNamedElement,
                oldName = "",
                error = "File not found: $file"
            )

        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile)
            ?: return RenameValidation(
                element = DummyNamedElement,
                oldName = "",
                error = "No document available for file: $file"
            )

        val offset = getOffset(document, line, column)
            ?: return RenameValidation(
                element = DummyNamedElement,
                oldName = "",
                error = "No element found at the specified position"
            )

        val psiElement = psiFile.findElementAt(offset)
            ?: return RenameValidation(
                element = DummyNamedElement,
                oldName = "",
                error = "No element found at the specified position"
            )

        val targetResolution = inspectNamedElementCandidateForRename(psiElement, offset)
        val namedElement = targetResolution.candidate
        val candidateName = targetResolution.candidateName

        if (namedElement == null) {
            return RenameValidation(
                element = DummyNamedElement,
                oldName = "",
                error = "No renameable symbol found at the specified position"
            )
        }

        if (strictRiderFallbackTargeting && isUnsupportedRiderFrontendFallbackTarget(namedElement, candidateName)) {
            return RenameValidation(
                element = DummyNamedElement,
                oldName = "",
                error = "Rider frontend fallback could not prove an exact named symbol target at the requested position"
            )
        }

        val oldName = candidateName
            ?: return RenameValidation(
                element = DummyNamedElement,
                oldName = "",
                error = "Element has no name"
            )

        if (oldName == newName) {
            return RenameValidation(
                element = DummyNamedElement,
                oldName = oldName,
                error = "New name is the same as the current name"
            )
        }

        // Validate the new name using language-specific rules
        val validationError = validateNewName(project, namedElement, newName)
        if (validationError != null) {
            return RenameValidation(
                element = DummyNamedElement,
                oldName = oldName,
                error = validationError
            )
        }

        // Check for naming conflicts (would show dialog otherwise)
        val conflictError = checkForConflicts(namedElement, newName)
        if (conflictError != null) {
            return RenameValidation(
                element = DummyNamedElement,
                oldName = oldName,
                error = conflictError
            )
        }

        return RenameValidation(
            element = namedElement,
            oldName = oldName
        )
    }

    /**
     * Validates and prepares a file rename (no line/column — renames the file itself).
     *
     * Uses the PsiFile directly as the rename target, which works for all file types
     * including binary files (images, etc.). The RenameProcessor and its
     * RenamePsiElementProcessor handle language-specific behavior (e.g., Android
     * resource renaming updates all XML references).
     *
     * Skips language-specific identifier validation since file names follow different
     * rules than code identifiers.
     */
    private fun validateAndPrepareFileRename(
        project: Project,
        file: String,
        newName: String
    ): RenameValidation {
        val psiFile = getPsiFile(project, file)
            ?: return RenameValidation(
                element = DummyNamedElement,
                oldName = "",
                error = "File not found: $file"
            )

        val oldName = psiFile.name

        if (oldName == newName) {
            return RenameValidation(
                element = DummyNamedElement,
                oldName = oldName,
                error = "New name is the same as the current name"
            )
        }

        return RenameValidation(
            element = psiFile,
            oldName = oldName
        )
    }

    /**
     * Checks for naming conflicts that would prevent the rename.
     * Returns an error message if conflicts exist, null otherwise.
     */
    private fun checkForConflicts(element: PsiElement, newName: String): String? {
        val processor = RenamePsiElementProcessor.forElement(element)
        val conflicts = MultiMap<PsiElement, String>()

        // Let the processor find existing name conflicts
        processor.findExistingNameConflicts(element, newName, conflicts)

        if (!conflicts.isEmpty) {
            val conflictMessages = conflicts.values().take(3).joinToString("; ")
            val moreCount = conflicts.values().size - 3
            val suffix = if (moreCount > 0) " (and $moreCount more)" else ""
            return "Name conflict: $conflictMessages$suffix"
        }

        return null
    }

    /**
     * Validates the new name using language-specific identifier rules.
     */
    private fun validateNewName(
        project: Project,
        element: PsiElement,
        newName: String
    ): String? {
        val psiFile = element.containingFile ?: return null
        val language = psiFile.language

        val validator = LanguageNamesValidation.INSTANCE.forLanguage(language)

        if (!validator.isIdentifier(newName, project)) {
            return "'$newName' is not a valid identifier in ${language.displayName}"
        }

        if (validator.isKeyword(newName, project)) {
            return "'$newName' is a reserved keyword in ${language.displayName}"
        }

        return null
    }

    /**
     * Executes the rename using IntelliJ's RenameProcessor.
     * Must be called on EDT.
     *
     * HEADLESS OPERATION WITH AUTOMATIC RELATED RENAMES:
     * - Related elements (getters/setters, overriding methods, tests, etc.) are delegated to
     *   IntelliJ's automatic renamer infrastructure
     * - Dialog-producing renamers are force-applied through [HeadlessRenameProcessor]
     * - Constructor parameter -> field coupling is pre-added because the platform only provides
     *   the inverse relation (field -> constructor parameters)
     *
     * @return Pair of (affected files count, related elements renamed count)
     */
    private fun executeRename(
        project: Project,
        element: PsiElement,
        newName: String,
        overrideStrategy: String,
        relatedRenamingStrategy: String,
        affectedFiles: MutableSet<String>
    ): Pair<Int, Int> {
        // Resolve the actual target element to rename based on override strategy.
        // For methods that override a base method, RenameJavaMethodProcessor's
        // substituteElementToRename() calls SuperMethodWarningUtil.checkSuperMethod()
        // which shows a modal dialog. We handle this ourselves based on the strategy:
        // - "rename_base": resolve to deepest super method (no dialog)
        // - "rename_only_current": use the element as-is (no dialog)
        // - "ask": delegate to substituteElementToRename (shows dialog)
        val targetElement = resolveRenameTarget(element, overrideStrategy)

        // Compute the effective name for the rename target.
        //
        // When a PsiFile is substituted to a non-PsiFile (e.g., Android resource element),
        // the target's getName() returns the resource name WITHOUT file extension (e.g.,
        // "ic_launcher" not "ic_launcher.webp"). The RenameProcessor calls setName() with
        // the new name, and the Android plugin's prepareRenaming() appends extensions when
        // generating related file names. Passing a name WITH extension would cause double
        // extensions on related files (e.g., "app_icon.webp.webp").
        //
        // Conversely, when the target remains a PsiFile (no substitution), getName() returns
        // the full filename WITH extension, and setName() expects the same format.
        val effectiveNewName = computeEffectiveNewName(element, targetElement, newName)

        // Create the RenameProcessor with language-appropriate settings.
        // NOTE: We intentionally DON'T search in comments/text occurrences to avoid
        // non-code usage dialogs. The basic rename is more predictable for agents.
        // When relatedRenamingStrategy is "ask", use a standard RenameProcessor so the
        // IDE shows its built-in dialog for each automatic renamer.
        val renameProcessor = if (relatedRenamingStrategy == "ask") {
            RenameProcessor(project, targetElement, effectiveNewName, false, false)
        } else {
            HeadlessRenameProcessor(project, targetElement, effectiveNewName, false, false)
        }

        // Register automatic renamers based on the relatedRenamingStrategy.
        // File rename requests stay file-scoped: do not attach symbol-related automatic renamers
        // or parameter/field coupling because the contract is to preserve declared type names.
        // Factories with null option names are already handled automatically by RenameProcessor.
        if (targetElement !is PsiFile && relatedRenamingStrategy != "none") {
            for (factory in AutomaticRenamerFactory.EP_NAME.extensionList) {
                if (factory.optionName == null) continue
                if (relatedRenamingStrategy == "accessors_and_tests" && !isAccessorOrTestFactory(factory)) continue
                renameProcessor.addRenamerFactory(factory)
            }
        }

        // Add constructor parameter -> field relation up front.
        if (targetElement !is PsiFile) {
            addParameterFieldRelations(project, targetElement, effectiveNewName, renameProcessor)
        }

        // Disable preview dialog for headless operation
        renameProcessor.setPreviewUsages(false)

        // Execute the rename - this modifies files in place (primary + all related elements)
        renameProcessor.run()

        val relatedRenamesCount = renameProcessor.elements.count { it != targetElement }
        for (renamedElement in renameProcessor.elements) {
            renamedElement.containingFile?.virtualFile?.let { vf ->
                affectedFiles.add(getRelativePath(project, vf))
            }
        }

        return Pair(affectedFiles.size, relatedRenamesCount)
    }

    /**
     * Computes the effective name for the rename target, accounting for element substitution
     * during [RenamePsiElementProcessor.prepareRenaming].
     *
     * When a `PsiFile` is passed to `RenameProcessor`, some processors (e.g., Android's
     * `ResourceReferenceRenameProcessor`) swap the `PsiFile` for a higher-level element
     * (like `ResourceReferencePsiElement`) in `prepareRenaming()`. The substitute element
     * uses resource-style naming (without file extension), while `PsiFile` uses filename-style
     * naming (with extension).
     *
     * In the IDE's own rename dialog, this is handled naturally: the dialog shows the element's
     * `getName()` value, so after substitution the user sees the resource name (no extension).
     * For our headless flow, we must detect this substitution and adjust `newName` accordingly.
     *
     * We probe `prepareRenaming` with a temporary map to detect if substitution would occur.
     * This is safe because `prepareRenaming` only creates lightweight wrapper objects.
     *
     * Additionally, when no substitution occurs and the target remains a `PsiFile`, if the
     * user provided a name without extension, the original file's extension is preserved.
     */
    private fun computeEffectiveNewName(
        element: PsiElement,
        targetElement: PsiElement,
        newName: String
    ): String {
        if (element !is PsiFile) return newName

        // Probe: check if prepareRenaming would substitute this PsiFile for a different element.
        // Processors like Android's ResourceReferenceRenameProcessor remove the PsiFile from
        // allRenames and add a ResourceReferencePsiElement instead. That substitute uses
        // resource-style naming (no file extension).
        val processor = RenamePsiElementProcessor.forElement(targetElement)
        val probeRenames = linkedMapOf<PsiElement, String>(targetElement to newName)
        try {
            processor.prepareRenaming(targetElement, newName, probeRenames)
        } catch (_: Exception) {
            // If probing fails, fall through to default behavior
        }

        val wasSubstituted = targetElement !in probeRenames && probeRenames.isNotEmpty()

        if (wasSubstituted) {
            // Element will be substituted (e.g., Android resource) — strip file extension.
            // The substitute's handleElementRename() re-appends extensions per density variant.
            val nameWithoutExt = newName.substringBeforeLast('.')
            return if (nameWithoutExt.isNotEmpty() && nameWithoutExt != newName) nameWithoutExt else newName
        }

        // No substitution — target remains a PsiFile. PsiFile.setName() expects full filename.
        // If the user omitted the extension, preserve the original file's extension.
        val originalExt = element.name.substringAfterLast('.', "")
        if (originalExt.isNotEmpty() && !newName.contains('.')) {
            return "$newName.$originalExt"
        }

        return newName
    }

    /**
     * Checks if an [AutomaticRenamerFactory] is an accessor (getter/setter) or test renamer.
     *
     * Used by the "accessors_and_tests" related renaming strategy to filter factories.
     * Matches by class name suffix to remain language-agnostic (works for Java, Kotlin, etc.).
     */
    private fun isAccessorOrTestFactory(factory: AutomaticRenamerFactory): Boolean {
        val className = factory.javaClass.simpleName
        return className.contains("GetterSetter") ||
            className.contains("Accessor") ||
            className.contains("Test")
    }

    /**
     * Resolves the actual PsiNamedElement to rename based on the override strategy.
     *
     * For methods that override a base method, IntelliJ's substituteElementToRename()
     * calls SuperMethodWarningUtil.checkSuperMethod() which shows a modal dialog.
     *
     * @param overrideStrategy Controls behavior for override methods:
     *   - "rename_base": resolve to deepest super method automatically (no dialog)
     *   - "rename_only_current": use the element as-is, skip substitution (no dialog)
     *   - "ask": delegate to substituteElementToRename (shows IDE dialog)
     */
    private fun resolveRenameTarget(element: PsiElement, overrideStrategy: String): PsiElement {
        val namedElement = element as? PsiNamedElement ?: return element

        when (overrideStrategy) {
            "rename_base" -> {
                // Resolve to the deepest super method to avoid the dialog
                val deepestSuper = resolveDeepestSuperMethod(namedElement)
                if (deepestSuper != null) return deepestSuper
            }
            "rename_only_current" -> {
                // Use the element directly — skip substituteElementToRename entirely
                // to avoid the dialog. Only apply non-dialog substitutions.
                return resolveNonDialogSubstitution(namedElement)
            }
            "ask" -> {
                // Fall through to substituteElementToRename (will show dialog)
            }
        }

        // For non-override elements or "ask" strategy, use standard substitution
        val elementProcessor = RenamePsiElementProcessor.forElement(namedElement)
        return elementProcessor.substituteElementToRename(namedElement, null) ?: namedElement
    }

    /**
     * Applies non-dialog substitutions (e.g., record component for accessor).
     * Skips substituteElementToRename() which would trigger the super method dialog.
     */
    private fun resolveNonDialogSubstitution(element: PsiNamedElement): PsiNamedElement {
        try {
            // Check for record component accessor (Java 16+)
            val recordUtilClass = Class.forName("com.intellij.psi.util.JavaPsiRecordUtil")
            val result = recordUtilClass.getMethod("getRecordComponentForAccessor", Class.forName("com.intellij.psi.PsiMethod"))
                .invoke(null, element)
            if (result is PsiNamedElement) return result
        } catch (e: Exception) {
            LOG.warn("Failed to resolve record component for accessor: ${e.message}", e)
        }
        return element
    }

    /**
     * If the element is a method that overrides a base method, returns the deepest
     * super method. Returns null if the element is not a method or has no super methods.
     *
     * Handles both:
     * - Java/Kotlin PsiMethod (including KtLightMethod) via PsiMethod.findDeepestSuperMethods()
     * - Kotlin KtNamedFunction via KtNamedFunction.getOverriddenDescriptors() (reflection)
     *
     * Uses reflection to access language-specific APIs to keep the tool language-agnostic.
     */
    private fun resolveDeepestSuperMethod(element: PsiNamedElement): PsiNamedElement? {
        // Try Java/Kotlin PsiMethod path (covers KtLightMethod too)
        try {
            val psiMethodClass = Class.forName("com.intellij.psi.PsiMethod")
            if (psiMethodClass.isInstance(element)) {
                val deepestSuperMethods = psiMethodClass.getMethod("findDeepestSuperMethods")
                    .invoke(element) as? Array<*> ?: return null
                if (deepestSuperMethods.isNotEmpty()) {
                    return deepestSuperMethods[0] as? PsiNamedElement
                }
                return null
            }
        } catch (e: Exception) {
            LOG.warn("Failed to resolve deepest super method via PsiMethod API: ${e.message}", e)
        }

        // Try Kotlin KtNamedFunction path — unwrap to light method and use PsiMethod API
        try {
            val ktNamedFunctionClass = Class.forName("org.jetbrains.kotlin.psi.KtNamedFunction")
            if (!ktNamedFunctionClass.isInstance(element)) return null

            // Use LightClassUtils to get the light method wrapper
            val lightClassUtilsClass = Class.forName("org.jetbrains.kotlin.asJava.LightClassUtilsKt")
            val lightElements = lightClassUtilsClass.getMethod("toLightMethods", Class.forName("org.jetbrains.kotlin.psi.KtDeclaration"))
                .invoke(null, element) as? List<*> ?: return null

            val lightMethod = lightElements.firstOrNull() ?: return null

            val psiMethodClass = Class.forName("com.intellij.psi.PsiMethod")
            if (!psiMethodClass.isInstance(lightMethod)) return null

            val deepestSuperMethods = psiMethodClass.getMethod("findDeepestSuperMethods")
                .invoke(lightMethod) as? Array<*> ?: return null

            if (deepestSuperMethods.isNotEmpty()) {
                return deepestSuperMethods[0] as? PsiNamedElement
            }
        } catch (e: Exception) {
            LOG.warn("Failed to resolve deepest super method via Kotlin KtNamedFunction API: ${e.message}", e)
        }

        return null
    }

    /**
     * Detects and adds constructor parameter -> field relationships that IntelliJ does not
     * model automatically.
     *
     * The platform has a built-in automatic renamer for the inverse direction
     * (field -> constructor parameters), but not for parameter -> field. We mirror the
     * Java naming logic so constructor parameters like `ready` can rename related fields
     * such as `isReady` or code-style-prefixed variants.
     *
     * Uses reflection to access Java PSI classes to keep the tool language-agnostic.
     *
     * @return Number of related elements added
     */
    private fun addParameterFieldRelations(
        project: Project,
        element: PsiElement,
        newName: String,
        renameProcessor: RenameProcessor
    ): Int {
        var count = 0

        try {
            val namedElement = element as? PsiNamedElement ?: return 0

            // Check if this is a Java/Kotlin parameter declared on a constructor
            val psiParameterClass = try {
                Class.forName("com.intellij.psi.PsiParameter")
            } catch (e: ClassNotFoundException) {
                return 0 // Java plugin not available
            }

            if (!psiParameterClass.isInstance(namedElement)) {
                return 0
            }

            val declarationScope = namedElement.javaClass.getMethod("getDeclarationScope").invoke(namedElement)
            val psiMethodClass = Class.forName("com.intellij.psi.PsiMethod")
            if (!psiMethodClass.isInstance(declarationScope)) {
                return 0
            }

            val isConstructor = psiMethodClass.getMethod("isConstructor").invoke(declarationScope) as Boolean
            if (!isConstructor) {
                return 0
            }

            val parameterName = namedElement.name ?: return 0
            val containingClass = psiMethodClass.getMethod("getContainingClass").invoke(declarationScope) ?: return 0
            val psiClassClass = Class.forName("com.intellij.psi.PsiClass")
            val javaCodeStyleManagerClass = Class.forName("com.intellij.psi.codeStyle.JavaCodeStyleManager")
            val variableKindClass = Class.forName("com.intellij.psi.codeStyle.VariableKind")

            @Suppress("UNCHECKED_CAST")
            val enumClass = variableKindClass as Class<out Enum<*>>
            val parameterKind = java.lang.Enum.valueOf(enumClass, "PARAMETER")
            val fieldKind = java.lang.Enum.valueOf(enumClass, "FIELD")

            val styleManager = javaCodeStyleManagerClass.getMethod("getInstance", Project::class.java)
                .invoke(null, project)
            val variableNameToPropertyName = javaCodeStyleManagerClass.getMethod(
                "variableNameToPropertyName",
                String::class.java,
                variableKindClass
            )
            val propertyNameToVariableName = javaCodeStyleManagerClass.getMethod(
                "propertyNameToVariableName",
                String::class.java,
                variableKindClass
            )

            val parameterPropertyName = variableNameToPropertyName.invoke(
                styleManager,
                parameterName,
                parameterKind
            ) as? String ?: return 0
            val newPropertyName = variableNameToPropertyName.invoke(
                styleManager,
                newName,
                parameterKind
            ) as? String ?: return 0
            val expectedFieldName = propertyNameToVariableName.invoke(
                styleManager,
                newPropertyName,
                fieldKind
            ) as? String ?: return 0

            val fields = psiClassClass.getMethod("getAllFields").invoke(containingClass) as Array<*>
            for (field in fields) {
                if (field !is PsiNamedElement) continue

                val fieldName = field.name ?: continue
                val fieldPropertyName = variableNameToPropertyName.invoke(
                    styleManager,
                    fieldName,
                    fieldKind
                ) as? String ?: continue

                if (fieldPropertyName != parameterPropertyName) continue
                if (fieldName == expectedFieldName) continue

                renameProcessor.addElement(field, expectedFieldName)
                count++
            }
        } catch (e: Exception) {
            // Reflection failed - likely not a Java/Kotlin project or different PSI structure
            // This is expected for other languages, silently continue
        }

        return count
    }

    /**
     * Finds the named element from a PSI element.
     *
     * First checks if the element itself is a named element (direct declaration hit).
     * Then checks if the element or its close ancestors have PSI references that resolve
     * to a named declaration — this handles cases like Android XML resource references
     * (`@+id/Foo`) where the cursor is inside a reference, not on a declaration.
     * Falls back to walking up the tree for the nearest [PsiNamedElement].
     */
    private fun findNamedElement(element: PsiElement): PsiNamedElement? {
        return resolveNamedElementCandidateForRename(element)
    }

    private fun isUnsupportedRiderFrontendFallbackTarget(element: PsiElement, resolvedName: String?): Boolean {
        if (element is PsiFile || element is PsiDirectory || resolvedName.isNullOrBlank()) {
            return true
        }
        return isUnsupportedRiderFrontendFallbackTargetClass(element.javaClass.name)
    }

    private fun describeElementForTrace(element: PsiElement?): String {
        return describeElementForTraceValue(element)
    }

    /**
     * Dummy placeholder for error cases to satisfy non-null return type.
     */
    @Suppress("DEPRECATION")
    private object DummyNamedElement : PsiNamedElement {
        override fun setName(name: String): PsiElement = this
        override fun getName(): String? = null
        override fun getProject() = throw UnsupportedOperationException()
        override fun getLanguage() = throw UnsupportedOperationException()
        override fun getManager() = throw UnsupportedOperationException()
        override fun getChildren() = throw UnsupportedOperationException()
        override fun getParent() = throw UnsupportedOperationException()
        override fun getFirstChild() = throw UnsupportedOperationException()
        override fun getLastChild() = throw UnsupportedOperationException()
        override fun getNextSibling() = throw UnsupportedOperationException()
        override fun getPrevSibling() = throw UnsupportedOperationException()
        override fun getContainingFile() = throw UnsupportedOperationException()
        override fun getTextRange() = throw UnsupportedOperationException()
        override fun getStartOffsetInParent() = throw UnsupportedOperationException()
        override fun getTextLength() = throw UnsupportedOperationException()
        override fun findElementAt(offset: Int) = throw UnsupportedOperationException()
        override fun findReferenceAt(offset: Int) = throw UnsupportedOperationException()
        override fun getTextOffset() = throw UnsupportedOperationException()
        override fun getText() = throw UnsupportedOperationException()
        override fun textToCharArray() = throw UnsupportedOperationException()
        override fun getNavigationElement() = throw UnsupportedOperationException()
        override fun getOriginalElement() = throw UnsupportedOperationException()
        override fun textMatches(text: CharSequence) = throw UnsupportedOperationException()
        override fun textMatches(element: PsiElement) = throw UnsupportedOperationException()
        override fun textContains(c: Char) = throw UnsupportedOperationException()
        override fun accept(visitor: com.intellij.psi.PsiElementVisitor) = throw UnsupportedOperationException()
        override fun acceptChildren(visitor: com.intellij.psi.PsiElementVisitor) = throw UnsupportedOperationException()
        override fun copy() = throw UnsupportedOperationException()
        override fun add(element: PsiElement) = throw UnsupportedOperationException()
        override fun addBefore(element: PsiElement, anchor: PsiElement?) = throw UnsupportedOperationException()
        override fun addAfter(element: PsiElement, anchor: PsiElement?) = throw UnsupportedOperationException()
        override fun checkAdd(element: PsiElement) = throw UnsupportedOperationException()
        override fun addRange(first: PsiElement, last: PsiElement) = throw UnsupportedOperationException()
        override fun addRangeBefore(first: PsiElement, last: PsiElement, anchor: PsiElement) = throw UnsupportedOperationException()
        override fun addRangeAfter(first: PsiElement, last: PsiElement, anchor: PsiElement) = throw UnsupportedOperationException()
        override fun delete() = throw UnsupportedOperationException()
        override fun checkDelete() = throw UnsupportedOperationException()
        override fun deleteChildRange(first: PsiElement, last: PsiElement) = throw UnsupportedOperationException()
        override fun replace(newElement: PsiElement) = throw UnsupportedOperationException()
        override fun isValid() = false
        override fun isWritable() = false
        override fun getReference() = throw UnsupportedOperationException()
        override fun getReferences() = throw UnsupportedOperationException()
        override fun <T> getCopyableUserData(key: com.intellij.openapi.util.Key<T>) = throw UnsupportedOperationException()
        override fun <T> putCopyableUserData(key: com.intellij.openapi.util.Key<T>, value: T?) = throw UnsupportedOperationException()
        override fun processDeclarations(processor: com.intellij.psi.scope.PsiScopeProcessor, state: com.intellij.psi.ResolveState, lastParent: PsiElement?, place: PsiElement) = throw UnsupportedOperationException()
        override fun getContext() = throw UnsupportedOperationException()
        override fun isPhysical() = false
        override fun getResolveScope() = throw UnsupportedOperationException()
        override fun getUseScope() = throw UnsupportedOperationException()
        override fun getNode() = throw UnsupportedOperationException()
        override fun isEquivalentTo(another: PsiElement?) = false
        override fun getIcon(flags: Int) = throw UnsupportedOperationException()
        override fun <T> getUserData(key: com.intellij.openapi.util.Key<T>): T? = null
        override fun <T> putUserData(key: com.intellij.openapi.util.Key<T>, value: T?) {}
    }
}
