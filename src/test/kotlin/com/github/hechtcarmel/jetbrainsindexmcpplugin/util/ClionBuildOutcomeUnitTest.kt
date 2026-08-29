package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

import junit.framework.TestCase

/**
 * [ClionBuildOutcome] is the pure session state behind [ClionBuildCapture]'s reflective
 * CidrBuildListener proxy: `beforeStarted`/`afterFinished` per cidr build (one per CMake
 * profile), summed into one session outcome. These tests drive it exactly as the proxy does.
 */
class ClionBuildOutcomeUnitTest : TestCase() {

    fun testSingleFailedBuildCompletesSessionWithSummary() {
        val outcome = ClionBuildOutcome()
        assertFalse(outcome.sawAnyBuild())

        outcome.buildStarted("build-1")
        assertTrue(outcome.sawAnyBuild())
        assertFalse("session is not finished while the build runs", outcome.anyBuildFailed())

        val sessionFinished = outcome.buildFinished(
            buildId = "build-1", succeeded = false, canceled = false,
            errors = 2, warnings = 1, message = "Compilation failed"
        )

        assertTrue("the only started build finished — the session is complete", sessionFinished)
        assertTrue(outcome.anyBuildFailed())
        assertEquals(listOf<Any>("build-1"), outcome.buildIds())
        assertEquals("Build failed: 2 errors, 1 warning — Compilation failed", outcome.failureSummary())
    }

    fun testMultiProfileSessionSumsCountsAndFinishesOnLastBuild() {
        val outcome = ClionBuildOutcome()
        outcome.buildStarted("profile-a")
        outcome.buildStarted("profile-b")

        assertFalse(
            "first of two builds finishing must not complete the session",
            outcome.buildFinished("profile-a", succeeded = true, canceled = false, errors = 0, warnings = 2, message = null)
        )
        assertTrue(
            outcome.buildFinished("profile-b", succeeded = false, canceled = false, errors = 3, warnings = 0, message = null)
        )

        assertTrue("one failed profile fails the session", outcome.anyBuildFailed())
        assertEquals(listOf<Any>("profile-a", "profile-b"), outcome.buildIds())
        assertEquals("Build failed: 3 errors, 2 warnings", outcome.failureSummary())
    }

    fun testSuccessfulSessionHasNoFailureSummary() {
        val outcome = ClionBuildOutcome()
        outcome.buildStarted("build-1")
        outcome.buildFinished("build-1", succeeded = true, canceled = false, errors = 0, warnings = 1, message = "OK")

        assertFalse(outcome.anyBuildFailed())
        assertNull("a successful session must not fabricate a failure", outcome.failureSummary())
    }

    fun testCanceledSessionReportsCanceled() {
        val outcome = ClionBuildOutcome()
        outcome.buildStarted("build-1")
        outcome.buildFinished("build-1", succeeded = true, canceled = true, errors = 0, warnings = 0, message = null)

        assertTrue(outcome.anyBuildFailed())
        assertEquals("Build canceled: 0 errors, 0 warnings", outcome.failureSummary())
    }

    fun testSingularCountsReadNaturally() {
        val outcome = ClionBuildOutcome()
        outcome.buildStarted(null)
        outcome.buildFinished(null, succeeded = false, canceled = false, errors = 1, warnings = 1, message = null)

        assertEquals("Build failed: 1 error, 1 warning", outcome.failureSummary())
    }

    fun testLoneFinishWithoutStartCompletesItsOwnSession() {
        val outcome = ClionBuildOutcome()
        val sessionFinished = outcome.buildFinished(
            "build-1", succeeded = false, canceled = false, errors = 1, warnings = 0, message = null
        )

        assertTrue("a finish with nothing in flight must still report a completed session", sessionFinished)
        assertTrue(outcome.sawAnyBuild())
        assertTrue(outcome.anyBuildFailed())
    }

    fun testNewSessionResetsPreviousSessionState() {
        val outcome = ClionBuildOutcome()
        outcome.buildStarted("old")
        outcome.buildFinished("old", succeeded = false, canceled = false, errors = 5, warnings = 5, message = "old failure")
        assertTrue(outcome.anyBuildFailed())

        outcome.buildStarted("new")

        assertFalse("the failed old session must not bleed into the new one", outcome.anyBuildFailed())
        assertNull(outcome.failureSummary())
        assertEquals("only the new session's build ids remain", listOf<Any>("new"), outcome.buildIds())

        outcome.buildFinished("new", succeeded = false, canceled = false, errors = 1, warnings = 0, message = null)
        assertEquals("counts start fresh in the new session", "Build failed: 1 error, 0 warnings", outcome.failureSummary())
    }

    fun testLoneFinishAfterCompletedSessionStartsFresh() {
        val outcome = ClionBuildOutcome()
        outcome.buildFinished("first", succeeded = true, canceled = false, errors = 0, warnings = 3, message = null)
        assertFalse(outcome.anyBuildFailed())

        outcome.buildFinished("second", succeeded = false, canceled = false, errors = 2, warnings = 0, message = null)

        assertEquals("second session must not inherit the first session's warnings",
            "Build failed: 2 errors, 0 warnings", outcome.failureSummary())
        assertEquals(listOf<Any>("second"), outcome.buildIds())
    }

    fun testNullFieldsFromReflectionAreTolerated() {
        val outcome = ClionBuildOutcome()
        outcome.buildStarted(null)
        val finished = outcome.buildFinished(null, succeeded = null, canceled = null, errors = null, warnings = null, message = null)

        assertTrue(finished)
        assertTrue(outcome.sawAnyBuild())
        assertFalse("unknown success must not be reported as failure", outcome.anyBuildFailed())
        assertNull(outcome.failureSummary())
        assertTrue(outcome.buildIds().isEmpty())
    }
}
