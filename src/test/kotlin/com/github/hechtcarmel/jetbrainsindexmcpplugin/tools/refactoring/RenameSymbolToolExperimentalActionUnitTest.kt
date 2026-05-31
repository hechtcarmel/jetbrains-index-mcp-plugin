package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import junit.framework.TestCase
import java.awt.Container
import kotlinx.coroutines.runBlocking
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JPanel

class RenameSymbolToolExperimentalActionUnitTest : TestCase() {

    fun testPreferredHighLevelLaneAllowsProvenRiderBackendRenameHandler() {
        val assessment = RenameSymbolTool.assessPreferredRiderRenameHandlerClassName(
            "com.jetbrains.rdclient.actions.impl.BackendRenameHandler"
        )

        assertTrue(assessment.shouldInvoke)
        assertEquals("selected rename handler is on the proven Rider non-modal allowlist", assessment.reason)
    }

    fun testPreferredHighLevelLaneRejectsModalPsiElementRenameHandler() {
        val assessment = RenameSymbolTool.assessPreferredRiderRenameHandlerClassName(
            "com.intellij.refactoring.rename.PsiElementRenameHandler"
        )

        assertFalse(assessment.shouldInvoke)
        assertEquals("selected rename handler would show modal UI outside unit test mode", assessment.reason)
    }

    fun testPreferredHighLevelLaneAcceptsSingleSafeHandler() {
        val plan = RenameSymbolTool.evaluatePreferredRiderActionLane(
            availableHandlerTitles = listOf("Rename symbol"),
            selectedHandlerClassName = "com.jetbrains.rdclient.actions.impl.BackendRenameHandler",
            selectedHandlerIsKnownSafeNonModal = true,
            selectedHandlerBlockReason = null
        )

        assertTrue(plan.shouldInvoke)
        assertEquals("single deterministic non-modal rename handler is available", plan.reason)
    }

    fun testPreferredHighLevelLaneRejectsMultipleHandlersToAvoidChooserUi() {
        val plan = RenameSymbolTool.evaluatePreferredRiderActionLane(
            availableHandlerTitles = listOf("Rename symbol", "Rename file"),
            selectedHandlerClassName = null,
            selectedHandlerIsKnownSafeNonModal = false,
            selectedHandlerBlockReason = null
        )

        assertFalse(plan.shouldInvoke)
        assertEquals("multiple rename handlers would require chooser UI", plan.reason)
    }

    fun testPreferredHighLevelLaneRejectsNonDeterministicHandlerSelection() {
        val plan = RenameSymbolTool.evaluatePreferredRiderActionLane(
            availableHandlerTitles = listOf("Rename symbol"),
            selectedHandlerClassName = null,
            selectedHandlerIsKnownSafeNonModal = false,
            selectedHandlerBlockReason = null
        )

        assertFalse(plan.shouldInvoke)
        assertEquals("rename handler selection did not resolve a concrete handler", plan.reason)
    }

    fun testPreferredHighLevelLaneRejectsModalUnsafeHandler() {
        val plan = RenameSymbolTool.evaluatePreferredRiderActionLane(
            availableHandlerTitles = listOf("Rename symbol"),
            selectedHandlerClassName = "com.intellij.refactoring.rename.PsiElementRenameHandler",
            selectedHandlerIsKnownSafeNonModal = false,
            selectedHandlerBlockReason = "selected rename handler would show modal UI outside unit test mode"
        )

        assertFalse(plan.shouldInvoke)
        assertEquals("selected rename handler would show modal UI outside unit test mode", plan.reason)
    }

    fun testBlockedRiderFrontendFallbackMapsActiveEditorRequirementToNeedsActiveEditor() {
        val result = RenameSymbolTool.buildBlockedRiderFrontendFallbackResult(
            oldName = "WidgetService",
            actionReason = "active editor is required for Rider rename lane"
        )

        assertFalse(result.success)
        assertEquals("needs_active_editor", result.status)
        assertTrue(result.message.contains("active editor is required"))
    }

