package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.MutationVerification
import junit.framework.TestCase
import java.io.File

class RiderMutationRoutingUnitTest : TestCase() {

    fun testRenameSymbolToolKeepsBackendImplementationButRemovesDotNetRoutingToIt() {
        val source = refactoringSource("RenameSymbolTool.kt")

        assertContains(
            source,
            "RiderBackendSemanticService.isDotNetFile(file)",
            "Rider .cs/.csx symbol rename should stay on the Rider .NET routing check"
        )
        assertContains(
            source,
            "invokeCallResult(model, \"renameSymbol\", request)",
            "The dedicated Rider backend rename endpoint should remain implemented even after routing stops using it for C# symbol rename"
        )
        assertContains(
            source,
            "shouldUseRiderBackendRename(file, isFileRename, relatedRenamingStrategy)",
            "The routing decision should remain explicit so C# symbol rename can be kept off the backend lane"
        )
        assertFalse(RenameSymbolTool.shouldUseRiderBackendRename("src/Service.cs", isFileRename = false, relatedRenamingStrategy = "all"))
    }

    fun testRenameSymbolToolRoutesRiderDotNetFileRenameThroughGenericFrontendLane() {
        val source = refactoringSource("RenameSymbolTool.kt")

        assertContains(
            source,
            "shouldUseRiderBackendRename(file, isFileRename, relatedRenamingStrategy)",
            "Rider .cs/.csx routing should explicitly exclude file rename requests from the backend rename lane"
        )
        assertContains(
            source,
            "validateAndPrepareFileRename(project, file, newName)",
            "Rider .cs/.csx file rename should reuse the generic frontend PsiFile rename lane"
        )
        assertFalse(source.contains("tryExecuteRiderFileRename(project, file, newName)"))
    }

    fun testRenameSymbolToolPassesRelatedRenameStrategyIntoRiderDialogAutomation() {
        val source = refactoringSource("RenameSymbolTool.kt")

        assertContains(
            source,
            "RiderRenameDialogAutomationController(newName, relatedRenamingStrategy, trace)",
            "Rider frontend rename automation should receive the related rename strategy so it can honor 'none'"
        )
        assertContains(
            source,
            "disableRelatedSymbolsCheckboxes(dialog)",
            "Rider frontend rename automation should clear the related-symbols checkbox before submitting the dialog"
        )
    }

    fun testRenameSymbolToolPreservesBackendStatusAndVerificationFields() {
        val source = refactoringSource("RenameSymbolTool.kt")

        assertContains(
            source,
            "backendResult.status",
            "Rider rename mapping should preserve backend mutation status instead of fabricating frontend success"
        )
        assertContains(
            source,
            "backendResult.verification",
            "Rider rename mapping should preserve backend verification evidence instead of dropping it"
        )
    }

    fun testRenameSymbolToolTracesBackendSummaryInsteadOfHardcodedSuccess() {
        val source = refactoringSource("RenameSymbolTool.kt")

        assertContains(
            source,
            "is RiderRenameOutcome.Success -> finish(",
            "Trace final.result should still route Rider rename success through finish(...)"
        )
        assertContains(
            source,
            "riderOutcome.summary.success",
            "Trace final.result should use the mapped backend success instead of hardcoded success=true"
        )
        assertContains(
            source,
            "riderOutcome.summary.status",
            "Trace final.result should use the mapped backend status instead of hardcoded status=success"
        )
    }

    fun testSharedMutationVerificationSummaryKeepsLimitedVerificationObservable() {
        val summary = RiderMutationResultMapper.summary(
            legacySuccess = true,
            status = "success",
            affectedFiles = listOf("src/Service.cs"),
            changesCount = 1,
            message = "Rename applied with bounded verification.",
            verification = MutationVerification(
                status = "limited",
                checksRun = listOf("post_change_semantics"),
                warnings = listOf("Closed-file diagnostics are supplementary only")
            ),
            contract = RiderMutationResultMapper.StatusContract.CANONICAL
        )

        assertFalse(summary.success)
        assertEquals("failed", summary.status)
        assertEquals("limited", summary.verification?.status)
        assertEquals(listOf("src/Service.cs"), summary.affectedFiles)
        assertEquals(1, summary.changesCount)
    }

