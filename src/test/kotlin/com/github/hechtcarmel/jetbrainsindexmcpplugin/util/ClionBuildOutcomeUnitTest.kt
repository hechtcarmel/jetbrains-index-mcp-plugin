package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

import junit.framework.TestCase

/**
 * [ClionBuildOutcome] is the pure session state behind [ClionBuildCapture]'s reflective
 * CidrBuildListener proxy: `beforeStarted`/`afterFinished` per cidr build (one per CMake
 * profile), summed into one session outcome and read only through immutable
 * [ClionBuildOutcome.Snapshot]s. These tests drive it exactly as the proxy does.
 */
class ClionBuildOutcomeUnitTest : TestCase() {

    fun testSingleFailedBuildCompletesSessionWithSummary() {
        val outcome = ClionBuildOutcome()
        assertFalse(outcome.snapshot().sawAnyBuild)

        outcome.buildStarted("build-1")
        assertTrue(outcome.snapshot().sawAnyBuild)
        assertFalse("session is not failed while the build runs", outcome.snapshot().failed)

        val session = outcome.buildFinished(
            buildId = "build-1", succeeded = false, canceled = false,
            errors = 2, warnings = 1, message = "Compilation failed"
        )

        assertNotNull("the only started build finished — the session is complete", session)
        assertTrue(session!!.failed)
        assertEquals(listOf<Any>("build-1"), session.buildIds)
        assertEquals("Build failed: 2 errors, 1 warning — Compilation failed", session.failureSummary)
    }

    fun testMultiProfileSessionSumsCountsAndFinishesOnLastBuild() {
        val outcome = ClionBuildOutcome()
        outcome.buildStarted("profile-a")
        outcome.buildStarted("profile-b")

        assertNull(
            "first of two builds finishing must not complete the session",
            outcome.buildFinished("profile-a", succeeded = true, canceled = false, errors = 0, warnings = 2, message = null)
        )
        val session = outcome.buildFinished(
            "profile-b", succeeded = false, canceled = false, errors = 3, warnings = 0, message = null
        )

        assertNotNull(session)
        assertTrue("one failed profile fails the session", session!!.failed)
        assertEquals(listOf<Any>("profile-a", "profile-b"), session.buildIds)
        assertEquals("Build failed: 3 errors, 2 warnings", session.failureSummary)
    }

    fun testSuccessfulSessionHasNoFailureSummary() {
        val outcome = ClionBuildOutcome()
        outcome.buildStarted("build-1")
        val session = outcome.buildFinished(
            "build-1", succeeded = true, canceled = false, errors = 0, warnings = 1, message = "OK"
        )

        assertNotNull(session)
        assertFalse(session!!.failed)
        assertNull("a successful session must not fabricate a failure", session.failureSummary)
    }

    fun testCanceledSessionReportsCanceled() {
        val outcome = ClionBuildOutcome()
        outcome.buildStarted("build-1")
        val session = outcome.buildFinished(
            "build-1", succeeded = true, canceled = true, errors = 0, warnings = 0, message = null
        )

        assertTrue(session!!.failed)
        assertEquals("Build canceled: 0 errors, 0 warnings", session.failureSummary)
    }

    fun testSingularCountsReadNaturally() {
        val outcome = ClionBuildOutcome()
        outcome.buildStarted(null)
        val session = outcome.buildFinished(
            null, succeeded = false, canceled = false, errors = 1, warnings = 1, message = null
        )

        assertEquals("Build failed: 1 error, 1 warning", session!!.failureSummary)
    }

    fun testLoneFinishWithoutStartCompletesItsOwnSession() {
        val outcome = ClionBuildOutcome()
        val session = outcome.buildFinished(
            "build-1", succeeded = false, canceled = false, errors = 1, warnings = 0, message = null
        )

        assertNotNull("a finish with nothing in flight must still report a completed session", session)
        assertTrue(session!!.sawAnyBuild)
        assertTrue(session.failed)
    }

    fun testNewSessionResetsPreviousSessionState() {
        val outcome = ClionBuildOutcome()
        outcome.buildStarted("old")
        outcome.buildFinished("old", succeeded = false, canceled = false, errors = 5, warnings = 5, message = "old failure")
        assertTrue(outcome.snapshot().failed)

        outcome.buildStarted("new")

        val midSession = outcome.snapshot()
        assertFalse("the failed old session must not bleed into the new one", midSession.failed)
        assertNull(midSession.failureSummary)
        assertEquals("only the new session's build ids remain", listOf<Any>("new"), midSession.buildIds)

        val session = outcome.buildFinished("new", succeeded = false, canceled = false, errors = 1, warnings = 0, message = null)
        assertEquals("counts start fresh in the new session", "Build failed: 1 error, 0 warnings", session!!.failureSummary)
    }

    fun testLoneFinishAfterCompletedSessionStartsFresh() {
        val outcome = ClionBuildOutcome()
        val first = outcome.buildFinished("first", succeeded = true, canceled = false, errors = 0, warnings = 3, message = null)
        assertFalse(first!!.failed)

        val second = outcome.buildFinished("second", succeeded = false, canceled = false, errors = 2, warnings = 0, message = null)

        assertEquals("second session must not inherit the first session's warnings",
            "Build failed: 2 errors, 0 warnings", second!!.failureSummary)
        assertEquals(listOf<Any>("second"), second.buildIds)
    }

    /**
     * The session snapshot handed to a subscriber must be immune to the next session's reset —
     * the exact race that made ide_diagnostics record a failed CLion build as clean when the
     * user rebuilt immediately and the recording ran on a pooled thread.
     */
    fun testCompletedSessionSnapshotSurvivesTheNextSessionsReset() {
        val outcome = ClionBuildOutcome()
        outcome.buildStarted("failing")
        val session = outcome.buildFinished(
            "failing", succeeded = false, canceled = false, errors = 4, warnings = 0, message = "boom"
        )!!

        outcome.buildStarted("next") // resets the accumulator

        assertTrue("the handed-off snapshot must still describe the failed session", session.failed)
        assertEquals("Build failed: 4 errors, 0 warnings — boom", session.failureSummary)
        assertEquals(listOf<Any>("failing"), session.buildIds)
    }

    fun testNullFieldsFromReflectionAreTolerated() {
        val outcome = ClionBuildOutcome()
        outcome.buildStarted(null)
        val session = outcome.buildFinished(null, succeeded = null, canceled = null, errors = null, warnings = null, message = null)

        assertNotNull(session)
        assertTrue(session!!.sawAnyBuild)
        assertFalse("unknown success must not be reported as failure", session.failed)
        assertNull(session.failureSummary)
        assertTrue(session.buildIds.isEmpty())
    }
}
