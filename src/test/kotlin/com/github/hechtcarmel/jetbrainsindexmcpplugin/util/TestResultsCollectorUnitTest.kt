package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.TestStatus
import junit.framework.TestCase

/**
 * Tests for [TestResultsCollector.magnitudeIndexToStatus].
 *
 * Integer values are those returned by the public SMTestProxy.getMagnitude() method
 * (i.e. Magnitude.getValue()), documented in magnitudeIndexToStatus's KDoc:
 *   0=SKIPPED, 1=COMPLETE/PASSED, 2=NOT_RUN, 3=RUNNING, 4=TERMINATED, 5=IGNORED, 6=FAILED, 8=ERROR
 */
class TestResultsCollectorUnitTest : TestCase() {

    fun testPassedIndex() {
        // PASSED_INDEX = 1
        assertEquals(TestStatus.PASSED, TestResultsCollector.magnitudeIndexToStatus(1))
    }

    fun testSkippedIndex() {
        // SKIPPED_INDEX = 0; isPassed() returns true for this in 2025.3, but we map it to SKIPPED
        assertEquals(TestStatus.SKIPPED, TestResultsCollector.magnitudeIndexToStatus(0))
    }

    fun testIgnoredIndex() {
        // IGNORED_INDEX = 5
        assertEquals(TestStatus.SKIPPED, TestResultsCollector.magnitudeIndexToStatus(5))
    }

    fun testFailedIndex() {
        // FAILED_INDEX = 6
        assertEquals(TestStatus.FAILED, TestResultsCollector.magnitudeIndexToStatus(6))
    }

    fun testErrorIndex() {
        // ERROR_INDEX = 8
        assertEquals(TestStatus.ERROR, TestResultsCollector.magnitudeIndexToStatus(8))
    }

    fun testNotRunReturnsNull() {
        // NOT_RUN_INDEX = 2
        assertNull(TestResultsCollector.magnitudeIndexToStatus(2))
    }

    fun testRunningReturnsNull() {
        // RUNNING_INDEX = 3
        assertNull(TestResultsCollector.magnitudeIndexToStatus(3))
    }

    fun testTerminatedReturnsNull() {
        // TERMINATED_INDEX = 4
        assertNull(TestResultsCollector.magnitudeIndexToStatus(4))
    }

    fun testCompleteSuiteValueMapsToPassedStatusItselfNotNull() {
        // COMPLETE_INDEX = 1, same int value as PASSED_INDEX.
        // collectRunEntries excludes suite nodes via isSuite() before calling this function,
        // so value 1 is unambiguously PASSED at this layer.
        assertEquals(TestStatus.PASSED, TestResultsCollector.magnitudeIndexToStatus(1))
    }
}