    fun testBlockedRiderFrontendFallbackMapsModalAndPreviewReasonsToConflict() {
        listOf(
            "production handler invoke would show modal UI",
            "rename preview would require user interaction"
        ).forEach { reason ->
            val result = RenameSymbolTool.buildBlockedRiderFrontendFallbackResult(
                oldName = "WidgetService",
                actionReason = reason
            )

            assertFalse("reason=$reason should be non-success", result.success)
            assertEquals("reason=$reason should map to conflict", "conflict", result.status)
        }
    }

    fun testClassifyFrontendRenameFailureMapsNotSupportedMessageToUnsupportedStatus() {
        val classification = RenameSymbolTool.classifyFrontendRenameFailure(
            message = "Rename: Operation is not supported",
            exceptionClassName = "java.lang.IllegalStateException",
            riderFallbackStatus = "unsupported"
        )

        assertEquals("unsupported_context", classification.status)
        assertTrue(classification.userMessage.contains("Operation is not supported"))
    }

    fun testClassifyFrontendRenameFailureKeepsGenericErrorsAsErrors() {
        val classification = RenameSymbolTool.classifyFrontendRenameFailure(
            message = "Index is stale",
            exceptionClassName = "java.lang.IllegalStateException",
            riderFallbackStatus = "unsupported"
        )

        assertNull(classification.status)
        assertEquals("Rename failed: Index is stale", classification.userMessage)
    }

    fun testBlockedRiderFrontendFallbackMapsOtherFailClosedReasonsToUnsupportedContext() {
        val result = RenameSymbolTool.buildBlockedRiderFrontendFallbackResult(
            oldName = "WidgetService",
            actionReason = "experimental action fallback disabled"
        )

        assertFalse(result.success)
        assertEquals("unsupported_context", result.status)
        assertTrue(result.message.contains("experimental action fallback disabled"))
    }

    fun testActiveEditorFeasibilityFailsClosedWhenDeclarationEditorIsMissing() {
        val feasibility = RenameSymbolTool.evaluateRiderFrontendFeasibility(
            hasDeclarationEditor = false,
            canComposeDataContext = false
        )

        assertFalse(feasibility.canProceed)
        assertEquals("active editor is required for Rider rename lane", feasibility.reason)
    }

    fun testActiveEditorFeasibilityAllowsPlanningWhenEditorAndContextExist() {
        val feasibility = RenameSymbolTool.evaluateRiderFrontendFeasibility(
            hasDeclarationEditor = true,
            canComposeDataContext = true
        )

        assertTrue(feasibility.canProceed)
        assertEquals("declaration editor and deterministic data context are available", feasibility.reason)
    }

    fun testSecondChoiceRefactoringFactoryLaneIsExplicitlyRefusedWithoutSafetyProof() {
        val plan = RenameSymbolTool.evaluateSecondChoiceRiderRefactoringFactoryLane(
            hasDeclarationEditor = true,
            canComposeDataContext = true,
            preferredLaneWasDeterministic = false
        )

        assertFalse(plan.shouldInvoke)
        assertTrue(plan.reason.contains("second choice"))
        assertTrue(plan.reason.contains("RefactoringFactory.createRename"))
        assertTrue(plan.reason.contains("non-modal"))
    }

    fun testSourceGatesRefactoringFactoryFallbackAsSecondChoiceLane() {
        val source = renameToolSource()

        assertTrue(source.contains("RefactoringFactory.createRename("))
        assertTrue(source.contains("second choice"))
        assertTrue(source.contains("frontend.factory.policy"))
        assertTrue(source.contains("frontend.factory.refused"))
    }

    fun testSourceAutoOpensRiderDeclarationEditorBeforeInvokingBackendHandler() {
        val source = renameToolSource()

        assertTrue(source.contains("openFile(virtualFile, true)"))
        assertTrue(source.contains("caretModel.moveToOffset"))
        assertTrue(source.contains("closeFile(virtualFile)"))
        assertTrue(source.contains("auto-opened declaration file for Rider rename lane"))
    }

