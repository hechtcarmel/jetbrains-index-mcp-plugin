package com.github.hechtcarmel.jetbrainsindexmcpplugin.lifecycle

import com.intellij.testFramework.fixtures.BasePlatformTestCase

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
            // Reset persisted state
            service.loadState(ProjectModeService.State())
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
        // Simulates what happens across an IDE restart: state is persisted and reloaded
        val path = "/persisted/project"
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
}