    fun testSharedMutationVerificationSummaryDowngradesFailedVerificationToNonSuccess() {
        val summary = RiderMutationResultMapper.summary(
            legacySuccess = true,
            status = "success",
            affectedFiles = listOf("src/Service.cs"),
            changesCount = 1,
            message = "Move applied but semantic verification failed.",
            verification = MutationVerification(
                status = "failed",
                checksRun = listOf("post_change_semantics"),
                warnings = listOf("Semantic verification failed")
            ),
            contract = RiderMutationResultMapper.StatusContract.CANONICAL
        )

        assertFalse(summary.success)
        assertEquals("failed", summary.status)
        assertEquals("failed", summary.verification?.status)
        assertEquals(listOf("src/Service.cs"), summary.affectedFiles)
        assertEquals(1, summary.changesCount)
    }

    fun testSharedMutationVerificationSummaryMapsBlockedToCanonicalUnsupportedContextByDefault() {
        val summary = RiderMutationResultMapper.summary(
            legacySuccess = false,
            status = "blocked",
            affectedFiles = emptyList(),
            changesCount = 0,
            message = "Rename remained blocked because the workflow requires preview interaction.",
            verification = null,
            contract = RiderMutationResultMapper.StatusContract.CANONICAL
        )

        assertFalse(summary.success)
        assertEquals("conflict", summary.status)
        assertTrue(summary.affectedFiles.isEmpty())
        assertEquals(0, summary.changesCount)
    }

    fun testSharedMutationVerificationSummaryCanPreserveBlockedForSafeDeleteContracts() {
        val summary = RiderMutationResultMapper.summary(
            legacySuccess = false,
            status = "blocked",
            affectedFiles = listOf("src/Service.cs"),
            changesCount = 0,
            message = "Deletion blocked by usages.",
            verification = null,
            contract = RiderMutationResultMapper.StatusContract.PRESERVE_BLOCKED
        )

        assertFalse(summary.success)
        assertEquals("blocked", summary.status)
        assertTrue(summary.affectedFiles.isEmpty())
        assertEquals(0, summary.changesCount)
    }

    fun testMoveFileToolRoutesRiderDotNetMoveThroughRiderBackendFirst() {
        val source = refactoringSource("MoveFileTool.kt")

        assertContains(
            source,
            "shouldUseRiderBackendMove(file)",
            "Rider .cs/.csx move should detect Rider-backed .NET files before the generic move lane"
        )
        assertContains(
            source,
            "RiderBackendSemanticService.isRiderEnvironment()",
            "Rider .cs/.csx move should only route through dialog automation inside Rider"
        )
        assertContains(
            source,
            "executeRiderMoveWithDialogAutomation(",
            "Rider .cs/.csx move should use the dedicated Rider dialog automation lane before the generic fallback"
        )
        assertContains(
            source,
            "RiderMoveDialogAutomationController",
            "Rider .cs/.csx move should automate the Rider Move to Folder dialog instead of relying on headless backend APIs"
        )
        assertContains(
            source,
            "dispatchRiderRefactorThisShortcut(invocationComponent)",
            "Rider .cs/.csx move should dispatch the Refactor This shortcut for dialog automation"
        )
        assertContains(
            source,
            "projectView.select(psiFile, sourceVirtualFile, true)",
            "Rider .cs/.csx move should focus Project/Solution Explorer before shortcut dispatch"
        )
        assertContains(
            source,
            "MoveBackendSelection.GenericFileMove",
            "Rider .cs/.csx move should still preserve the generic IDE fallback lane"
        )
        assertFalse(
            "Rider .cs/.csx move should no longer call the backend move rd endpoint from the frontend tool",
            source.contains("invokeCallResult(model, \"moveFile\", request)")
        )
    }

    fun testMoveFileToolPreparesAndRefreshesDestinationBeforeRiderBackendMove() {
        val source = refactoringSource("MoveFileTool.kt")

        assertContains(
            source,
            "prepareDestinationDirectoryForRiderMove(project, targetDir)",
            "Rider .cs/.csx move should prepare the destination directory before invoking the backend"
        )
        assertContains(
            source,
            "Files.createDirectories(Path.of(targetDir.path))",
            "Rider .cs/.csx move should ensure the destination exists on disk before backend execution"
        )
        assertContains(
            source,
            "refreshProjectRootsAndCommit(project)",
            "Rider .cs/.csx move should refresh VFS/project roots before backend execution"
        )
    }

