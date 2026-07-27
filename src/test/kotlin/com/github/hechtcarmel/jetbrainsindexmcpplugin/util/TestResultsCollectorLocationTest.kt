package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.TestResultInfo
import com.intellij.execution.Location
import com.intellij.execution.PsiLocation
import com.intellij.execution.testframework.sm.runner.SMTestLocator
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.util.concurrent.TimeUnit

/**
 * Pins the threading contract of [TestResultsCollector.toTestResultInfo]: location resolution
 * must run under a read action.
 *
 * Production reaches the collector from GetDiagnosticsTool on a Ktor worker thread with no
 * read lock, where SMTestLocator implementations (e.g. JavaTestLocator -> stub index) assert
 * read access. BasePlatformTestCase runs test bodies on the EDT with implicit read access,
 * which would mask the bug — so the collector is driven from a pooled thread here, with a
 * locator that fails exactly like the platform does when called without read access.
 */
class TestResultsCollectorLocationTest : BasePlatformTestCase() {

    fun testLocationResolutionWorksOffEdtWithoutImplicitReadAccess() {
        val psiFile = myFixture.addFileToProject("FooTest.java", "public class FooTest {}")
        val proxy = SMTestProxy("testX", false, "test://FooTest.testX")
        proxy.setLocator(ReadAccessRequiringLocator(psiFile))

        val info = ApplicationManager.getApplication().executeOnPooledThread<TestResultInfo> {
            TestResultsCollector.toTestResultInfo(proxy, project)
        }.get(30, TimeUnit.SECONDS)

        assertNotNull(
            "file must resolve on a background thread — location lookup must run under a read action",
            info.file
        )
        assertNotNull(
            "line must resolve on a background thread — location lookup must run under a read action",
            info.line
        )
    }

    /**
     * Mimics the platform's SMTestLocator implementations, which assert read access before
     * touching PSI or the stub index. Without a read action this throws, and the collector's
     * best-effort catch swallows it — leaving file/line null.
     */
    private class ReadAccessRequiringLocator(private val element: PsiElement) : SMTestLocator {
        override fun getLocation(
            protocol: String,
            path: String,
            project: Project,
            scope: GlobalSearchScope
        ): List<Location<*>> {
            check(ApplicationManager.getApplication().isReadAccessAllowed) {
                "Read access is allowed from inside read-action only"
            }
            return listOf(PsiLocation(element))
        }
    }
}