    fun testVerifyRiderFrontendMutationFailsWhenNameAndTextStayUnchanged() {
        val check = RenameSymbolTool.verifyRiderFrontendMutation(
            beforeName = "OldName",
            afterName = "OldName",
            newName = "NewName",
            beforeFileText = "class OldName {}",
            afterFileText = "class OldName {}"
        )

        assertFalse(check.verified)
        assertTrue(check.reason.contains("does not match"))
    }

    fun testVerifyRiderFrontendMutationSucceedsWhenTargetNameChanges() {
        val check = RenameSymbolTool.verifyRiderFrontendMutation(
            beforeName = "OldName",
            afterName = "NewName",
            newName = "NewName",
            beforeFileText = "class OldName {}",
            afterFileText = "class OldName {}"
        )

        assertTrue(check.verified)
        assertTrue(check.reason.contains("matching the requested rename"))
    }

    fun testMutationPollingAcceptsRenamedContainerFileWhenOriginalPathDisappears() {
        var attempts = 0

        val result = runBlocking {
            RenameSymbolTool.pollRiderFrontendMutationVerification(
                initialDelayMs = 200L,
                timeoutMs = 1_000L,
                initialPollIntervalMs = 50L,
                maxPollIntervalMs = 200L,
                sleep = {}
            ) {
                attempts += 1
                if (attempts < 3) {
                    RenameSymbolTool.verifyRiderFrontendMutation(
                        beforeName = null,
                        afterName = null,
                        newName = "ISmokeModelDocumentationProvider",
                        beforeFileText = "interface IModelDocumentationProvider {}",
                        afterFileText = null,
                        pathEvidence = RenameSymbolTool.frontendRenamePathEvidence(
                            originalFilePath = "src/IModelDocumentationProvider.cs",
                            originalPathExists = true,
                            observedFilePath = null
                        )
                    )
                } else {
                    RenameSymbolTool.verifyRiderFrontendMutation(
                        beforeName = null,
                        afterName = null,
                        newName = "ISmokeModelDocumentationProvider",
                        beforeFileText = "interface IModelDocumentationProvider {}",
                        afterFileText = null,
                        pathEvidence = RenameSymbolTool.frontendRenamePathEvidence(
                            originalFilePath = "src/IModelDocumentationProvider.cs",
                            originalPathExists = false,
                            observedFilePath = "src/ISmokeModelDocumentationProvider.cs",
                            candidatePaths = listOf("src/ISmokeModelDocumentationProvider.cs")
                        )
                    )
                }
            }
        }

        assertTrue(result.check.verified)
        assertEquals("src/ISmokeModelDocumentationProvider.cs", result.check.observedFilePath)
        assertEquals(3, attempts)
        assertEquals(3, result.attemptCount)
        assertEquals(350L, result.totalWaitMs)
    }

    fun testDialogAutomationWaitsForSecondDialogOnlyAfterNext() {
        assertTrue(RenameSymbolTool.shouldAwaitSecondDialogAfterPrimarySubmit("Next"))
        assertTrue(RenameSymbolTool.shouldAwaitSecondDialogAfterPrimarySubmit(" next "))
        assertFalse(RenameSymbolTool.shouldAwaitSecondDialogAfterPrimarySubmit("Refactor"))
        assertFalse(RenameSymbolTool.shouldAwaitSecondDialogAfterPrimarySubmit("Rename"))
        assertFalse(RenameSymbolTool.shouldAwaitSecondDialogAfterPrimarySubmit("OK"))
        assertFalse(RenameSymbolTool.shouldAwaitSecondDialogAfterPrimarySubmit(null))
    }

