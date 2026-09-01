package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.usageView.UsageInfo
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Covers the threading contract of [RefactoringScopeGuard.computeUsagesOffEdt]
 * (issue #357): the read-only-scope pre-check's usage search must never execute
 * raw on the EDT, because the Kotlin K2 Analysis API forbids resolution there.
 *
 * The K2 assertion itself cannot be reproduced in this suite — the Kotlin plugin
 * is not on the test classpath — so these tests pin the guard's mechanics
 * instead: the search callback runs under read access, runs off the EDT when the
 * caller is off the EDT, returns its result to the caller, and propagates
 * failures instead of swallowing them. The read-only blocking behavior itself is
 * covered end to end by [ReadOnlyFileGuardBehaviorTest].
 */
class RefactoringScopeGuardThreadingTest : McpPlatformTestCase() {

    fun testSearchFromBackgroundThreadRunsUnderReadActionOffEdt() {
        val psiFile = myFixture.addFileToProject("Guarded.java", "class Guarded {}")
        val usage = UsageInfo(psiFile)

        val searchRanOnEdt = AtomicBoolean(true)
        val searchHadReadAccess = AtomicBoolean(false)

        val usages = PlatformTestUtil.callOnBgtSynchronously<Array<UsageInfo>?>(
            {
                RefactoringScopeGuard.computeUsagesOffEdt(project) {
                    searchRanOnEdt.set(ApplicationManager.getApplication().isDispatchThread)
                    searchHadReadAccess.set(ApplicationManager.getApplication().isReadAccessAllowed)
                    arrayOf(usage)
                }
            },
            30
        )

        assertFalse(
            "Pre-check usage search must not run on the EDT (issue #357)",
            searchRanOnEdt.get()
        )
        assertTrue(
            "Pre-check usage search must hold read access",
            searchHadReadAccess.get()
        )
        assertNotNull("Search result must reach the caller", usages)
        assertEquals(1, usages!!.size)
        assertSame(usage, usages[0])
    }

    fun testSearchResultAndFailuresPropagateWhenCalledOnEdt() {
        assertTrue(
            "This test drives the guard from the EDT, like the tools' execution phase",
            ApplicationManager.getApplication().isDispatchThread
        )
        val psiFile = myFixture.addFileToProject("Scoped.java", "class Scoped {}")
        val usage = UsageInfo(psiFile)

        val searchHadReadAccess = AtomicBoolean(false)
        val usages = RefactoringScopeGuard.computeUsagesOffEdt(project) {
            searchHadReadAccess.set(ApplicationManager.getApplication().isReadAccessAllowed)
            arrayOf(usage)
        }
        assertTrue(
            "Pre-check usage search must hold read access",
            searchHadReadAccess.get()
        )
        assertNotNull("Search result must reach the caller", usages)
        assertSame(usage, usages!![0])

        try {
            RefactoringScopeGuard.computeUsagesOffEdt(project) {
                throw IllegalStateException("search failed")
            }
            fail("A failing search must propagate to the caller, not be swallowed")
        } catch (e: IllegalStateException) {
            assertEquals("search failed", e.message)
        }
    }
}
