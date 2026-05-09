package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.MutationVerification
import junit.framework.TestCase
import java.io.File

class RiderMutationRoutingUnitTest : TestCase() {

    fun testRenameSymbolToolKeepsRiderDotNetSymbolRenameOnBackendPath() {
        val source = refactoringSource("RenameSymbolTool.kt")

        assertContains(
            source,
            "RiderBackendSemanticService.isDotNetFile(file)",
            "Rider .cs/.csx symbol rename should stay on the Rider .NET routing check"
        )
        assertContains(
            source,
            "invokeCallResult(model, \"renameSymbol\", request)",
            "Rider .cs/.csx symbol rename should keep using the dedicated Rider backend rename endpoint"
        )
    }

    fun testRenameSymbolToolRoutesRiderDotNetFileRenameThroughGenericFrontendLane() {
        val source = refactoringSource("RenameSymbolTool.kt")

        assertContains(
            source,
            "shouldUseRiderBackendRename(file, isFileRename)",
            "Rider .cs/.csx routing should explicitly exclude file rename requests from the backend rename lane"
        )
        assertContains(
            source,
            "validateAndPrepareFileRename(project, file, newName)",
            "Rider .cs/.csx file rename should reuse the generic frontend PsiFile rename lane"
        )
        assertFalse(source.contains("tryExecuteRiderFileRename(project, file, newName)"))
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
            "ActionManager.getInstance().getAction(RIDER_MOVE_ACTION_ID)",
            "Rider .cs/.csx move should resolve the standard IDE move action for dialog automation"
        )
        assertContains(
            source,
            "ActionUtil.invokeAction(action, dataContext, ActionPlaces.UNKNOWN, null, null)",
            "Rider .cs/.csx move should invoke the standard move action with a deterministic data context"
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
            "Move failed: Rider move action '",
            "Rider .cs/.csx move should fail closed when the Rider move action cannot be resolved for dialog automation"
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

    fun testRiderMutationToolsUseSharedVerificationSummaryMapper() {
        val renameSource = refactoringSource("RenameSymbolTool.kt")
        val moveSource = refactoringSource("MoveFileTool.kt")
        val deleteSource = refactoringSource("SafeDeleteTool.kt")

        assertContains(renameSource, "RiderMutationResultMapper.summary", "Rename mapping should use the shared Rider mutation summary mapper")
        assertContains(moveSource, "using Rider Move to Folder dialog automation", "Move mapping should return the frontend dialog-automation success summary directly")
        assertContains(deleteSource, "RiderMutationResultMapper.summary", "Safe delete mapping should use the shared Rider mutation summary mapper")
    }

    private fun refactoringSource(fileName: String): String {
        return File("src/main/kotlin/com/github/hechtcarmel/jetbrainsindexmcpplugin/tools/refactoring/$fileName").readText()
    }

    private fun backendSource(): String {
        return File("src/dotnet/ReSharperPlugin.IndexMcp/IndexMcpBackendHost.cs").readText()
    }

    private fun assertContains(source: String, needle: String, message: String) {
        assertTrue(message, source.contains(needle))
    }
}