    fun testDialogAutomationRecognizesSecondDialogTitles() {
        assertTrue(RenameSymbolTool.isSecondDialogCandidateTitle("Refactoring Preview"))
        assertTrue(RenameSymbolTool.isSecondDialogCandidateTitle("Rename Conflicts"))
        assertTrue(RenameSymbolTool.isSecondDialogCandidateTitle("Rename"))
        assertFalse(RenameSymbolTool.isSecondDialogCandidateTitle("Settings"))
        assertFalse(RenameSymbolTool.isSecondDialogCandidateTitle(""))
    }

    fun testFollowUpDialogPlanRecognizesRelatedSymbolsSecondRenameDialog() {
        val plan = RenameSymbolTool.planRiderRenameFollowUpDialog(
            title = "Rename",
            visibleTexts = listOf(
                "There are declarations that seem to have names related to the name of the type to be renamed"
            ),
            visibleButtonLabels = listOf("Back", "Next", "Cancel"),
            relatedRenamingStrategy = "none"
        )

        assertEquals("continue", plan.action)
        assertEquals("Next", plan.submitButtonLabel)
        assertFalse(plan.shouldCompleteAutomationAfterSubmit)
        assertTrue(plan.shouldDisableRelatedSymbols)
        assertFalse(plan.requiresRenameTextInput)
        assertNull(plan.failureReason)
    }

    fun testFollowUpDialogPlanKeepsWaitingAfterRelatedSymbolsNext() {
        val plan = RenameSymbolTool.planRiderRenameFollowUpDialog(
            title = "Rename",
            visibleTexts = listOf(
                "There are declarations that seem to have names related to the name of the type to be renamed"
            ),
            visibleButtonLabels = listOf("Back", "Next", "Cancel"),
            relatedRenamingStrategy = "none"
        )

        assertEquals("continue", plan.action)
        assertEquals("Next", plan.submitButtonLabel)
        assertFalse(plan.shouldCompleteAutomationAfterSubmit)
    }

    fun testFollowUpDialogPlanTreatsRenameNextStepAsRelatedSymbolsWhenStrategyIsNone() {
        val plan = RenameSymbolTool.planRiderRenameFollowUpDialog(
            title = "Rename",
            visibleTexts = emptyList(),
            visibleButtonLabels = listOf("Back", "Next", "Cancel"),
            relatedRenamingStrategy = "none"
        )

        assertEquals("continue", plan.action)
        assertEquals("Next", plan.submitButtonLabel)
        assertTrue(plan.shouldDisableRelatedSymbols)
        assertFalse(plan.shouldCompleteAutomationAfterSubmit)
    }

    fun testFollowUpDialogPlanCompletesAfterTerminalRefactorButton() {
        val plan = RenameSymbolTool.planRiderRenameFollowUpDialog(
            title = "Rename",
            visibleTexts = listOf("Ready to finish rename"),
            visibleButtonLabels = listOf("Refactor", "Cancel"),
            relatedRenamingStrategy = "none"
        )

        assertEquals("continue", plan.action)
        assertEquals("Refactor", plan.submitButtonLabel)
        assertTrue(plan.shouldCompleteAutomationAfterSubmit)
    }

    fun testTerminalFollowUpButtonsExcludeNextAndIncludeRefactorFinishRenameAndSafeOk() {
        assertFalse(RenameSymbolTool.isTerminalRiderFollowUpSubmitButton("Next"))
        assertTrue(RenameSymbolTool.isTerminalRiderFollowUpSubmitButton("Refactor"))
        assertTrue(RenameSymbolTool.isTerminalRiderFollowUpSubmitButton("Finish"))
        assertTrue(RenameSymbolTool.isTerminalRiderFollowUpSubmitButton("Rename"))
        assertTrue(RenameSymbolTool.isTerminalRiderFollowUpSubmitButton("OK"))
    }

    fun testRiderFrontendMutationCheckDelayAppliesAfterAsyncFollowUp() {
        assertEquals(200L, RenameSymbolTool.riderFrontendMutationCheckDelayMs(usedAsyncFollowUpClick = true))
        assertEquals(0L, RenameSymbolTool.riderFrontendMutationCheckDelayMs(usedAsyncFollowUpClick = false))
    }