    fun testMoveFileToolKeepsUnsupportedRiderBackendStatusesVisible() {
        val source = refactoringSource("MoveFileTool.kt")
        val backendSource = backendSource()

        assertContains(
            source,
            "Move failed: simulated Ctrl+Shift+R did not expose a selectable Move to Folder popup entry",
            "Rider .cs/.csx move should fail closed when shortcut-driven dialog automation cannot surface Move to Folder"
        )
        assertContains(
            backendSource,
            "frontend dialog automation owns Rider move execution",
            "The Rider backend move endpoint should explicitly advertise that frontend dialog automation owns move execution"
        )
        assertFalse(
            "The Rider backend move endpoint should no longer execute the failed MoveToFolder workflow lane",
            backendSource.contains("ExecuteMoveFile(movePlan)")
        )
    }

    fun testSafeDeleteToolContractsRiderDotNetBackendDeleteEndpoint() {
        val source = refactoringSource("SafeDeleteTool.kt")
        val registrySource = File("src/main/kotlin/com/github/hechtcarmel/jetbrainsindexmcpplugin/tools/ToolRegistry.kt").readText()

        assertContains(
            source,
            "safeDelete",
            "Rider .cs/.csx safe delete should route through a Rider backend endpoint"
        )
        assertContains(
            source,
            "RiderBackendSemanticService.isDotNetFile(file)",
            "Rider .cs/.csx safe delete should detect Rider-backed .NET files before generic deletion"
        )
        assertContains(
            registrySource,
            "PluginDetectors.java.isAvailable || RiderBackendSemanticService.isRiderEnvironment()",
            "SafeDeleteTool registration should intentionally expose the Rider backend safe-delete path when Rider is available"
        )
        assertFalse(
            "SafeDeleteTool should not require Java PSI classes just to classify deleted element types in Rider environments",
            source.contains("com.intellij.psi.PsiMethod") ||
                source.contains("com.intellij.psi.PsiClass") ||
                source.contains("com.intellij.psi.PsiField")
        )
    }

    fun testGenericFallbackRemainsAvailableForNonDotNetTargets() {
        val renameSource = refactoringSource("RenameSymbolTool.kt")
        val moveSource = refactoringSource("MoveFileTool.kt")
        val deleteSource = refactoringSource("SafeDeleteTool.kt")

        assertContains(
            renameSource,
            "validateAndPrepareFileRename(project, file, newName)",
            "Non-.NET file rename should keep the generic IDE rename fallback"
        )
        assertContains(
            moveSource,
            "MoveBackendSelection.GenericFileMove",
            "Non-.NET move should keep the generic IDE move fallback"
        )
        assertContains(
            deleteSource,
            "targetType",
            "Non-.NET safe delete should keep the generic tool routing entry point"
        )
    }

    fun testOptimizeImportsToolRoutesRiderDotNetFilesThroughFrontendManualEquivalentBeforeGenericProcessor() {
        val source = refactoringSource("OptimizeImportsTool.kt")

        assertContains(
            source,
            "shouldUseRiderFrontendOptimizeImports(file)",
            "Rider .cs/.fs optimize imports should detect Rider-backed .NET files before the generic IntelliJ lane"
        )
        assertContains(
            source,
            "resolveIdeActionsOptimizeImportsActionId()",
            "Rider .cs/.fs optimize imports should actively resolve the platform Optimize Imports id instead of mentioning IdeActions only in comments"
        )
        assertContains(
            source,
            "ACTION_OPTIMIZE_IMPORTS",
            "Rider .cs/.fs optimize imports should probe the ACTION_OPTIMIZE_IMPORTS platform field when available"
        )
        assertContains(
            source,
            "\"OptimizeImports\"",
            "Rider .cs/.fs optimize imports should keep the stable OptimizeImports fallback action id"
        )
        assertContains(
            source,
            "dispatchRiderOptimizeImportsShortcut",
            "Rider .cs/.fs optimize imports should keep a manual-equivalent keyboard fallback when no action id resolves"
        )
        assertContains(
            source,
            "KeyEvent.VK_O",
            "Rider .cs/.fs optimize imports shortcut fallback should dispatch Alt+Shift+O"
        )
        assertContains(
            source,
            "OptimizeImportsProcessor(project, psiFile).runWithoutProgress()",
            "Non-.NET optimize imports should keep the generic IntelliJ OptimizeImportsProcessor lane"
        )
        assertContains(
            source,
            "Optimize imports made no changes to",
            "Optimize imports should report honest no-op results when the file text does not change"
        )
        assertContains(
            source,
            "AnActionEvent.createEvent(",
            "Rider .cs/.fs optimize imports should use the stable action-event factory instead of the deprecated constructor"
        )
        assertContains(
            source,
            "ActionUtil.invokeAction(action, event, null)",
            "Rider .cs/.fs optimize imports should execute through an official action-system runner instead of calling OverrideOnly AnAction methods directly"
        )
        assertContains(
            source,
            "awaitObservableOptimizeImportsMutation(",
            "Rider .cs/.fs optimize imports should use the shared post-invocation wait path for both action and shortcut lanes"
        )
        assertFalse(source.contains("AnActionEvent("))
        assertFalse(source.contains("action.update(event)"))
        assertFalse(source.contains("action.actionPerformed(event)"))
        assertFalse(source.contains("invokeCallResult(model, \"optimizeImports\", request)"))
    }

