package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.dotnet.RiderBackendRenameDiagnostics
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.MutationVerification
import junit.framework.TestCase
import java.io.File
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class RenameSymbolToolRoutingUnitTest : TestCase() {

    fun testRenameModeTreatsOmittedLineAndColumnAsFileRename() {
        val mode = RenameSymbolTool().resolveRenameMode(buildJsonObject {
            put("file", JsonPrimitive("src/File.cs"))
            put("newName", JsonPrimitive("Renamed.cs"))
        })

        assertTrue(mode.isFileRename)
        assertNull(mode.line)
        assertNull(mode.column)
        assertNull(mode.error)
    }

    fun testRenameModeTreatsZeroLineAndColumnAsFileRename() {
        val mode = RenameSymbolTool().resolveRenameMode(buildJsonObject {
            put("file", JsonPrimitive("src/File.cs"))
            put("line", JsonPrimitive(0))
            put("column", JsonPrimitive(0))
            put("newName", JsonPrimitive("Renamed.cs"))
        })

        assertTrue(mode.isFileRename)
        assertNull(mode.line)
        assertNull(mode.column)
        assertNull(mode.error)
    }

    fun testRenameModeTreatsBlankLineAndColumnStringsAsFileRename() {
        val mode = RenameSymbolTool().resolveRenameMode(buildJsonObject {
            put("file", JsonPrimitive("src/File.cs"))
            put("line", JsonPrimitive("   "))
            put("column", JsonPrimitive(""))
            put("newName", JsonPrimitive("Renamed.cs"))
        })

        assertTrue(mode.isFileRename)
        assertNull(mode.line)
        assertNull(mode.column)
        assertNull(mode.error)
    }

    fun testRenameModeTreatsPositiveLineAndColumnAsSymbolRename() {
        val mode = RenameSymbolTool().resolveRenameMode(buildJsonObject {
            put("file", JsonPrimitive("src/File.cs"))
            put("line", JsonPrimitive(12))
            put("column", JsonPrimitive(8))
            put("newName", JsonPrimitive("RenamedSymbol"))
        })

        assertFalse(mode.isFileRename)
        assertEquals(12, mode.line)
        assertEquals(8, mode.column)
        assertNull(mode.error)
    }

    fun testRenameModeRejectsMixedPositiveAndNonPositiveCoordinates() {
        listOf(
            buildJsonObject {
                put("line", JsonPrimitive(12))
                put("column", JsonPrimitive(0))
            },
            buildJsonObject {
                put("line", JsonPrimitive(0))
                put("column", JsonPrimitive(8))
            },
            buildJsonObject {
                put("line", JsonPrimitive(12))
            },
            buildJsonObject {
                put("column", JsonPrimitive(8))
            }
        ).forEach { coordinatesOnly ->
            val mode = RenameSymbolTool().resolveRenameMode(buildJsonObject {
                put("file", JsonPrimitive("src/File.cs"))
                put("newName", JsonPrimitive("RenamedSymbol"))
                coordinatesOnly.forEach { (key, value) -> put(key, value) }
            })

            assertFalse(mode.isFileRename)
            assertEquals(
                "Both 'line' and 'column' must be provided for symbol rename, or both omitted for file rename.",
                mode.error
            )
        }
    }

    fun testRiderFrontendFallbackRejectsContainerLikeTargets() {
        assertTrue(RenameSymbolTool.isUnsupportedRiderFrontendFallbackTargetClass(null))
        assertTrue(RenameSymbolTool.isUnsupportedRiderFrontendFallbackTargetClass("com.intellij.psi.impl.file.PsiDirectoryImpl"))
        assertTrue(RenameSymbolTool.isUnsupportedRiderFrontendFallbackTargetClass("com.jetbrains.rider.ideaInterop.fileTypes.csharp.psi.impl.CSharpNamespaceDeclaration"))
        assertTrue(RenameSymbolTool.isUnsupportedRiderFrontendFallbackTargetClass("com.intellij.psi.impl.source.PsiPackageImpl"))
        assertFalse(RenameSymbolTool.isUnsupportedRiderFrontendFallbackTargetClass("com.jetbrains.rider.ideaInterop.fileTypes.csharp.psi.impl.CSharpClassDeclaration"))
    }

    fun testRiderFrontendFallbackZeroChangeSummaryFailsClosed() {
        val summary = RenameSymbolTool.summarizeFrontendRenameResult(
            oldName = "ModelDescriptions",
            newName = "SimpleModelDesc",
            relatedRenamesCount = 0,
            affectedFiles = emptyList(),
            changesCount = 0,
            riderFallbackStatus = "unsupported"
        )

        assertFalse(summary.success)
        assertEquals("unsupported_context", summary.status)
        assertEquals(0, summary.changesCount)
        assertTrue(summary.affectedFiles.isEmpty())
        assertFalse(summary.message.contains("Successfully renamed"))
    }

    fun testBlockedRiderFrontendFallbackMapsEditorRequirementToNeedsActiveEditor() {
        val result = RenameSymbolTool.buildBlockedRiderFrontendFallbackResult(
            oldName = "WidgetService",
            backendStatus = "unsupported",
            actionReason = "active editor is required for Rider rename lane"
        )

        assertFalse(result.success)
        assertEquals("needs_active_editor", result.status)
        assertTrue(result.message.contains("active editor is required"))
    }

    fun testBlockedRiderFrontendFallbackMapsConflictLikeReasonsToConflict() {
        listOf(
            "multiple rename handlers would require chooser UI",
            "production handler invoke would show modal UI",
            "rename preview would require user interaction"
        ).forEach { reason ->
            val result = RenameSymbolTool.buildBlockedRiderFrontendFallbackResult(
                oldName = "WidgetService",
                backendStatus = "unsupported",
                actionReason = reason
            )

            assertFalse("reason=$reason should be non-success", result.success)
            assertEquals("reason=$reason should map to conflict", "conflict", result.status)
        }
    }

    fun testBlockedRiderFrontendFallbackMapsOtherFailClosedReasonsToUnsupportedContext() {
        val result = RenameSymbolTool.buildBlockedRiderFrontendFallbackResult(
            oldName = "WidgetService",
            backendStatus = "unsupported",
            actionReason = "experimental action fallback disabled"
        )

        assertFalse(result.success)
        assertEquals("unsupported_context", result.status)
        assertTrue(result.message.contains("experimental action fallback disabled"))
    }

    fun testRiderFrontendFallbackChangedSummaryPreservesSuccess() {
        val summary = RenameSymbolTool.summarizeFrontendRenameResult(
            oldName = "SimpleTypeModelDescription",
            newName = "SimpleModelDesc",
            relatedRenamesCount = 1,
            affectedFiles = listOf("src/File.cs"),
            changesCount = 1,
            riderFallbackStatus = "unsupported",
            mutationCheck = RenameSymbolTool.verifyRiderFrontendMutation(
                beforeName = "SimpleTypeModelDescription",
                afterName = "SimpleModelDesc",
                beforeFileText = "class SimpleTypeModelDescription {}",
                afterFileText = "class SimpleTypeModelDescription {}"
            )
        )

        assertTrue(summary.success)
        assertNull(summary.status)
        assertEquals(1, summary.changesCount)
        assertEquals(listOf("src/File.cs"), summary.affectedFiles)
        assertTrue(summary.message.contains("Successfully renamed 'SimpleTypeModelDescription' to 'SimpleModelDesc'"))
    }

    fun testRiderFrontendFallbackChangedSummaryFailsClosedWhenMutationWasNotVerified() {
        val summary = RenameSymbolTool.summarizeFrontendRenameResult(
            oldName = "SimpleTypeModelDescription",
            newName = "SimpleModelDesc",
            relatedRenamesCount = 1,
            affectedFiles = listOf("src/File.cs"),
            changesCount = 1,
            riderFallbackStatus = "unsupported",
            mutationCheck = RenameSymbolTool.verifyRiderFrontendMutation(
                beforeName = "SimpleTypeModelDescription",
                afterName = "SimpleTypeModelDescription",
                beforeFileText = "class SimpleTypeModelDescription {}",
                afterFileText = "class SimpleTypeModelDescription {}"
            )
        )

        assertFalse(summary.success)
        assertEquals("no_op", summary.status)
        assertEquals(0, summary.changesCount)
        assertTrue(summary.affectedFiles.isEmpty())
        assertTrue(summary.message.contains("no real source mutation was verified"))
    }

    fun testRiderFrontendFallbackChangedSummaryFailsWhenBackendExactTargetDoesNotMatchFrontendTarget() {
        val summary = RenameSymbolTool.summarizeFrontendRenameResult(
            oldName = "SimpleTypeModelDescription",
            newName = "SimpleModelDesc",
            relatedRenamesCount = 0,
            affectedFiles = listOf("src/File.cs"),
            changesCount = 1,
            riderFallbackStatus = "unsupported",
            mutationCheck = RenameSymbolTool.verifyRiderFrontendMutation(
                beforeName = "SimpleTypeModelDescription",
                afterName = "SimpleModelDesc",
                beforeFileText = "class SimpleTypeModelDescription {}",
                afterFileText = "class SimpleModelDesc {}"
            ),
            backendDiagnostics = RiderBackendRenameDiagnostics(
                resolutionStatus = "success",
                targetKind = "type",
                resolvedName = "DifferentType",
                sourceTokenText = "DifferentType",
                executionHint = "frontend_editor_backed_exact_target_only",
                unsupportedReason = "requires_frontend_lane",
                traceStages = listOf("plan.end", "target-resolution.bound")
            )
        )

        assertFalse(summary.success)
        assertEquals("failed", summary.status)
        assertEquals("failed", summary.verification?.status)
        assertTrue(summary.verification?.warnings?.any { it.contains("exact target", ignoreCase = true) } == true)
    }

    fun testRiderFrontendFallbackChangedSummaryNormalizesLimitedVerificationToCanonicalFailed() {
        val summary = RenameSymbolTool.summarizeFrontendRenameResult(
            oldName = "SimpleTypeModelDescription",
            newName = "SimpleModelDesc",
            relatedRenamesCount = 0,
            affectedFiles = listOf("src/File.cs"),
            changesCount = 1,
            riderFallbackStatus = "unsupported",
            mutationCheck = RenameSymbolTool.verifyRiderFrontendMutation(
                beforeName = "SimpleTypeModelDescription",
                afterName = "SimpleModelDesc",
                beforeFileText = "class SimpleTypeModelDescription {}",
                afterFileText = "class SimpleModelDesc {}"
            ),
            backendDiagnostics = RiderBackendRenameDiagnostics(
                resolutionStatus = "success",
                targetKind = "type",
                resolvedName = "SimpleTypeModelDescription",
                sourceTokenText = "SimpleTypeModelDescription",
                executionHint = "frontend_editor_backed_exact_target_only",
                unsupportedReason = "requires_frontend_lane",
                traceStages = listOf("plan.end", "target-resolution.bound")
            ),
            verification = MutationVerification(
                status = "limited",
                checksRun = listOf("post_change_semantics"),
                warnings = listOf("Closed-file diagnostics are supplementary only")
            )
        )

        assertFalse(summary.success)
        assertEquals("failed", summary.status)
        assertEquals("limited", summary.verification?.status)
        assertEquals(listOf("src/File.cs"), summary.affectedFiles)
        assertEquals(1, summary.changesCount)
    }

    fun testGenericRenameSummaryRemainsSuccessWithoutRiderFallbackMutationGate() {
        val summary = RenameSymbolTool.summarizeFrontendRenameResult(
            oldName = "Service",
            newName = "CustomerService",
            relatedRenamesCount = 0,
            affectedFiles = listOf("src/Service.kt"),
            changesCount = 1,
            riderFallbackStatus = null,
            mutationCheck = RenameSymbolTool.verifyRiderFrontendMutation(
                beforeName = "Service",
                afterName = "Service",
                beforeFileText = "class Service {}",
                afterFileText = "class Service {}"
            )
        )

        assertTrue(summary.success)
        assertNull(summary.status)
        assertEquals(1, summary.changesCount)
        assertEquals(listOf("src/Service.kt"), summary.affectedFiles)
    }

    fun testCompletedRiderSymbolRenameUnsupportedStatusesFallBackToFrontend() {
        assertTrue(RenameSymbolTool.shouldFallbackToFrontendForCompletedRiderSymbolRename("unsupported"))
        assertTrue(RenameSymbolTool.shouldFallbackToFrontendForCompletedRiderSymbolRename("not_supported"))
        assertTrue(RenameSymbolTool.shouldFallbackToFrontendForCompletedRiderSymbolRename(" Unsupported "))
    }

    fun testCompletedRiderSymbolRenameTerminalStatusesDoNotFallBackToFrontend() {
        listOf(
            null,
            "",
            "success",
            "no_op",
            "blocked",
            "verification_limited",
            "verification_failed",
            "needs_active_editor",
            "conflict",
            "unsupported_context",
            "failed"
        ).forEach { status ->
            assertFalse("status=$status should stay terminal", RenameSymbolTool.shouldFallbackToFrontendForCompletedRiderSymbolRename(status))
        }
    }

    fun testRiderRenameTimeoutAndFailureStayTerminalBackendErrorsWithoutFrontendFallback() {
        val source = renameToolSource()

        assertTrue(source.contains("is com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.dotnet.RdCallOutcome.Timeout ->"))
        assertTrue(source.contains("return RiderRenameOutcome.BackendCallFailed(\"Backend rd call returned no result (timeout, fault, or cancellation; check IDE log for details)\")"))
        assertTrue(source.contains("is com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.dotnet.RdCallOutcome.Failure ->"))
        assertFalse(source.contains("RdCallOutcome.Timeout -> RiderRenameOutcome.FallbackToFrontend"))
        assertFalse(source.contains("RdCallOutcome.Failure -> RiderRenameOutcome.FallbackToFrontend"))
    }

    fun testFileRenameLaneDoesNotUseFrontendFallbackStatusPolicy() {
        val source = renameToolSource()

        assertFalse(RenameSymbolTool.shouldUseRiderBackendRename("src/Service.cs", isFileRename = true, relatedRenamingStrategy = "all"))
        assertTrue(source.contains("if (targetElement !is PsiFile && relatedRenamingStrategy != \"none\")"))
        assertTrue(source.contains("if (targetElement !is PsiFile) {"))
        assertTrue(source.contains("addParameterFieldRelations(project, targetElement, effectiveNewName, renameProcessor)"))
    }

    fun testZeroCoordinatesRouteRiderDotNetFilesToGenericFrontendFileRename() {
        val source = renameToolSource()

        assertTrue(source.contains("if (isFileRename) {"))
        assertTrue(source.contains("shouldUseRiderBackendRename(file, isFileRename, relatedRenamingStrategy)"))
        assertTrue(source.contains("validateAndPrepareFileRename(project, file, newName)"))
        assertTrue(source.contains("val result = executeRename(project, element, newName, overrideStrategy, relatedRenamingStrategy, affectedFiles)"))
        assertFalse(source.contains("tryExecuteRiderFileRename(project, file, newName)"))
        assertFalse(source.contains("tryExecuteRiderSymbolRename(project, file, line, column, newName"))
    }

    fun testRiderBackendRenameRoutingKeepsDotNetSymbolsButNotDotNetFileRenames() {
        assertTrue(RenameSymbolTool.shouldUseRiderBackendRename("src/Service.cs", isFileRename = false, relatedRenamingStrategy = "all"))
        assertFalse(RenameSymbolTool.shouldUseRiderBackendRename("src/Service.cs", isFileRename = false, relatedRenamingStrategy = "none"))
        assertFalse(RenameSymbolTool.shouldUseRiderBackendRename("src/Service.cs", isFileRename = false, relatedRenamingStrategy = "ask"))
        assertFalse(RenameSymbolTool.shouldUseRiderBackendRename("src/Service.cs", isFileRename = true, relatedRenamingStrategy = "all"))
        assertFalse(RenameSymbolTool.shouldUseRiderBackendRename("src/Service.kt", isFileRename = false, relatedRenamingStrategy = "all"))
    }

    fun testRiderFrontendAutomationRoutingOnlyUsesValidatedNoneStrategyForDotNetSymbols() {
        assertTrue(RenameSymbolTool.shouldUseRiderFrontendRenameAutomation("src/Service.cs", isFileRename = false, relatedRenamingStrategy = "none"))
        assertFalse(RenameSymbolTool.shouldUseRiderFrontendRenameAutomation("src/Service.cs", isFileRename = false, relatedRenamingStrategy = "ask"))
        assertFalse(RenameSymbolTool.shouldUseRiderFrontendRenameAutomation("src/Service.cs", isFileRename = false, relatedRenamingStrategy = "accessors_and_tests"))
        assertFalse(RenameSymbolTool.shouldUseRiderFrontendRenameAutomation("src/Service.cs", isFileRename = false, relatedRenamingStrategy = "all"))
        assertFalse(RenameSymbolTool.shouldUseRiderFrontendRenameAutomation("src/Service.cs", isFileRename = true, relatedRenamingStrategy = "none"))
        assertFalse(RenameSymbolTool.shouldUseRiderFrontendRenameAutomation("src/Service.kt", isFileRename = false, relatedRenamingStrategy = "none"))
    }

    fun testRiderDialogAutomationDisablesRelatedSymbolsCheckboxOnlyForNoneStrategy() {
        assertTrue(RenameSymbolTool.shouldDisableRelatedSymbolsCheckbox("Rename related symbols"))
        assertTrue(RenameSymbolTool.shouldDisableRelatedSymbolsCheckbox("Rename Related Symbols in comments"))
        assertTrue(RenameSymbolTool.shouldDisableRelatedSymbolsCheckbox("Also update related symbols"))
        assertFalse(RenameSymbolTool.shouldDisableRelatedSymbolsCheckbox("Search in comments and strings"))
        assertFalse(RenameSymbolTool.shouldDisableRelatedSymbolsCheckbox(null))
    }

    fun testFileRenameRoutingNormalizesCoordinatesBeforeBranching() {
        val source = renameToolSource()

        assertTrue(source.contains("val renameMode = resolveRenameMode(arguments)"))
        assertTrue(source.contains("val isFileRename = renameMode.isFileRename"))
        assertTrue(source.contains("if (isFileRename) {"))
        assertTrue(source.contains("validateAndPrepareFileRename(project, file, newName)"))
        assertFalse(source.contains("val line = arguments[\"line\"]?.jsonPrimitive?.int"))
        assertFalse(source.contains("val column = arguments[\"column\"]?.jsonPrimitive?.int"))
    }

    fun testNonRiderSymbolRenameKeepsGenericProcessorLane() {
        val source = renameToolSource()

        assertTrue(source.contains("validateAndPrepare("))
        assertTrue(source.contains("riderFrontendFallback != null"))
        assertTrue(source.contains("val result = executeRename(project, element, newName, overrideStrategy, relatedRenamingStrategy, affectedFiles)"))
        assertTrue(source.contains("RenameProcessor(project, targetElement, effectiveNewName, false, false)"))
        assertTrue(source.contains("HeadlessRenameProcessor(project, targetElement, effectiveNewName, false, false)"))
    }

    fun testDotNetNoneStrategyRoutesToRiderFrontendAutomationBeforeGenericProcessorLane() {
        val source = renameToolSource()

        assertTrue(source.contains("val shouldUseRiderFrontendAutomation = shouldUseRiderFrontendRenameAutomation(file, isFileRename, relatedRenamingStrategy)"))
        assertTrue(source.contains("val riderFrontendExecutionRequested = riderFrontendFallback != null || shouldUseRiderFrontendAutomation"))
        assertTrue(source.contains("if (riderFrontendExecutionRequested) {"))
        assertTrue(source.contains("riderFallbackStatus = riderFrontendStatus"))
    }

    fun testDotNetFileRenameDeclaredTypeContractAcceptsFileOnlyRename() {
        val verification = RenameSymbolTool.verifyDotNetFileRenameDeclaredTypeIdentity(
            beforeFileText = "namespace Demo; public class Service {}",
            afterFileText = "namespace Demo; public class Service {}"
        )

        assertNull(verification)
    }

    fun testDotNetFileRenameDeclaredTypeContractRejectsTypeRename() {
        val verification = RenameSymbolTool.verifyDotNetFileRenameDeclaredTypeIdentity(
            beforeFileText = "namespace Demo; public class Service {}",
            afterFileText = "namespace Demo; public class CustomerService {}"
        )

        assertNotNull(verification)
        assertEquals("failed", verification!!.status)
        assertTrue(verification.warnings.any { it.contains("declared type identity", ignoreCase = true) })
    }

    fun testFallbackTracingStagesArePresentInSource() {
        val source = renameToolSource()

        assertTrue(source.contains("frontend.file.resolve"))
        assertTrue(source.contains("frontend.offset.resolve"))
        assertTrue(source.contains("frontend.resolve.start"))
        assertTrue(source.contains("frontend.resolve.end"))
        assertTrue(source.contains("frontend.factory.policy"))
        assertTrue(source.contains("frontend.factory.refused"))
        assertTrue(source.contains("frontend.processor.start"))
        assertTrue(source.contains("frontend.processor.end"))
        assertTrue(source.contains("frontend.processor.failure"))
        assertTrue(source.contains("frontend.result"))
        assertTrue(source.contains("frontend.execution.result"))
        assertTrue(source.contains("frontend.verification.result"))
    }

    fun testUnsafeRiderFrontendActionReturnsBeforeProcessorExecution() {
        val source = renameToolSource()
        val actionEndIndex = source.indexOf("frontend.action.end")
        val shouldInvokeGuardIndex = source.indexOf("if (!actionPlan.policy.shouldInvoke)")
        val processorStartIndex = source.indexOf("frontend.processor.start")

        assertTrue(actionEndIndex >= 0)
        assertTrue(shouldInvokeGuardIndex > actionEndIndex)
        assertTrue(processorStartIndex > shouldInvokeGuardIndex)
        assertTrue(source.contains("buildBlockedRiderFrontendFallbackResult("))
        assertTrue(source.contains("reason=\${actionPlan.policy.reason}"))
    }

    fun testEditorFeasibilityGuardsRunBeforeHandlerInspectionAndProcessorExecution() {
        val source = renameToolSource()
        val editorLookupIndex = source.indexOf("val editorLookup = lookupRiderFrontendEditor(project, element, trace)")
        val feasibilityIndex = source.indexOf("val feasibility = evaluateRiderFrontendFeasibility(")
        val handlerRegistryIndex = source.indexOf("val registry = RenameHandlerRegistry.getInstance()")

        assertTrue(editorLookupIndex >= 0)
        assertTrue(feasibilityIndex > editorLookupIndex)
        assertTrue(handlerRegistryIndex > feasibilityIndex)
        assertTrue(source.contains("if (!feasibility.canProceed)"))
        assertTrue(source.contains("val refusalReason = actionPlan.blockReason ?: actionPlan.policy.reason"))
        assertTrue(source.contains("actionReason = refusalReason"))
    }

    fun testRiderBackendLaneOpensEditorWithoutFocusAndClosesOnlyIfToolOpenedIt() {
        val source = renameToolSource()

        assertTrue(source.contains("var openedByTool = false"))
        assertTrue(source.contains("fileEditorManager.openFile(virtualFile, false)"))
        assertTrue(source.contains("kotlinx.coroutines.delay(500)"))
        assertTrue(source.contains("if (openedByTool)"))
        assertTrue(source.contains("FileEditorManager.getInstance(project).closeFile(virtualFile)"))
    }

    private fun renameToolSource(): String {
        val path = "src/main/kotlin/com/github/hechtcarmel/jetbrainsindexmcpplugin/tools/refactoring/RenameSymbolTool.kt"
        return File(path).readText()
    }
}