    fun testFollowUpDialogPlanRecognizesUnsupportedOperationDialog() {
        val plan = RenameSymbolTool.planRiderRenameFollowUpDialog(
            title = "Rename",
            visibleTexts = listOf("Operation is not supported"),
            visibleButtonLabels = listOf("OK"),
            relatedRenamingStrategy = "none"
        )

        assertEquals("fail_unsupported", plan.action)
        assertNull(plan.submitButtonLabel)
        assertFalse(plan.shouldDisableRelatedSymbols)
        assertFalse(plan.requiresRenameTextInput)
        assertNotNull(plan.failureReason)
        assertTrue(plan.failureReason!!.contains("Operation is not supported"))
    }

    fun testSecondStageTimeoutFailsClosedWhileRenameDialogRemainsVisible() {
        val decision = RenameSymbolTool.evaluateRiderSecondStageTimeout(
            visibleDialogTitles = listOf("Rename")
        )

        assertFalse(decision.shouldCompleteSuccess)
        assertNotNull(decision.failureReason)
        assertTrue(decision.failureReason!!.contains("remained visible"))
    }

    fun testSecondStageTimeoutCompletesOnlyAfterRenameDialogsDisappear() {
        val decision = RenameSymbolTool.evaluateRiderSecondStageTimeout(
            visibleDialogTitles = emptyList()
        )

        assertTrue(decision.shouldCompleteSuccess)
        assertNull(decision.failureReason)
    }

    fun testAutomationTimeoutDefersToMutationVerificationOnlyAfterAsyncFollowUpAndNoVisibleDialogs() {
        val decision = RenameSymbolTool.evaluateRiderAutomationTimeout(
            waitingForSecondDialog = true,
            usedAsyncFollowUpClick = true,
            visibleDialogTitles = emptyList()
        )

        assertTrue(decision.shouldDeferToMutationVerification)
        assertFalse(decision.shouldFail)
        assertEquals("defer-to-mutation-check", decision.traceStatus)
        assertNull(decision.failureReason)
    }

    fun testAutomationTimeoutFailsClosedWhenVisibleFollowUpDialogRemains() {
        val decision = RenameSymbolTool.evaluateRiderAutomationTimeout(
            waitingForSecondDialog = true,
            usedAsyncFollowUpClick = true,
            visibleDialogTitles = listOf("Rename")
        )

        assertFalse(decision.shouldDeferToMutationVerification)
        assertTrue(decision.shouldFail)
        assertEquals("timeout-visible-dialog", decision.traceStatus)
        assertNotNull(decision.failureReason)
        assertTrue(decision.failureReason!!.contains("remained visible"))
    }

    fun testAutomationTimeoutFailsClosedWhenNoAsyncFollowUpWasTriggered() {
        val decision = RenameSymbolTool.evaluateRiderAutomationTimeout(
            waitingForSecondDialog = false,
            usedAsyncFollowUpClick = false,
            visibleDialogTitles = emptyList()
        )

        assertFalse(decision.shouldDeferToMutationVerification)
        assertTrue(decision.shouldFail)
        assertEquals("timeout", decision.traceStatus)
        assertNotNull(decision.failureReason)
        assertTrue(decision.failureReason!!.contains("Rider rename dialog automation timed out"))
    }

    fun testAutomationTimeoutAlsoDefersAfterAsyncFollowUpWhenSecondStageFlagAlreadyCleared() {
        val decision = RenameSymbolTool.evaluateRiderAutomationTimeout(
            waitingForSecondDialog = false,
            usedAsyncFollowUpClick = true,
            visibleDialogTitles = emptyList()
        )

        assertTrue(decision.shouldDeferToMutationVerification)
        assertFalse(decision.shouldFail)
        assertEquals("defer-to-mutation-check", decision.traceStatus)
        assertNull(decision.failureReason)
    }

