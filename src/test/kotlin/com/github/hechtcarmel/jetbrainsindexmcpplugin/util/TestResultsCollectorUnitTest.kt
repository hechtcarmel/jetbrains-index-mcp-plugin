package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.TestStatus
import com.intellij.execution.testframework.sm.runner.states.TestStateInfo.Magnitude
import junit.framework.TestCase

class TestResultsCollectorUnitTest : TestCase() {

    fun testMagnitudePassed() {
        assertEquals(TestStatus.PASSED, TestResultsCollector.magnitudeToStatus(Magnitude.PASSED_INDEX))
    }

    fun testMagnitudeSkipped() {
        // SKIPPED_INDEX makes isPassed() return true in 2025.3; must map to SKIPPED, not PASSED
        assertEquals(TestStatus.SKIPPED, TestResultsCollector.magnitudeToStatus(Magnitude.SKIPPED_INDEX))
    }

    fun testMagnitudeIgnored() {
        assertEquals(TestStatus.SKIPPED, TestResultsCollector.magnitudeToStatus(Magnitude.IGNORED_INDEX))
    }

    fun testMagnitudeFailed() {
        assertEquals(TestStatus.FAILED, TestResultsCollector.magnitudeToStatus(Magnitude.FAILED_INDEX))
    }

    fun testMagnitudeError() {
        assertEquals(TestStatus.ERROR, TestResultsCollector.magnitudeToStatus(Magnitude.ERROR_INDEX))
    }

    fun testMagnitudeCompleteSuiteReturnsNull() {
        // COMPLETE_INDEX is suite completion; isPassed() returns true for it, but it is not a test node
        assertNull(TestResultsCollector.magnitudeToStatus(Magnitude.COMPLETE_INDEX))
    }

    fun testMagnitudeNotRunReturnsNull() {
        assertNull(TestResultsCollector.magnitudeToStatus(Magnitude.NOT_RUN_INDEX))
    }

    fun testMagnitudeRunningReturnsNull() {
        assertNull(TestResultsCollector.magnitudeToStatus(Magnitude.RUNNING_INDEX))
    }

    fun testMagnitudeTerminatedReturnsNull() {
        assertNull(TestResultsCollector.magnitudeToStatus(Magnitude.TERMINATED_INDEX))
    }
}
