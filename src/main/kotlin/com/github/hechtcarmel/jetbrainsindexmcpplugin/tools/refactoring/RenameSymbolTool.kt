package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.dotnet.RiderBackendSemanticService
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.dotnet.RiderBackendMutationResultMapper
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.dotnet.RiderBackendRenameDiagnostics
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.swing.AbstractButton
import javax.swing.JDialog
import javax.swing.Timer
import javax.swing.JTextField
import javax.swing.text.JTextComponent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
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
        private const val RIDER_BACKEND_EDITOR_SYNC_DELAY_MS = 1_500L
        private const val RIDER_DIALOG_AUTOMATION_TIMEOUT_MS = 15_000L
        private const val RIDER_DIALOG_READINESS_TIMEOUT_MS = 10_000L
        private const val RIDER_DIALOG_READINESS_POLL_MS = 200
        private const val RIDER_DIALOG_SECOND_STAGE_TIMEOUT_MS = 5_000L

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

        internal data class FrontendRenameMutationCheck(
            val verified: Boolean,
            val reason: String
        )

        internal data class FrontendExactTargetCheck(
            val consistent: Boolean,
            val reason: String
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

        internal fun shouldFallbackToFrontendForCompletedRiderSymbolRename(status: String?): Boolean {
            return when (status?.trim()?.lowercase()) {
                "unsupported", "not_supported", "unsupported_context" -> true
                else -> false
            }
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
            mutationCheck: FrontendRenameMutationCheck? = null,
            backendDiagnostics: RiderBackendRenameDiagnostics? = null,
            verification: MutationVerification? = null
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
                    message = "Rider frontend fallback did not apply any rename for '$oldName'; backend reported '$riderFallbackStatus', so the request failed closed.",
                    status = STATUS_UNSUPPORTED_CONTEXT
                )
            }

            if (cameFromRiderFallback && hasObservableChanges && mutationCheck?.verified == false) {
                return RefactoringResult(
                    success = false,
                    affectedFiles = emptyList(),
                    changesCount = 0,
                    message = "Rider frontend fallback reported rename changes for '$oldName', but no real source mutation was verified: ${mutationCheck.reason}.",
                    status = STATUS_NO_OP,
                    verification = verification
                )
            }

            if (cameFromRiderFallback && hasObservableChanges && mutationCheck == null) {
                return RefactoringResult(
                    success = false,
                    affectedFiles = normalizedFiles,
                    changesCount = normalizedChangesCount,
                    message = "Rider frontend fallback reported rename changes for '$oldName', but mutation proof could not be collected for verification.",
                    status = STATUS_FAILED,
                    verification = verification ?: MutationVerification(
                        status = STATUS_FAILED,
                        checksRun = listOf("rename_execution", "mutation_proof"),
                        warnings = listOf("Mutation proof could not be collected after Rider frontend rename execution")
                    )
                )
            }

            val exactTargetCheck = verifyRiderFrontendExactTargetConsistency(oldName, backendDiagnostics)
            if (cameFromRiderFallback && hasObservableChanges && exactTargetCheck?.consistent == false) {
                return RefactoringResult(
                    success = false,
                    affectedFiles = normalizedFiles,
                    changesCount = normalizedChangesCount,
                    message = "Rider frontend fallback applied edits for '$oldName', but exact-target verification failed: ${exactTargetCheck.reason}.",
                    status = STATUS_FAILED,
                    verification = verification?.let {
                        it.copy(
                            warnings = (it.warnings + exactTargetCheck.reason).distinct()
                        )
                    } ?: MutationVerification(
                        status = STATUS_FAILED,
                        checksRun = listOf("rename_execution", "exact_target_consistency"),
                        warnings = listOf(exactTargetCheck.reason)
                    )
                )
            }

            val normalizedVerification = verification?.let { verificationResult ->
                when (verificationResult.status.trim().lowercase()) {
                    "verification_limited", "limited", "verification_failed", STATUS_FAILED -> verificationResult
                    else -> null
                }
            }
            if (cameFromRiderFallback && hasObservableChanges && normalizedVerification != null) {
                return RefactoringResult(
                    success = false,
                    affectedFiles = normalizedFiles,
                    changesCount = normalizedChangesCount,
                    message = "Rider frontend fallback applied edits for '$oldName', but verification remained insufficient: ${normalizedVerification.warnings.joinToString("; ").ifBlank { normalizedVerification.status }}.",
                    status = STATUS_FAILED,
                    verification = normalizedVerification
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
                message = "Successfully renamed '$oldName' to '$newName'$relatedNote",
                verification = verification
            )
        }

        internal fun verifyRiderFrontendExactTargetConsistency(
            oldName: String?,
            backendDiagnostics: RiderBackendRenameDiagnostics?
        ): FrontendExactTargetCheck? {
            backendDiagnostics ?: return null
            val resolutionStatus = backendDiagnostics.resolutionStatus?.trim()?.lowercase()
            if (!resolutionStatus.isNullOrBlank() && resolutionStatus != STATUS_SUCCESS) {
                return FrontendExactTargetCheck(
                    consistent = false,
                    reason = "backend exact-target resolution stayed '$resolutionStatus' instead of proving a successful symbol bind"
                )
            }

            val frontendName = oldName?.trim().orEmpty()
            if (frontendName.isBlank()) {
                return FrontendExactTargetCheck(
                    consistent = false,
                    reason = "frontend rename candidate had no stable name to compare with backend exact-target diagnostics"
                )
            }

            val backendNames = listOfNotNull(
                backendDiagnostics.resolvedName?.trim()?.takeIf { it.isNotEmpty() },
                backendDiagnostics.sourceTokenText?.trim()?.takeIf { it.isNotEmpty() }
            ).distinct()
            if (backendNames.isEmpty()) {
                return null
            }

            if (backendNames.any { it == frontendName }) {
                return FrontendExactTargetCheck(
                    consistent = true,
                    reason = "frontend target '$frontendName' matched backend exact-target diagnostics"
                )
            }

            return FrontendExactTargetCheck(
                consistent = false,
                reason = "backend exact target resolved ${backendNames.joinToString(" / ")} but frontend prepared '$frontendName'"
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
            backendStatus: String?,
            actionReason: String
        ): RefactoringResult {
            val backendNote = backendStatus?.takeIf { it.isNotBlank() }?.let { " Backend reported '$it'." }.orEmpty()
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
                message = "Rider frontend fallback was blocked for '$oldName': $actionReason.$backendNote",
                status = mappedStatus
            )
        }

        internal fun verifyRiderFrontendMutation(
            beforeName: String?,
            afterName: String?,
            beforeFileText: String?,
            afterFileText: String?
        ): FrontendRenameMutationCheck {
            val nameChanged = !beforeName.isNullOrBlank() && !afterName.isNullOrBlank() && beforeName != afterName
            if (nameChanged) {
                return FrontendRenameMutationCheck(true, "target name changed from '$beforeName' to '$afterName'")
            }

            val fileTextChanged = beforeFileText != null && afterFileText != null && beforeFileText != afterFileText
            if (fileTextChanged) {
                return FrontendRenameMutationCheck(true, "target file text changed")
            }

            return FrontendRenameMutationCheck(false, "target name and containing file text remained unchanged")
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

            val selectedCandidate = localCandidate ?: referenceCandidate
            val selectedCandidateName = localCandidateName ?: referenceCandidateName
            val selectedReason = localCandidateReason
                ?: referenceCandidateReason
                ?: "no supported non-container named element found"

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

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val file = requiredStringArg(arguments, "file").getOrElse {
            return createErrorResult(it.message ?: "Missing required parameter: file")
        }
        val line = arguments["line"]?.jsonPrimitive?.int
        val column = arguments["column"]?.jsonPrimitive?.int
        val newName = arguments["newName"]?.jsonPrimitive?.content
            ?: return createErrorResult("Missing required parameter: newName")

        val overrideStrategy = arguments["overrideStrategy"]?.jsonPrimitive?.content ?: "rename_base"
        if (overrideStrategy !in listOf("rename_base", "rename_only_current", "ask")) {
            return createErrorResult("Invalid overrideStrategy: '$overrideStrategy'. Must be 'rename_base', 'rename_only_current', or 'ask'.")
        }

        val relatedRenamingStrategy = arguments["relatedRenamingStrategy"]?.jsonPrimitive?.content ?: "all"
        if (relatedRenamingStrategy !in listOf("all", "none", "accessors_and_tests", "ask")) {
            return createErrorResult("Invalid relatedRenamingStrategy: '$relatedRenamingStrategy'. Must be 'all', 'none', 'accessors_and_tests', or 'ask'.")
        }

        if (newName.isBlank()) {
            return createErrorResult("newName cannot be blank")
        }

        // Validate that line and column are either both present or both absent
        val isFileRename = line == null && column == null
        if (!isFileRename && (line == null || column == null)) {
            return createErrorResult("Both 'line' and 'column' must be provided for symbol rename, or both omitted for file rename.")
        }

        requireSmartMode(project)

        var riderFrontendFallback: RiderRenameOutcome.FallbackToFrontend? = null

        if (RiderBackendSemanticService.isDotNetFile(file)) {
            val riderOutcome = if (isFileRename) {
                tryExecuteRiderFileRename(project, file, newName)
            } else {
                tryExecuteRiderSymbolRename(project, file, line!!, column!!, newName)
            }

            val riderResult = when (riderOutcome) {
                is RiderRenameOutcome.Success -> riderOutcome.result
                is RiderRenameOutcome.FallbackToFrontend -> {
                    riderFrontendFallback = riderOutcome
                    null
                }
                is RiderRenameOutcome.NotInRider -> null
                is RiderRenameOutcome.FileNotFound -> createErrorResult(
                    "File not found in project: '$file'. Pass a path relative to the project root."
                )
                is RiderRenameOutcome.BackendCallFailed -> createErrorResult(
                    "Rider ReSharper backend rename failed: ${riderOutcome.reason}"
                )
            }
            if (riderResult != null) return riderResult
        }

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
                    riderFrontendFallback != null
                )
            }
        }

        if (validation.error != null) {
            return if (riderFrontendFallback != null) {
                createJsonResult(
                    RefactoringResult(
                        success = false,
                        affectedFiles = emptyList(),
                        changesCount = 0,
                        message = validation.error,
                        status = STATUS_UNSUPPORTED_CONTEXT
                    )
                )
            } else {
                createErrorResult(validation.error)
            }
        }

        val element = validation.element
        val oldName = validation.oldName

        var riderExecutionPlan: RiderRenameExecutionPlan? = null

        if (riderFrontendFallback != null) {
            try {
                val actionPlan = planProductionRiderRename(project, element, newName)
                riderExecutionPlan = actionPlan
                if (!actionPlan.policy.shouldInvoke) {
                    val refusalReason = actionPlan.blockReason ?: actionPlan.policy.reason
                    val blocked = buildBlockedRiderFrontendFallbackResult(
                        oldName = oldName,
                        backendStatus = riderFrontendFallback.status,
                        actionReason = refusalReason
                    )
                    return createJsonResult(blocked)
                }
            } catch (e: Exception) {
                LOG.debug("Failed to inspect Rider frontend rename fallback lane", e)
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
        val riderMutationProbe = riderFrontendFallback?.let {
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

        try {
            if (riderFrontendFallback != null && riderPlan?.lane == RiderRenameExecutionLane.HIGH_LEVEL_HANDLER) {
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
                    selectedHandler = selectedHandler,
                    editor = editor,
                    psiFile = psiFile,
                    dataContext = dataContext,
                )
            } else {
                edtAction {
                    val result = executeRename(project, element, newName, overrideStrategy, relatedRenamingStrategy, affectedFiles)
                    changesCount = result.first
                    relatedRenamesCount = result.second
                }
            }

            riderMutationCheck = riderMutationProbe?.let { probe ->
                suspendingReadAction {
                    val afterElement = probe.pointer.element
                    verifyRiderFrontendMutation(
                        beforeName = probe.beforeName,
                        afterName = (afterElement as? PsiNamedElement)?.name,
                        beforeFileText = probe.beforeFileText,
                        afterFileText = probe.targetFilePath
                            ?.let(LocalFileSystem.getInstance()::findFileByPath)
                            ?.let(PsiManager.getInstance(project)::findFile)
                            ?.text
                    )
                }
            }
            if (riderFrontendFallback != null && riderExecutionPlan?.lane == RiderRenameExecutionLane.HIGH_LEVEL_HANDLER && riderMutationCheck?.verified == true) {
                changesCount = 1
                riderMutationProbe?.targetFilePath?.let { affectedFiles += toRelativeProjectPath(project, it) }
            }
        } catch (e: Exception) {
            errorMessage = e.message ?: "Unknown error during rename"
            errorClassName = e::class.java.name
        } finally {
            autoOpenedEditorFile?.let { virtualFile ->
                edtAction {
                    FileEditorManager.getInstance(project).closeFile(virtualFile)
                }
            }
        }

        // Commit and save outside EDT block — commitDocuments uses
        // TransactionGuard.submitTransactionAndWait for write-safe context
        if (errorMessage == null) {
            commitDocuments(project)
            edtAction { FileDocumentManager.getInstance().saveAllDocuments() }
        }

        return if (errorMessage != null) {
            val classification = classifyFrontendRenameFailure(errorMessage, errorClassName, riderFrontendFallback?.status)
            if (classification.status == STATUS_UNSUPPORTED_CONTEXT) {
                createJsonResult(
                    RefactoringResult(
                        success = false,
                        affectedFiles = emptyList(),
                        changesCount = 0,
                        message = classification.userMessage,
                        status = STATUS_UNSUPPORTED_CONTEXT
                    )
                )
            } else {
                createErrorResult(classification.userMessage)
            }
        } else {
            val summary = summarizeFrontendRenameResult(
                oldName = oldName,
                newName = newName,
                relatedRenamesCount = relatedRenamesCount,
                affectedFiles = affectedFiles.toList(),
                changesCount = changesCount,
                riderFallbackStatus = riderFrontendFallback?.status,
                mutationCheck = riderMutationCheck,
                backendDiagnostics = riderFrontendFallback?.backendDiagnostics,
                verification = riderFrontendFallback?.verification
            )

            createJsonResult(summary)
        }
    }

    private sealed interface RiderRenameOutcome {
        data class Success(val result: ToolCallResult) : RiderRenameOutcome
        data class FallbackToFrontend(
            val status: String,
            val reason: String,
            val backendDiagnostics: RiderBackendRenameDiagnostics?,
            val verification: MutationVerification?
        ) : RiderRenameOutcome
        data object NotInRider : RiderRenameOutcome
        data object FileNotFound : RiderRenameOutcome
        data class BackendCallFailed(val reason: String) : RiderRenameOutcome
    }

    private data class RiderFrontendMutationProbe(
        val pointer: com.intellij.psi.SmartPsiElementPointer<PsiElement>,
        val beforeName: String?,
        val targetFilePath: String?,
        val beforeFileText: String?
    )

    private data class RiderDialogAutomationSnapshot(
        val renameDialogSeen: Boolean,
        val handledDialogs: List<String>,
        val failureReason: String?
    )

    private class RiderRenameDialogAutomationController(
        private val newName: String,
    ) {
        private val renameDialogSeen = AtomicBoolean(false)
        private val waitingForSecondDialog = AtomicBoolean(false)
        private val automationCompleted = AtomicBoolean(false)
        private val renameDialogAutomated = CountDownLatch(1)
        private val handledDialogs = ConcurrentLinkedQueue<String>()
        private val failureReason = AtomicReference<String?>(null)
        private val listenerRegistered = AtomicBoolean(false)
        private val primaryDialogTitle = AtomicReference<String?>(null)
        private var renameDialogReadinessTimer: Timer? = null
        private var secondDialogTimeoutTimer: Timer? = null

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
        }

        fun dispose() {
            cancelRenameDialogReadinessTimer()
            cancelSecondDialogTimeout()
            if (listenerRegistered.compareAndSet(true, false)) {
                Toolkit.getDefaultToolkit().removeAWTEventListener(listener)
            }
        }

        fun snapshot(): RiderDialogAutomationSnapshot {
            return RiderDialogAutomationSnapshot(
                renameDialogSeen = renameDialogSeen.get(),
                handledDialogs = handledDialogs.toList(),
                failureReason = failureReason.get()
            )
        }

        fun failTimeout() {
            fail("timed out waiting for Rider rename dialog automation after ${RIDER_DIALOG_AUTOMATION_TIMEOUT_MS}ms", null)
        }

        fun failInvocation(reason: String) {
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
            if (!dialog.isShowing || failureReason.get() != null || automationCompleted.get()) {
                return
            }

            val title = dialog.title?.trim().orEmpty().ifBlank { "<untitled>" }

            when {
                waitingForSecondDialog.get() -> automateFollowUpDialog(dialog, title)
                isRenameDialogTitle(title) -> automateRenameDialog(dialog, title)
            }
        }

        private fun handleDialogClosed(dialog: JDialog) {
            if (!waitingForSecondDialog.get() || automationCompleted.get()) {
                return
            }

            val title = dialog.title?.trim().orEmpty().ifBlank { "<untitled>" }
            if (!title.equals(primaryDialogTitle.get(), ignoreCase = true)) {
                return
            }
        }

        private fun automateRenameDialog(dialog: JDialog, title: String) {
            if (!renameDialogSeen.compareAndSet(false, true)) {
                return
            }

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
            renameDialogReadinessTimer = Timer(200) { timerEvent ->
                if (!dialog.isShowing || failureReason.get() != null || automationCompleted.get()) {
                    (timerEvent.source as? Timer)?.stop()
                    renameDialogReadinessTimer = null
                    return@Timer
                }
                if (attemptRenameDialogAutomationWhenReady(dialog, title)) {
                    (timerEvent.source as? Timer)?.stop()
                    renameDialogReadinessTimer = null
                    return@Timer
                }
                if (System.currentTimeMillis() >= deadline) {
                    (timerEvent.source as? Timer)?.stop()
                    renameDialogReadinessTimer = null
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
            primaryDialogTitle.set(title)
            handledDialogs += title
            val shouldAwaitSecondDialog = shouldAwaitSecondDialogAfterPrimarySubmit(button.text)
            if (shouldAwaitSecondDialog) {
                waitingForSecondDialog.set(true)
                scheduleSecondDialogTimeout(title)
            }
            button.doClick()
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
            val button = when {
                title.contains("Refactor", ignoreCase = true) ||
                    title.contains("Preview", ignoreCase = true) ||
                    title.contains("Conflict", ignoreCase = true) -> {
                    findButton(dialog, listOf("Do Refactor", "Refactor", "Continue", "OK"))
                }

                else -> findButton(dialog, listOf("Do Refactor", "Refactor", "Continue"))
            } ?: return

            handledDialogs += title
            cancelSecondDialogTimeout()
            waitingForSecondDialog.set(false)
            button.doClick()
            completeAutomation(
                details = "title=$title success=true newName=$newName secondDialog=true"
            )
        }

        private fun isRenameDialogTitle(title: String): Boolean {
            return title.equals("Rename", ignoreCase = true) || title.contains("Rename", ignoreCase = true)
        }

        private fun fail(reason: String, dialog: JDialog?) {
            if (!failureReason.compareAndSet(null, reason)) {
                return
            }
            cancelRenameDialogReadinessTimer()
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
                completeAutomation(
                    details = "title=$title success=true newName=$newName secondDialog=false fallback=timeout"
                )
            }.apply {
                isRepeats = false
                start()
            }
        }

        private fun cancelSecondDialogTimeout() {
            secondDialogTimeoutTimer?.stop()
            secondDialogTimeoutTimer = null
        }

        private fun cancelRenameDialogReadinessTimer() {
            renameDialogReadinessTimer?.stop()
            renameDialogReadinessTimer = null
        }

        private fun completeAutomation(details: String) {
            cancelRenameDialogReadinessTimer()
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

    private suspend fun executeRiderHandlerWithDialogAutomation(
        project: Project,
        newName: String,
        selectedHandler: RenameHandler,
        editor: Editor,
        psiFile: PsiFile,
        dataContext: DataContext,
    ): RiderDialogAutomationSnapshot {
        val dialogAutomation = RiderRenameDialogAutomationController(newName)
        try {
            edtAction {
                dialogAutomation.install()
                ApplicationManager.getApplication().invokeLater(
                    {
                        try {
                            selectedHandler.invoke(project, editor, psiFile, dataContext)
                        } catch (t: Throwable) {
                            dialogAutomation.failInvocation(
                                "Rider rename handler threw before dialog automation could complete: ${t.message ?: t.javaClass.name}",
                            )
                        }
                    },
                    ModalityState.any()
                )
            }

            val completedInTime = dialogAutomation.awaitRenameDialogAutomation(RIDER_DIALOG_AUTOMATION_TIMEOUT_MS)

            if (!completedInTime) {
                edtAction {
                    dialogAutomation.failTimeout()
                    dialogAutomation.cancelOutstandingDialogs()
                }
                throw IllegalStateException(
                    "Rider rename dialog automation timed out after ${RIDER_DIALOG_AUTOMATION_TIMEOUT_MS}ms"
                )
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
            return automationSnapshot
        } finally {
            edtAction {
                dialogAutomation.dispose()
            }
        }
    }

    private suspend fun tryExecuteRiderSymbolRename(
        project: Project,
        file: String,
        line: Int,
        column: Int,
        newName: String
    ): RiderRenameOutcome {
        commitDocuments(project)
        edtAction { FileDocumentManager.getInstance().saveAllDocuments() }

        val virtualFile = suspendingReadAction {
            val psiFile = getPsiFile(project, file) ?: return@suspendingReadAction null
            psiFile.virtualFile
        } ?: return RiderRenameOutcome.FileNotFound
        val absolutePath = virtualFile.path

        var openedByTool = false
        runCatching {
            openedByTool = edtAction {
                val fileEditorManager = FileEditorManager.getInstance(project)
                val wasOpen = fileEditorManager.isFileOpen(virtualFile)
                val document = FileDocumentManager.getInstance().getDocument(virtualFile)
                val safeLineIndex = if (document != null && document.lineCount > 0) {
                    (line - 1).coerceIn(0, document.lineCount - 1)
                } else {
                    0
                }
                val safeColumnIndex = (column - 1).coerceAtLeast(0)
                val safeOffset = if (document != null && document.lineCount > 0) {
                    (document.getLineStartOffset(safeLineIndex) + safeColumnIndex)
                        .coerceIn(0, document.textLength)
                } else {
                    0
                }

                val editor = fileEditorManager.openTextEditor(
                    OpenFileDescriptor(project, virtualFile, safeLineIndex, safeColumnIndex),
                    true
                ) ?: fileEditorManager.getEditors(virtualFile)
                    .filterIsInstance<TextEditor>()
                    .firstOrNull { !it.editor.isDisposed }
                    ?.editor
                if (editor != null && document != null && editor.document == document && document.lineCount > 0) {
                    editor.caretModel.moveToOffset(safeOffset)
                }

                !wasOpen
            }
        }

        if (openedByTool) {
            kotlinx.coroutines.delay(RIDER_BACKEND_EDITOR_SYNC_DELAY_MS)
        }

        try {
            val model = RdProtocolBridge.getModel(project) ?: return RiderRenameOutcome.NotInRider
            val position = RdProtocolBridge.createStruct("$MODEL_PKG.RdSourcePosition", absolutePath, line, column)
                ?: return RiderRenameOutcome.BackendCallFailed("Failed to create rd source position struct (rdgen mismatch?)")
            val request = RdProtocolBridge.createStruct("$MODEL_PKG.RdRenameSymbolRequest", position, newName)
                ?: return RiderRenameOutcome.BackendCallFailed("Failed to create rd rename request struct (rdgen mismatch?)")

            val outcome = RdProtocolBridge.invokeCallResult(model, "renameSymbol", request)

            val result = when (outcome) {
                is com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.dotnet.RdCallOutcome.Success -> {
                    outcome.value ?: return RiderRenameOutcome.BackendCallFailed(
                        "Backend rd call returned no result (timeout, fault, or cancellation; check IDE log for details)"
                    )
                }
                is com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.dotnet.RdCallOutcome.Timeout -> {
                    return RiderRenameOutcome.BackendCallFailed("Backend rd call returned no result (timeout, fault, or cancellation; check IDE log for details)")
                }
                is com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.dotnet.RdCallOutcome.Failure -> {
                    return RiderRenameOutcome.BackendCallFailed("Backend rd call returned no result (timeout, fault, or cancellation; check IDE log for details)")
                }
            }

            return mapRiderRenameResult(project, result, allowFrontendFallback = true)
        } finally {
            if (openedByTool) {
                edtAction {
                    FileEditorManager.getInstance(project).closeFile(virtualFile)
                }
            }
        }
    }

    private suspend fun tryExecuteRiderFileRename(
        project: Project,
        file: String,
        newName: String
    ): RiderRenameOutcome {
        commitDocuments(project)
        edtAction { FileDocumentManager.getInstance().saveAllDocuments() }

        val absolutePath = suspendingReadAction {
            val psiFile = getPsiFile(project, file) ?: return@suspendingReadAction null
            psiFile.virtualFile.path
        } ?: return RiderRenameOutcome.FileNotFound

        val model = RdProtocolBridge.getModel(project) ?: return RiderRenameOutcome.NotInRider
        val request = RdProtocolBridge.createStruct("$MODEL_PKG.RdRenameFileRequest", absolutePath, newName)
            ?: return RiderRenameOutcome.BackendCallFailed("Failed to create rd rename file request struct (rdgen mismatch?)")

        val outcome = RdProtocolBridge.invokeCallResult(model, "renameFile", request)

        val result = when (outcome) {
            is com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.dotnet.RdCallOutcome.Success -> {
                outcome.value ?: return RiderRenameOutcome.BackendCallFailed(
                    "Backend rd call returned no result (timeout, fault, or cancellation; check IDE log for details)"
                )
            }
            is com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.dotnet.RdCallOutcome.Timeout -> {
                return RiderRenameOutcome.BackendCallFailed("Backend rd call returned no result (timeout, fault, or cancellation; check IDE log for details)")
            }
            is com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.dotnet.RdCallOutcome.Failure -> {
                return RiderRenameOutcome.BackendCallFailed("Backend rd call returned no result (timeout, fault, or cancellation; check IDE log for details)")
            }
        }

        return mapRiderRenameResult(project, result, allowFrontendFallback = false)
    }

    private suspend fun mapRiderRenameResult(
        project: Project,
        result: Any,
        allowFrontendFallback: Boolean = false
    ): RiderRenameOutcome {
        val backendResult = RiderBackendMutationResultMapper.fromRdResult(result)
        val success = backendResult.success
        val message = backendResult.message ?: "Rider backend rename failed"
        val status = backendResult.status
            ?: return RiderRenameOutcome.BackendCallFailed("Backend rename result omitted required status field")

        val rawAffectedFiles = backendResult.affectedFiles
        val affectedFiles = rawAffectedFiles.map { absolute -> toRelativeProjectPath(project, absolute) }
        val changesCount = backendResult.changesCount
        val verification = backendResult.verification?.status?.let { verificationStatus ->
            MutationVerification(
                status = verificationStatus,
                checksRun = backendResult.verification.checksRun,
                warnings = backendResult.verification.warnings
            )
        }

        if (allowFrontendFallback && shouldFallbackToFrontendForCompletedRiderSymbolRename(status)) {
            return RiderRenameOutcome.FallbackToFrontend(
                status = status,
                reason = message,
                backendDiagnostics = backendResult.renameDiagnostics,
                verification = verification
            )
        }

        val extraRefreshPaths = buildList {
            addAll(rawAffectedFiles)
            (RdProtocolBridge.getProperty(result, "oldPath") as? String)?.takeIf { it.isNotBlank() }?.let(::add)
            (RdProtocolBridge.getProperty(result, "newPath") as? String)?.takeIf { it.isNotBlank() }?.let(::add)
        }.distinct()

        refreshAffectedFiles(extraRefreshPaths)
        refreshProjectRootsAndCommit(project)
        commitDocuments(project)
        edtAction { FileDocumentManager.getInstance().saveAllDocuments() }

        val summary = RiderMutationResultMapper.summary(
            legacySuccess = success,
            status = status,
            affectedFiles = affectedFiles,
            changesCount = changesCount,
            message = message,
            verification = verification
        )

        return RiderRenameOutcome.Success(createJsonResult(summary.toRefactoringResult()))
    }

    private fun assessPreferredRiderRenameHandler(selectedHandler: RenameHandler?): PreferredRiderActionLanePlan {
        return assessPreferredRiderRenameHandlerClassName(selectedHandler?.javaClass?.name)
    }

    private suspend fun planProductionRiderRename(
        project: Project,
        element: PsiElement,
        newName: String,
    ): RiderRenameExecutionPlan {
        val editorLookup = lookupRiderFrontendEditor(project, element)
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

    private fun toMutationVerification(rawVerification: Any?): MutationVerification? {
        return RiderMutationResultMapper.toMutationVerification(rawVerification, RdProtocolBridge::getProperty)
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

    private fun refreshAffectedFiles(paths: List<String>) {
        val virtualFiles = paths.mapNotNull { path ->
            LocalFileSystem.getInstance().refreshAndFindFileByPath(path.replace('\\', File.separatorChar))
        }
        if (virtualFiles.isNotEmpty()) {
            VfsUtil.markDirtyAndRefresh(false, false, false, *virtualFiles.toTypedArray())
        }
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
        // Factories with null option names are already handled automatically by RenameProcessor.
        if (relatedRenamingStrategy != "none") {
            for (factory in AutomaticRenamerFactory.EP_NAME.extensionList) {
                if (factory.optionName == null) continue
                if (relatedRenamingStrategy == "accessors_and_tests" && !isAccessorOrTestFactory(factory)) continue
                renameProcessor.addRenamerFactory(factory)
            }
        }

        // Add constructor parameter -> field relation up front.
        addParameterFieldRelations(project, targetElement, effectiveNewName, renameProcessor)

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
