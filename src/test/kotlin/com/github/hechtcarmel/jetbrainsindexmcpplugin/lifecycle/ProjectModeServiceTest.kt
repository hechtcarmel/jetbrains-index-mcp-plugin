package com.github.hechtcarmel.jetbrainsindexmcpplugin.lifecycle

import com.intellij.ide.PowerSaveMode
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil

/**
 * Tests the ProjectModeService state machine using a real application context.
 *
 * Focuses on correctness of the closed-project registry and mode transitions
 * that don't require a real window (focus listeners are tested separately via
 * integration). Side effects that touch external platform APIs (PowerSaveMode,
 * FileEditorManager) are async via invokeLater and do not affect mode state
 * assertions, which are synchronous.
 */
class ProjectModeServiceTest : BasePlatformTestCase() {

    private lateinit var service: ProjectModeService

    override fun setUp() {
        super.setUp()
        service = ProjectModeService.getInstance()
        // Start each test with a clean state
        service.loadState(ProjectModeService.State())
    }

    override fun tearDown() {
        try {
            // Release the test project so lifecycle timers don't bleed between tests
            if (service.isManaged(project)) {
                service.release(project)
            }
            // release() and the dormant/active transitions queue editor work on the EDT — run it
            // inside this test rather than letting it close or reopen files in the next one.
            UIUtil.dispatchAllInvocationEvents()
            // Reset persisted state
            service.loadState(ProjectModeService.State())
            // Drop stale pendingClose entries left by deferred-close tests
            service.flushPendingCloses()
            // PSM is IDE-global — restore it so test isolation isn't broken
            PowerSaveMode.setEnabled(false)
        } finally {
            super.tearDown()
        }
    }

    // ── Closed-project registry ───────────────────────────────────────────────

    fun testWasClosedByUsReturnsFalseForUnknownPath() {
        assertFalse(service.wasClosedByUs("/never/seen/project"))
    }

    fun testMarkClosedMakesWasClosedByUsReturnTrue() {
        val path = "/some/closed/project"
        service.markClosed(path)
        assertTrue(service.wasClosedByUs(path))
    }

    fun testMarkClosedAlsoEnrollsIntoManagedSet() {
        // A closed project must remain tracked as managed so it can be auto-reopened
        val path = "/some/closed/project"
        service.markClosed(path)
        assertTrue("Closed projects must remain in managed set", service.isManaged(path))
    }

    fun testMarkReopenedRemovesFromClosedRegistry() {
        val path = "/some/closed/project"
        service.markClosed(path)
        assertTrue(service.wasClosedByUs(path))  // sanity

        service.markReopened(path)
        assertFalse(
            "After reopening, wasClosedByUs must return false",
            service.wasClosedByUs(path)
        )
    }

    fun testMarkReopenedSetsModeToBg() {
        val path = "/some/closed/project"
        service.markClosed(path)
        service.markReopened(path)
        assertEquals(
            "Reopened project should be in BACKGROUND mode",
            ProjectMode.BACKGROUND,
            service.getMode(path)
        )
    }

