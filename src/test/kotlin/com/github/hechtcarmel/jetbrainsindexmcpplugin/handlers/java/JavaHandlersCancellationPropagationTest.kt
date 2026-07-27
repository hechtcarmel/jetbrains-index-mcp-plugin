package com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.java

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.BuiltInSearchScope
import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PluginDetectors
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.search.searches.ClassInheritorsSearch
import com.intellij.util.QueryExecutor
import org.junit.Assume

/**
 * Pins cancellation propagation out of the Java handlers' index searches.
 *
 * The handlers run inside cancellable read actions: when a write action arrives mid-search,
 * the search throws [ProcessCanceledException] (or a coroutine [CancellationException]).
 * Swallowing it in the handlers' generic `catch (Exception)` fallbacks made the read action
 * complete "successfully" with whatever was collected so far — a truncated result reported as
 * complete — instead of letting the platform restart the read action.
 *
 * The exception is injected through the real `classInheritorsSearch` extension point: a
 * registered [QueryExecutor] that throws is exactly what the search sees when it is cancelled
 * mid-enumeration. (A dumb-mode simulation cannot pin this: the platform's `ExecutorsQuery`
 * catches `IndexNotReadyException` thrown inside query executors and ends the query
 * gracefully, so INRE never reaches the handler through this path — the handlers' INRE
 * rethrow guards only non-query PSI access inside the same try blocks.) The fixture hands PSI
 * directly to the handler, which is allowed per the fixture rules — no path resolution is
 * involved.
 */
class JavaHandlersCancellationPropagationTest : McpPlatformTestCase() {

    private fun interfaceFixture(): PsiClass {
        val ifaceFile = myFixture.addFileToProject(
            "dumb/Repo.java",
            """
                package dumb;

                public interface Repo {
                }
            """.trimIndent()
        ) as PsiJavaFile
        myFixture.addFileToProject(
            "dumb/RepoImpl.java",
            """
                package dumb;

                public class RepoImpl implements Repo {
                }
            """.trimIndent()
        )
        return ifaceFile.classes.single()
    }

    private fun registerThrowingInheritorsExecutor(toThrow: () -> Nothing) {
        val executor = QueryExecutor<PsiClass, ClassInheritorsSearch.SearchParameters> { _, _ ->
            toThrow()
        }
        ClassInheritorsSearch.EP_NAME.point.registerExtension(executor, testRootDisposable)
    }

    fun testFindImplementationsRethrowsProcessCanceledException() {
        Assume.assumeTrue("Java plugin required for this fixture", PluginDetectors.java.isAvailable)
        val iface = interfaceFixture()
        registerThrowingInheritorsExecutor { throw ProcessCanceledException() }
        val handler = JavaImplementationsHandler()

        try {
            val implementations = handler.findImplementations(
                iface,
                project,
                BuiltInSearchScope.PROJECT_FILES,
                excludeGenerated = false
            )
            fail(
                "Expected ProcessCanceledException to propagate — swallowing it breaks the " +
                    "platform's cancellable read action protocol and reports a truncated result " +
                    "set as a complete success; the handler returned: " + implementations
            )
        } catch (expected: ProcessCanceledException) {
            // Propagation lets the platform restart the read action after the write action.
        }
    }

}