    fun testOptimizeImportsProtocolSourceAndBackendNoLongerExposeRdEndpoint() {
        val protocol = protocolSource()
        val backend = backendSource()
        val generatedKt = generatedKotlinSource()
        val generatedCs = generatedCsSource()

        assertFalse(protocol.contains("RdOptimizeImportsRequest"))
        assertFalse(protocol.contains("RdOptimizeImportsResult"))
        assertFalse(protocol.contains("call(\"optimizeImports\""))
        assertFalse(backend.contains("model.OptimizeImports.SetAsync(HandleOptimizeImports);"))
        assertFalse(generatedKt.contains("optimizeImports"))
        assertFalse(generatedKt.contains("RdOptimizeImportsRequest"))
        assertFalse(generatedKt.contains("RdOptimizeImportsResult"))
        assertFalse(generatedCs.contains("optimizeImports"))
        assertFalse(generatedCs.contains("RdOptimizeImportsRequest"))
        assertFalse(generatedCs.contains("RdOptimizeImportsResult"))
    }

    fun testOptimizeImportsBackendPartialImplementationIsRemoved() {
        val backend = backendSource()

        assertFalse(backend.contains("HandleOptimizeImports"))
        assertFalse(backend.contains("ExecuteOptimizeImports"))
        assertFalse(backend.contains("TryResolveCSharpRemoveUnusedImportsMethod"))
        assertFalse(backend.contains("ValidateFSharpOptimizeImportsProcessMethod"))
    }

    fun testReformatCodeToolRoutesRiderDotNetFilesThroughFrontendManualEquivalentBeforeGenericProcessors() {
        val source = refactoringSource("ReformatCodeTool.kt")

        assertContains(
            source,
            "shouldUseRiderFrontendReformat(file)",
            "Rider .cs/.fs reformat should detect Rider-backed .NET files before the generic IntelliJ processor lane"
        )
        assertContains(
            source,
            "ACTION_EDITOR_REFORMAT",
            "Rider .cs/.fs reformat should probe ACTION_EDITOR_REFORMAT first when available"
        )
        assertContains(
            source,
            "ACTION_REFORMAT_CODE",
            "Rider .cs/.fs reformat may keep ACTION_REFORMAT_CODE as an additional compatibility candidate"
        )
        assertContains(
            source,
            "\"ReformatCode\"",
            "Rider .cs/.fs reformat should keep the stable ReformatCode fallback action id"
        )
        assertContains(
            source,
            "\"RearrangeCode\"",
            "Rider .cs/.fs reformat should keep the stable RearrangeCode action id for rearrangeCode=true"
        )
        assertContains(
            source,
            "AnActionEvent.createEvent(",
            "Rider .cs/.fs reformat should use the stable action-event factory instead of the deprecated constructor"
        )
        assertContains(
            source,
            "ActionUtil.invokeAction(action, event, null)",
            "Rider .cs/.fs reformat should execute through the official action-system runner instead of calling OverrideOnly AnAction methods directly"
        )
        assertContains(
            source,
            "private suspend fun invokeOptionalRiderAction",
            "Rider .cs/.fs reformat should model action invocation as a suspending helper so EDT can be enforced consistently"
        )
        assertContains(
            source,
            "return edtAction {",
            "Rider .cs/.fs reformat should resolve and invoke Rider actions on EDT"
        )
        assertContains(
            source,
            "dispatchRiderReformatShortcut",
            "Rider .cs/.fs reformat should keep a manual-equivalent keyboard fallback when no action id resolves"
        )
        assertContains(
            source,
            "KeyEvent.VK_L",
            "Rider .cs/.fs reformat shortcut fallback should dispatch Ctrl+Alt+L"
        )
        assertContains(
            source,
            "awaitObservableRiderMutation(",
            "Rider .cs/.fs reformat should wait for an observable post-invocation mutation before deciding no_op"
        )
        assertContains(
            source,
            "OptimizeImportsProcessor(processor)",
            "Non-.NET reformat should keep the generic IntelliJ OptimizeImportsProcessor chain"
        )
        assertContains(
            source,
            "RearrangeCodeProcessor(processor)",
            "Non-.NET reformat should keep the generic IntelliJ RearrangeCodeProcessor chain"
        )
        assertContains(
            source,
            "ReformatCodeProcessor(psiFile, false)",
            "Non-.NET reformat should keep the generic IntelliJ ReformatCodeProcessor lane"
        )
        assertFalse(source.contains("AnActionEvent("))
        assertFalse(source.contains("action.update(event)"))
        assertFalse(source.contains("action.actionPerformed(event)"))
    }