    fun testRelatedSymbolsDisableAttemptUsesAccessibleNameFallback() {
        val panel = AlwaysShowingPanel()
        val checkbox = AlwaysShowingCheckBox().apply {
            isSelected = true
            accessibleContext.accessibleName = "Rename related symbols"
        }
        panel.add(checkbox)

        val outcome = RenameSymbolTool.attemptRequiredRelatedSymbolsDisable(panel)

        assertTrue(outcome.attempted)
        assertTrue(outcome.succeeded)
        assertEquals("accessible-button", outcome.method)
        assertFalse(checkbox.isSelected)
    }

    fun testRelatedSymbolsDisableAttemptFailsClosedWhenNoSafeControlIsFound() {
        val panel = AlwaysShowingPanel().apply {
            add(AlwaysShowingLabel("There are declarations that seem to have names related to the name of the type to be renamed"))
            add(AlwaysShowingButton("Back"))
            add(AlwaysShowingButton("Next"))
            add(AlwaysShowingButton("Cancel"))
        }

        val outcome = RenameSymbolTool.attemptRequiredRelatedSymbolsDisable(panel)

        assertTrue(outcome.attempted)
        assertFalse(outcome.succeeded)
        assertEquals("no-safe-toggle", outcome.method)
        assertEquals(RenameSymbolTool.Companion.RelatedSymbolsDisableFailureKind.NO_SAFE_TOGGLE, outcome.failureKind)
        assertNotNull(outcome.failureReason)
        assertTrue(outcome.failureReason!!.contains("could not disable related-symbol renames"))
    }

    fun testPrimaryDialogSubmitPlanFailsClosedBeforeNextWhenNoneStrategyCannotDisableRelatedSymbolsSafely() {
        val plan = RenameSymbolTool.planRiderPrimaryDialogSubmit(
            buttonText = "Next",
            relatedRenamingStrategy = "none",
            relatedDisableSucceeded = false,
            relatedDisableFailureKind = RenameSymbolTool.Companion.RelatedSymbolsDisableFailureKind.ACTIONABLE_CONTROL_REMAINED_SELECTED,
            relatedDisableFailureReason = "could not disable related-symbol renames"
        )

        assertFalse(plan.shouldClick)
        assertTrue(plan.shouldAwaitSecondDialog)
        assertNotNull(plan.failureReason)
        assertTrue(plan.failureReason!!.contains("could not disable related-symbol renames"))
    }

    fun testPrimaryDialogSubmitPlanAllowsNextWhenPrimaryDialogHasNoVerifiedRelatedToggleYet() {
        val plan = RenameSymbolTool.planRiderPrimaryDialogSubmit(
            buttonText = "Next",
            relatedRenamingStrategy = "none",
            relatedDisableSucceeded = false,
            relatedDisableFailureKind = RenameSymbolTool.Companion.RelatedSymbolsDisableFailureKind.NO_SAFE_TOGGLE,
            relatedDisableFailureReason = "Rider rename dialog could not disable related-symbol renames with a verified safe control, so the request failed closed before submitting the follow-up step."
        )

        assertTrue(plan.shouldClick)
        assertTrue(plan.shouldAwaitSecondDialog)
        assertNull(plan.failureReason)
    }

    fun testPrimaryDialogSubmitPlanAllowsNextWhenFailureKindIsNoSafeToggle() {
        val plan = RenameSymbolTool.planRiderPrimaryDialogSubmit(
            buttonText = "Next",
            relatedRenamingStrategy = "none",
            relatedDisableSucceeded = false,
            relatedDisableFailureKind = RenameSymbolTool.Companion.RelatedSymbolsDisableFailureKind.NO_SAFE_TOGGLE,
            relatedDisableFailureReason = "no-safe-toggle"
        )

        assertTrue(plan.shouldClick)
        assertTrue(plan.shouldAwaitSecondDialog)
        assertNull(plan.failureReason)
    }