    fun testClosedProjectRegistryRoundTrip() {
        // Simulates what happens across an IDE restart: state is persisted and reloaded.
        // Uses a real temp directory — loadState prunes non-existent paths on startup.
        val dir = java.io.File.createTempFile("lifecycle-roundtrip-", "").apply { delete(); mkdirs() }
        try {
            val path = dir.absolutePath
            service.markClosed(path)

            val persistedState = service.getState()
            assertTrue(persistedState.closedProjectPaths.contains(path))

            // Simulate restart: create fresh service, load persisted state
            val fresh = ProjectModeService()
            fresh.loadState(persistedState)

            assertTrue("Closed path must survive state round-trip", fresh.wasClosedByUs(path))
            assertEquals(
                "Mode must be CLOSED after loading state with closed path",
                ProjectMode.CLOSED,
                fresh.getMode(path)
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    // ── Mode queries ─────────────────────────────────────────────────────────

    fun testGetModeReturnsBackgroundForUnknownPath() {
        assertEquals(
            "Unknown projects default to BACKGROUND (not managed, not closed)",
            ProjectMode.BACKGROUND,
            service.getMode("/not/a/real/project")
        )
    }

    fun testGetModeReturnsClosedForClosedPath() {
        val path = "/closed/project"
        service.markClosed(path)
        assertEquals(ProjectMode.CLOSED, service.getMode(path))
    }

    fun testGetModeByProjectDelegatesToPathLookup() {
        // Ensures both overloads return consistent results
        service.enroll(project)
        val byProject = service.getMode(project)
        val byPath = service.getMode(project.basePath!!)
        assertEquals("getMode(Project) and getMode(String) must agree", byProject, byPath)
    }

    fun testGetAllManagedModesIncludesEnrolledProject() {
        service.enroll(project)
        val modes = service.getAllManagedModes()
        assertTrue(
            "Enrolled project must appear in getAllManagedModes()",
            modes.containsKey(project.basePath)
        )
    }

    fun testGetAllManagedModesIncludesClosedProjects() {
        val closedPath = "/closed/project"
        service.markClosed(closedPath)
        val modes = service.getAllManagedModes()
        assertTrue(
            "Closed project must appear in getAllManagedModes()",
            modes.containsKey(closedPath)
        )
        assertEquals(ProjectMode.CLOSED, modes[closedPath])
    }

    fun testGetAllManagedModesIsEmptyWhenNothingManaged() {
        assertTrue(service.getAllManagedModes().isEmpty())
    }

    // ── Enrollment ───────────────────────────────────────────────────────────

    fun testEnrollMakesProjectManaged() {
        assertFalse(service.isManaged(project))  // sanity: not enrolled yet
        service.enroll(project)
        assertTrue(service.isManaged(project))
    }

    fun testEnrollSetsInitialModeToBackground() {
        service.enroll(project)
        assertEquals(ProjectMode.BACKGROUND, service.getMode(project))
    }

    fun testEnrollIsIdempotent() {
        service.enroll(project)
        service.enroll(project)  // second call must not double-enroll or throw
        assertTrue(service.isManaged(project))
        assertEquals(ProjectMode.BACKGROUND, service.getMode(project))
    }

    fun testReleaseMakesProjectUnmanaged() {
        service.enroll(project)
        service.release(project)
        assertFalse(service.isManaged(project))
    }

    fun testReleaseRemovesFromAllManagedModes() {
        service.enroll(project)
        service.release(project)
        assertFalse(service.getAllManagedModes().containsKey(project.basePath))
    }

    // ── wakeForMcp — the auto-wake mechanism ─────────────────────────────────

    fun testWakeForMcpDoesNothingWhenModeIsBackground() {
        // A project already in BACKGROUND should stay BACKGROUND and not error
        service.enroll(project)
        assertEquals(ProjectMode.BACKGROUND, service.getMode(project))  // sanity

        service.wakeForMcp(project)

        assertEquals(
            "wakeForMcp on BACKGROUND project must leave mode unchanged",
            ProjectMode.BACKGROUND,
            service.getMode(project)
        )
    }

    fun testWakeForMcpChangesDormantToBackground() {
        // Get the project to DORMANT state. transition() side effects (editor close,
        // PSI cache drop) are async via invokeLater and harmless on a test project
        // with no open editors. The mode change itself is synchronous.
        service.enroll(project)
        service.transition(project, ProjectMode.DORMANT)

        assertEquals(ProjectMode.DORMANT, service.getMode(project))  // sanity

        service.wakeForMcp(project)

        assertEquals(
            "wakeForMcp must change DORMANT → BACKGROUND",
            ProjectMode.BACKGROUND,
            service.getMode(project)
        )
    }

    fun testWakeForMcpDoesNotAffectActiveMode() {
        // ACTIVE projects are under user control — MCP calls should not downgrade them
        service.enroll(project)
        service.transition(project, ProjectMode.ACTIVE)

        service.wakeForMcp(project)

        assertEquals(
            "wakeForMcp must not downgrade ACTIVE to BACKGROUND",
            ProjectMode.ACTIVE,
            service.getMode(project)
        )
    }

    // ── last_project_kept regression (Bug: mode was left as CLOSED) ──────────

    fun testLastProjectKeptSetsModeBackToDormantNotClosed() {
        // When the only managed open project is about to be closed, it must be
        // kept in DORMANT (not CLOSED) so focus events log correctly afterwards.
        service.enroll(project)
        service.transition(project, ProjectMode.DORMANT)
        service.transition(project, ProjectMode.CLOSED)  // blocked by last_project_kept

        assertEquals(
            "last_project_kept must reset mode to DORMANT, not leave it as CLOSED",
            ProjectMode.DORMANT,
            service.getMode(project)
        )
    }

    fun testLastProjectKeptDoesNotAddToClosedRegistry() {
        // A project kept dormant by last_project_kept is NOT closed by us — auto-open
        // must not treat it as a managed-closed project.
        service.enroll(project)
        service.transition(project, ProjectMode.DORMANT)
        service.transition(project, ProjectMode.CLOSED)

        assertFalse(
            "last_project_kept must not add the project to closedProjectPaths",
            service.wasClosedByUs(project.basePath ?: "")
        )
    }

    // ── minimumOpenProjects floor ─────────────────────────────────────────────

    fun testMinimumFloorPreventsCloseWhenAtLimit() {
        // With the default minimum of 4 and only 1 managed open project,
        // closing must be blocked.
        service.enroll(project)
        service.transition(project, ProjectMode.DORMANT)
        service.transition(project, ProjectMode.CLOSED)

        // Mode should be DORMANT — the close was blocked
        assertEquals(ProjectMode.DORMANT, service.getMode(project))
        assertFalse(service.wasClosedByUs(project.basePath ?: ""))
    }

    // ── Regression: .idea auto-open must clear closedProjectPaths ────────────
    // Bug: resolveOrOpen's .idea fallback didn't call markReopened, leaving the
    // project in closedProjectPaths while open → healthCheck reported "open but in
    // closedProjectPaths" as a bug.

    fun testMarkReopenedClearsClosedPathsRegistry() {
        val path = project.basePath ?: ""
        service.enroll(project)
        service.markClosed(path)
        assertTrue("precondition: must be in closedProjectPaths", service.wasClosedByUs(path))

        service.markReopened(path)

        assertFalse(
            "after markReopened: project must NOT remain in closedProjectPaths",
            service.wasClosedByUs(path)
        )
    }

    fun testHealthCheckReportsOkWhenOpenProjectIsNotInClosedPaths() {
        // Invariant the bug violated: a project that is open must not be in closedProjectPaths.
        // healthCheck must not report a bug for a normally-open enrolled project.
        service.enroll(project)
        // healthCheck must complete without reporting a bug
        service.healthCheck("test_invariant")
        // If no exception and no "open but in closedProjectPaths" — test passes.
        // We verify the invariant holds: open project is NOT in closedProjectPaths.
        assertFalse(
            "enrolled open project must not be in closedProjectPaths",
            service.wasClosedByUs(project.basePath ?: "")
        )
    }

    fun testOnProjectClosedExternallyFixesOpenButInClosedPathsInconsistency() {
        // If a project somehow ends up open AND in closedProjectPaths (the bug),
        // onProjectClosedExternally should repair it when the project actually closes.
        val path = project.basePath ?: ""
        service.enroll(project)
        service.markClosed(path)  // Simulate the inconsistent state
        assertTrue("test setup: in closedProjectPaths", service.wasClosedByUs(path))

        // onProjectClosedExternally is called when the window closes
        service.onProjectClosedExternally(path, project.name)

        // State should now be consistent — closed and marked as such
        assertTrue("after external close: must be in closedProjectPaths", service.wasClosedByUs(path))
        assertFalse("after external close: must not be in pendingClose", service.isInPendingClose(path))
    }

    // ── onDormant cancels focus alarm (Bug: timer:focus raced timer:inactivity)

    fun testDormantTransitionSetsModeCorrectly() {
        // After going dormant, mode must remain DORMANT — the focus alarm (which
        // would transition back to background) must not fire after dormant is entered.
        // We can only assert the synchronous state; the alarm cancellation is async.
        service.enroll(project)
        service.transition(project, ProjectMode.ACTIVE)
        service.transition(project, ProjectMode.BACKGROUND)
        service.transition(project, ProjectMode.DORMANT)

        assertEquals(
            "mode must be DORMANT after dormant transition regardless of prior focus state",
            ProjectMode.DORMANT,
            service.getMode(project)
        )
    }

    // ── pendingClose set (event-driven flush replaces alarm reschedule) ──────

    fun testFloorBlockedProjectAddedToPendingClose() {
        // When the floor blocks a close, the project must enter pendingClose so
        // the next flush can close it without a repeated alarm.
        service.enroll(project)
        service.transition(project, ProjectMode.DORMANT)
        service.transition(project, ProjectMode.CLOSED)

        val path = project.basePath ?: ""
        assertTrue(
            "floor-blocked project must be in pendingClose",
            service.isInPendingClose(path)
        )
        // And NOT in closedProjectPaths — that would make wasClosedByUs true
        assertFalse(service.wasClosedByUs(path))
    }

    fun testFlushPendingClosesDoesNothingAtFloor() {
        // With exactly one managed open project (= floor), flush must not close it.
        service.enroll(project)
        service.transition(project, ProjectMode.DORMANT)
        service.transition(project, ProjectMode.CLOSED)  // blocked → pendingClose

        service.flushPendingCloses()  // still at floor — must stay

        assertEquals(
            "flushPendingCloses must not close below the floor",
            ProjectMode.DORMANT,
            service.getMode(project)
        )
        assertFalse(service.wasClosedByUs(project.basePath ?: ""))
    }

    fun testOnProjectClosedExternallyRemovesFromPendingAndMarksClosed() {
        // If a user manually closes a window that was in pendingClose, the service
        // must mark it as closed (so future auto-open works) and clear the entry.
        service.enroll(project)
        val path = project.basePath ?: ""
        service.transition(project, ProjectMode.DORMANT)
        service.transition(project, ProjectMode.CLOSED)  // blocked → pendingClose
        assertTrue("precondition: must be in pendingClose", service.isInPendingClose(path))

        service.onProjectClosedExternally(path, project.name)

        assertFalse("pendingClose must be cleared after external close", service.isInPendingClose(path))
        assertTrue("project must be marked as closed by us", service.wasClosedByUs(path))
    }

    fun testHealthCheckRunsWithoutException() {
        // healthCheck must not throw even with no managed projects.
        service.healthCheck("test")
    }

    fun testHealthCheckWithEnrolledProjectProducesOkResult() {
        service.enroll(project)
        // Must complete cleanly with a managed project in background mode.
        service.healthCheck("test_enrolled")
    }

    // ── State machine transitions ─────────────────────────────────────────────

    fun testTransitionActiveChangesMode() {
        service.enroll(project)
        service.transition(project, ProjectMode.ACTIVE)
        assertEquals(ProjectMode.ACTIVE, service.getMode(project))
    }

    fun testTransitionDormantChangesMode() {
        service.enroll(project)
        service.transition(project, ProjectMode.DORMANT)
        assertEquals(ProjectMode.DORMANT, service.getMode(project))
    }

    fun testTransitionIsNoOpWhenModeUnchanged() {
        service.enroll(project)
        service.transition(project, ProjectMode.BACKGROUND)  // already BACKGROUND after enroll
        assertEquals(ProjectMode.BACKGROUND, service.getMode(project))
    }

    fun testResetInactivityTimerIsNoOpInActiveMode() {
        // In ACTIVE mode, MCP calls should not start the dormant countdown.
        // We verify by checking no exception is thrown and mode is unchanged.
        service.enroll(project)
        service.transition(project, ProjectMode.ACTIVE)
        service.resetInactivityTimer(project)  // must not throw or change state
        assertEquals(ProjectMode.ACTIVE, service.getMode(project))
    }

    // ── Power Save Mode reconciliation (Bug: releasing the last managed ──────
    // project computed anyActive=false over an empty set and turned PSM ON)

    fun testReconcileDisablesPowerSaveModeWhenNoManagedProjects() {
        PowerSaveMode.setEnabled(true)  // simulate PSM left on by lifecycle management
        assertTrue(service.getAllManagedModes().isEmpty())  // sanity: nothing managed

        service.reconcilePowerSaveMode()

        assertFalse(
            "With no managed projects, reconcile must disable Power Save Mode, not enable it",
            PowerSaveMode.isEnabled()
        )
    }

    fun testReleasingLastManagedProjectDisablesPowerSaveMode() {
        service.enroll(project)
        UIUtil.dispatchAllInvocationEvents()  // flush enroll's invokeLater reconcile
        assertTrue(
            "precondition: PSM must be on while the only managed project is non-active",
            PowerSaveMode.isEnabled()
        )

        service.release(project)
        UIUtil.dispatchAllInvocationEvents()  // flush release's invokeLater reconcile

        assertFalse(
            "Releasing the last managed project must restore full IDE capabilities (PSM off)",
            PowerSaveMode.isEnabled()
        )
    }

    // ── Deferred close: state must follow the actual close outcome ───────────
    // (Bug: onClosed recorded CLOSED before closeAndDispose ran and ignored its
    // result, so a vetoed close left the registry disagreeing with the open
    // window forever.) The closer lambda replaces the platform boundary only —
    // light test projects short-circuit canClose, so a real veto cannot be
    // produced in this fixture.

    fun testDeferredCloseRecordsClosedOnlyAfterSuccessfulClose() {
        service.enroll(project)
        val path = project.basePath!!

        service.executeDeferredClose(project, path, "dormant", "timer:close") { true }

        assertTrue("successful close must be recorded", service.wasClosedByUs(path))
        assertEquals(ProjectMode.CLOSED, service.getMode(path))
    }

    fun testDeferredCloseVetoIsNotRecordedAsClosed() {
        service.enroll(project)
        val path = project.basePath!!

        service.executeDeferredClose(project, path, "dormant", "timer:close") { false }

        assertFalse(
            "vetoed close must NOT be recorded in closedProjectPaths",
            service.wasClosedByUs(path)
        )
        assertEquals(
            "vetoed close must fall back to DORMANT",
            ProjectMode.DORMANT,
            service.getMode(path)
        )
        assertTrue(
            "vetoed close must enter pendingClose for event-driven retry",
            service.isInPendingClose(path)
        )
    }

    // ── Issue #369: dormant closes editor tabs; the user's return gives them back ──────────
    // Before the fix a dormant transition closed every tab for good — the user had to rebuild
    // their editor set from Recent Files after any two-minute gap in MCP activity.

    private fun openInEditor(name: String, text: String): VirtualFile {
        val file = myFixture.addFileToProject(name, text).virtualFile
        FileEditorManager.getInstance(project).openFile(file, true)
        return file
    }

    private fun openFilesNow(): Set<VirtualFile> = FileEditorManager.getInstance(project).openFiles.toSet()

    /** The dormant transition closes editors on the EDT; run that work before asserting. */
    private fun goDormant() {
        service.transition(project, ProjectMode.DORMANT, "timer:inactivity")
        UIUtil.dispatchAllInvocationEvents()
    }

    /** What the window focus listener does when the user comes back, EDT work included. */
    private fun comeBack() {
        service.transition(project, ProjectMode.ACTIVE, "focus_gained")
        UIUtil.dispatchAllInvocationEvents()
    }

    fun testDormantClosesEveryEditorTabAndRemembersThem() {
        val first = openInEditor("First.java", "class First {}")
        val second = openInEditor("Second.java", "class Second {}")
        assertEquals(setOf(first, second), openFilesNow())  // sanity
        service.enroll(project)

        goDormant()

        assertEquals("dormant must close every editor tab", emptySet<VirtualFile>(), openFilesNow())
        assertEquals(
            "the closed tabs must be remembered so they can be given back",
            setOf(first.url, second.url),
            service.rememberedEditorUrls(project.basePath!!).toSet()
        )
    }

    fun testReturningFocusReopensTheTabsDormantClosed() {
        val first = openInEditor("First.java", "class First {}")
        val second = openInEditor("Second.java", "class Second {}")
        service.enroll(project)
        goDormant()
        assertEquals(emptySet<VirtualFile>(), openFilesNow())  // sanity

        comeBack()

        assertEquals(
            "focus_gained → ACTIVE must reopen exactly the tabs dormant closed",
            setOf(first, second),
            openFilesNow()
        )
        assertTrue(
            "restored tabs are no longer owed",
            service.rememberedEditorUrls(project.basePath!!).isEmpty()
        )
    }

    fun testReturningFocusReselectsTheTabThatWasSelectedBeforeDormant() {
        val first = openInEditor("First.java", "class First {}")
        openInEditor("Second.java", "class Second {}")
        val fem = FileEditorManager.getInstance(project)
        fem.openFile(first, true)  // make First the selected tab again
        assertEquals(first, fem.selectedFiles.single())  // sanity
        service.enroll(project)
        goDormant()

        comeBack()

        assertEquals("the tab selected before dormant must be selected again", first, fem.selectedFiles.single())
    }

    fun testMcpWakeLeavesTheTabsClosedButStillOwed() {
        val file = openInEditor("Only.java", "class Only {}")
        service.enroll(project)
        goDormant()

        service.wakeForMcp(project)
        UIUtil.dispatchAllInvocationEvents()

        assertEquals(ProjectMode.BACKGROUND, service.getMode(project))
        assertEquals(
            "an MCP wake must not reopen editors — the agent does not need them and dormant freed that memory",
            emptySet<VirtualFile>(),
            openFilesNow()
        )
        assertEquals(listOf(file.url), service.rememberedEditorUrls(project.basePath!!))

        comeBack()
        assertEquals("the tabs are still owed to the user after an MCP wake", setOf(file), openFilesNow())
    }

    fun testTabsClosedAcrossSeveralDormantCyclesAreAllGivenBack() {
        val first = openInEditor("First.java", "class First {}")
        service.enroll(project)
        goDormant()                    // closes First
        service.wakeForMcp(project)    // BACKGROUND; First stays closed
        val second = openInEditor("Second.java", "class Second {}")  // e.g. ide_open_file while in background
        goDormant()                    // closes Second

        comeBack()

        assertEquals("both cycles' tabs are owed", setOf(first, second), openFilesNow())
    }

    fun testRestoredTabsAreForgottenSoALaterRestoreReflectsWhatTheUserLeftOpen() {
        val first = openInEditor("First.java", "class First {}")
        val second = openInEditor("Second.java", "class Second {}")
        service.enroll(project)
        goDormant()
        comeBack()
        assertEquals(setOf(first, second), openFilesNow())  // sanity

        FileEditorManager.getInstance(project).closeFile(first)  // the user closes one tab
        service.transition(project, ProjectMode.BACKGROUND, "timer:focus")
        goDormant()
        comeBack()

        assertEquals(
            "a tab the user closed must not come back from a stale memory",
            setOf(second),
            openFilesNow()
        )
    }

    fun testReleaseGivesBackTheTabsDormantClosed() {
        val file = openInEditor("Only.java", "class Only {}")
        service.enroll(project)
        goDormant()
        assertEquals(emptySet<VirtualFile>(), openFilesNow())  // sanity

        service.release(project)
        UIUtil.dispatchAllInvocationEvents()

        assertEquals("release returns full control to the user, tabs included", setOf(file), openFilesNow())
    }

    fun testTabsClosedByDormantSurviveAnIdeRestart() {
        // The IDE saves a dormant project's workspace with no open editors, so the remembered
        // set has to live in this service's persisted state to outlive a restart.
        val file = openInEditor("Only.java", "class Only {}")
        service.enroll(project)
        goDormant()
        val persisted = service.getState()
        assertEquals(listOf(file.url), persisted.dormantEditors[project.basePath]?.fileUrls)

        // Restart: a fresh service loads the persisted state, then the user focuses the window.
        val fresh = ProjectModeService()
        fresh.loadState(persisted)
        assertTrue("precondition: the project must survive the ghost prune", fresh.isManaged(project))

        fresh.transition(project, ProjectMode.ACTIVE, "focus_gained")
        UIUtil.dispatchAllInvocationEvents()

        assertEquals("tabs remembered before the restart come back on the first focus", setOf(file), openFilesNow())
    }

    fun testMarkReopenedDoesNotDemoteAnActiveProject() {
        // The frame usually takes focus as it appears, so the focus listener can promote a
        // reopened project to ACTIVE before the opener records the reopen. Demoting it would
        // leave the user working in a focused window whose dormant countdown is running.
        service.enroll(project)
        service.transition(project, ProjectMode.ACTIVE, "focus_gained")

        service.markReopened(project.basePath!!)

        assertEquals(ProjectMode.ACTIVE, service.getMode(project))
    }

    fun testMarkReopenedStillPutsAnUnfocusedProjectInBackground() {
        service.enroll(project)
        service.transition(project, ProjectMode.DORMANT, "timer:inactivity")
        UIUtil.dispatchAllInvocationEvents()

        service.markReopened(project.basePath!!)

        assertEquals(ProjectMode.BACKGROUND, service.getMode(project))
    }
}