    fun testReformatCodeToolFailsClosedForRiderDotNetPartialRange() {
        val source = refactoringSource("ReformatCodeTool.kt")

        assertContains(
            source,
            "normalizeOptionalLineRange(",
            "Reformat should normalize optional 0-valued line arguments before Rider partial-range fail-closed routing runs"
        )
        assertContains(
            source,
            "shouldUseRiderFrontendReformat(file) && textRange != null",
            "Rider .cs/.fs reformat should branch explicitly on partial-range requests before invoking frontend actions"
        )
        assertContains(
            source,
            "startLine/endLine is not supported when optimizeImports or rearrangeCode is enabled because those actions are file-wide",
            "Rider .cs/.fs reformat should fail closed for partial range plus file-wide optimize/rearrange actions"
        )
        assertContains(
            source,
            "startLine/endLine is not supported because the frontend reformat action cannot guarantee selection-scoped formatting",
            "Rider .cs/.fs reformat should fail closed for selection-only partial range when scope guarantees are not strong enough"
        )
    }

    fun testReformatCodeToolReportsHonestNoOpPayloads() {
        val source = refactoringSource("ReformatCodeTool.kt")

        assertContains(
            source,
            "success = changed",
            "Reformat should only report success when an observable diff actually happened"
        )
        assertContains(
            source,
            "affectedFiles = if (changed) listOf(file) else emptyList()",
            "Reformat should not claim affected files for no-op executions"
        )
        assertContains(
            source,
            "changesCount = if (changed) 1 else 0",
            "Reformat should keep changesCount at zero for no-op executions"
        )
        assertContains(
            source,
            "status = if (changed) \"success\" else \"no_op\"",
            "Reformat should map zero-diff executions to no_op instead of synthetic success"
        )
    }

    fun testRiderMutationToolsUseSharedVerificationSummaryMapper() {
        val renameSource = refactoringSource("RenameSymbolTool.kt")
        val moveSource = refactoringSource("MoveFileTool.kt")
        val deleteSource = refactoringSource("SafeDeleteTool.kt")

        assertContains(renameSource, "RiderMutationResultMapper.summary", "Rename mapping should use the shared Rider mutation summary mapper")
        assertContains(moveSource, "using Rider semantic refactoring; namespace and .csproj updated", "Move mapping should return the frontend dialog-automation success summary directly")
        assertContains(deleteSource, "RiderMutationResultMapper.summary", "Safe delete mapping should use the shared Rider mutation summary mapper")
    }

    private fun refactoringSource(fileName: String): String {
        return File("src/main/kotlin/com/github/hechtcarmel/jetbrainsindexmcpplugin/tools/refactoring/$fileName").readText()
    }

    private fun backendSource(): String {
        return File("src/dotnet/ReSharperPlugin.IndexMcp/IndexMcpBackendHost.cs").readText()
    }

    private fun protocolSource(): String {
        return File("protocol/src/main/kotlin/model/rider/IndexMcpModel.kt").readText()
    }

    private fun generatedKotlinSource(): String {
        return File("src/rider/main/kotlin/com/jetbrains/rider/plugins/indexmcp/IndexMcpModel.Generated.kt").readText()
    }

    private fun generatedCsSource(): String {
        return File("src/dotnet/ReSharperPlugin.IndexMcp/IndexMcpModel.Generated.cs").readText()
    }

    private fun assertContains(source: String, needle: String, message: String) {
        assertTrue(message, source.contains(needle))
    }
}