    fun testPrimaryDialogSubmitPlanFailsClosedWhenActionableControlCannotBeDisabled() {
        val plan = RenameSymbolTool.planRiderPrimaryDialogSubmit(
            buttonText = "Next",
            relatedRenamingStrategy = "none",
            relatedDisableSucceeded = false,
            relatedDisableFailureKind = RenameSymbolTool.Companion.RelatedSymbolsDisableFailureKind.ACTIONABLE_CONTROL_REMAINED_SELECTED,
            relatedDisableFailureReason = "Rider rename dialog kept the related-symbol toggle selected after attempting to disable it."
        )

        assertFalse(plan.shouldClick)
        assertTrue(plan.shouldAwaitSecondDialog)
        assertNotNull(plan.failureReason)
        assertTrue(plan.failureReason!!.contains("kept the related-symbol toggle selected"))
    }

    fun testPrimaryDialogSubmitPlanAllowsNextWhenNoneStrategyVerifiedDisableSucceeded() {
        val plan = RenameSymbolTool.planRiderPrimaryDialogSubmit(
            buttonText = "Next",
            relatedRenamingStrategy = "none",
            relatedDisableSucceeded = true,
            relatedDisableFailureKind = RenameSymbolTool.Companion.RelatedSymbolsDisableFailureKind.NONE,
            relatedDisableFailureReason = null
        )

        assertTrue(plan.shouldClick)
        assertTrue(plan.shouldAwaitSecondDialog)
        assertNull(plan.failureReason)
    }

    fun testSourceKeepsListeningForSecondDialogAfterNext() {
        val source = renameToolSource()

        assertTrue(source.contains("RIDER_DIALOG_SECOND_STAGE_TIMEOUT_MS"))
        assertTrue(source.contains("frontend.dialog-automation.second-dialog.opened"))
        assertTrue(source.contains("frontend.dialog-automation.second-dialog.components"))
        assertTrue(source.contains("frontend.dialog-automation.second-dialog.button.clicked"))
        assertTrue(source.contains("frontend.dialog-automation.second-dialog.timeout"))
        assertTrue(source.contains("frontend.dialog-automation.second-dialog.primary.closed"))
        assertTrue(source.contains("startFollowUpDialogReadinessPolling(dialog, title)"))
        assertTrue(source.contains("evaluateRiderSecondStageTimeout("))
    }

    fun testSourcePollsForDialogReadinessBeforePrimaryAutomation() {
        val source = renameToolSource()

        assertTrue(source.contains("frontend.dialog-automation.readiness.waiting"))
        assertTrue(source.contains("frontend.dialog-automation.readiness.ready"))
        assertTrue(source.contains("frontend.dialog-automation.readiness.timeout"))
        assertTrue(source.contains("Timer(200)"))
    }

    fun testSourcePollsForFollowUpDialogReadinessBeforeAutomation() {
        val source = renameToolSource()

        assertTrue(source.contains("attemptFollowUpDialogAutomationWhenReady"))
        assertTrue(source.contains("startFollowUpDialogReadinessPolling"))
        assertTrue(source.contains("Follow-up dialog content did not become ready"))
    }

    fun testSourceDoesNotCompleteFollowUpImmediatelyAfterNext() {
        val source = renameToolSource()

        assertTrue(source.contains("else \"awaiting-next-state\""))
        assertTrue(source.contains("shouldCompleteAutomationAfterSubmit"))
        assertTrue(source.contains("\"clickedButtonLabel\" to button.text"))
    }

    fun testSourceDefersAutomationTimeoutToMutationCheckOnlyForAsyncFollowUpWithoutVisibleDialogs() {
        val source = renameToolSource()

        assertTrue(source.contains("evaluateRiderAutomationTimeout("))
        assertTrue(source.contains("timeout-deferred-no-visible-dialog"))
        assertTrue(source.contains("followup.progress.mutation-verified"))
        assertTrue(source.contains("automation.completed-after-mutation"))
    }

    fun testSourceTracesRelatedDisableAttemptAndFailsClosedBeforeUnsafeNext() {
        val source = renameToolSource()

        assertTrue(source.contains("related.disable.attempt"))
        assertTrue(source.contains("related.disable.success"))
        assertTrue(source.contains("related.disable.failure"))
        assertTrue(source.contains("could not disable related-symbol renames"))
    }

    fun testSourceDelaysRiderMutationCheckAfterAsyncFollowUpClicks() {
        val source = renameToolSource()

        assertTrue(source.contains("pollRiderFrontendMutationVerification("))
        assertTrue(source.contains("Thread.sleep(delayMs)"))
        assertTrue(source.contains("mutationCheckDelayMs"))
    }

    fun testMutationPollingRetriesUntilAsyncRenameBecomesObservable() {
        var attempts = 0

        val result = runBlocking {
            RenameSymbolTool.pollRiderFrontendMutationVerification(
            initialDelayMs = 200L,
            timeoutMs = 1_000L,
            initialPollIntervalMs = 50L,
            maxPollIntervalMs = 200L,
            sleep = {}
        ) {
            attempts += 1
            if (attempts < 3) {
                RenameSymbolTool.verifyRiderFrontendMutation(
                    beforeName = "IModelDocumentationProvider",
                    afterName = "IModelDocumentationProvider",
                    newName = "ISmokeModelDocumentationProvider",
                    beforeFileText = "interface IModelDocumentationProvider {}",
                    afterFileText = "interface IModelDocumentationProvider {}"
                )
            } else {
                RenameSymbolTool.verifyRiderFrontendMutation(
                    beforeName = "IModelDocumentationProvider",
                    afterName = "ISmokeModelDocumentationProvider",
                    newName = "ISmokeModelDocumentationProvider",
                    beforeFileText = "interface IModelDocumentationProvider {}",
                    afterFileText = "interface ISmokeModelDocumentationProvider {}"
                )
            }
            }
        }

        assertTrue(result.check.verified)
        assertEquals(3, attempts)
        assertEquals(3, result.attemptCount)
        assertEquals(350L, result.totalWaitMs)
    }

    fun testMutationPollingFailsClosedWhenRenameNeverBecomesObservable() {
        var attempts = 0

        val result = runBlocking {
            RenameSymbolTool.pollRiderFrontendMutationVerification(
            initialDelayMs = 200L,
            timeoutMs = 300L,
            initialPollIntervalMs = 50L,
            maxPollIntervalMs = 200L,
            sleep = {}
        ) {
            attempts += 1
            RenameSymbolTool.verifyRiderFrontendMutation(
                beforeName = "IModelDocumentationProvider",
                afterName = "IModelDocumentationProvider",
                newName = "ISmokeModelDocumentationProvider",
                beforeFileText = "interface IModelDocumentationProvider {}",
                afterFileText = "interface IModelDocumentationProvider {}"
            )
            }
        }

        assertFalse(result.check.verified)
        assertEquals(3, attempts)
        assertEquals(3, result.attemptCount)
        assertEquals(300L, result.totalWaitMs)
    }

    private fun renameToolSource(): String {
        return java.io.File(
            "src/main/kotlin/com/github/hechtcarmel/jetbrainsindexmcpplugin/tools/refactoring/RenameSymbolTool.kt"
        ).readText()
    }

    private open class AlwaysShowingPanel : JPanel() {
        override fun isShowing(): Boolean = true
    }

    private class AlwaysShowingButton(text: String) : JButton(text) {
        override fun isShowing(): Boolean = true
    }

    private class AlwaysShowingCheckBox : JCheckBox() {
        override fun isShowing(): Boolean = true
    }

    private class AlwaysShowingLabel(text: String) : JLabel(text) {
        override fun isShowing(): Boolean = true
    }
}
