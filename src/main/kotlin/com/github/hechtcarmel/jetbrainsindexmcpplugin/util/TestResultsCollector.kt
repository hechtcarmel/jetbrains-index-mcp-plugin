package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.TestResultInfo
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.TestRunEntry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.TestStatus
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.TestSummary
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.execution.testframework.sm.runner.states.TestStateInfo.Magnitude
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView
import com.intellij.execution.testframework.sm.runner.ui.SMTestRunnerResultsForm
import com.intellij.execution.ui.ExecutionConsole
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.ui.RunContentManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.profiler.ultimate.widget.JavaConsoleWithProfilerWidget
import com.intellij.psi.PsiDocumentManager

data class TestCollectionResult(
    val testResults: List<TestResultInfo>,
    val testSummary: TestSummary,
    val truncated: Boolean
)

object TestResultsCollector {

    private val LOG = logger<TestResultsCollector>()
    private const val MAX_STACKTRACE_LENGTH = 500

    fun collect(
        project: Project,
        testResultFilter: String,
        severity: String,
        maxTestResults: Int
    ): TestCollectionResult? {
        val descriptors = RunContentManager.getInstance(project).allDescriptors
        val (rootProxy, runConfigName) = findTestRootProxyAndName(descriptors) ?: return null

        val allLeafTests = rootProxy.getAllTests().filter { it.children.isEmpty() }

        val summary = computeSummary(allLeafTests, runConfigName)

        val filtered = filterTests(allLeafTests, testResultFilter, severity)

        val truncated = filtered.size > maxTestResults
        val results = filtered.take(maxTestResults).map { toTestResultInfo(it, project) }

        return TestCollectionResult(
            testResults = results,
            testSummary = summary,
            truncated = truncated
        )
    }

    fun collectRunEntries(root: SMTestProxy.SMRootTestProxy): List<TestRunEntry> =
        root.allTests
            .filter { it.isLeaf && it !== root }
            .mapNotNull { test ->
                val status = magnitudeToStatus(test.magnitudeInfo) ?: return@mapNotNull null
                val suite = test.parent?.name?.takeIf { it.isNotBlank() }
                TestRunEntry(
                    name = if (suite != null) "$suite.${test.name}" else test.name,
                    status = status,
                    errorMessage = if (status.isFailure) test.errorMessage else null
                )
            }

    /**
     * Maps a [Magnitude] to a [TestStatus], or null for non-test magnitudes (suites, not-run, in-progress).
     *
     * In 2025.3, [Magnitude.isPassed] returns true for both [Magnitude.SKIPPED_INDEX] and
     * [Magnitude.COMPLETE_INDEX], so we map directly from magnitude to avoid misclassification.
     * [Magnitude.COMPLETE_INDEX] (suite completion) returns null so suite nodes are excluded.
     */
    internal fun magnitudeToStatus(magnitude: Magnitude): TestStatus? = when (magnitude) {
        Magnitude.SKIPPED_INDEX, Magnitude.IGNORED_INDEX -> TestStatus.SKIPPED
        Magnitude.PASSED_INDEX -> TestStatus.PASSED
        Magnitude.ERROR_INDEX -> TestStatus.ERROR
        Magnitude.FAILED_INDEX -> TestStatus.FAILED
        else -> null
    }

    private fun findTestRootProxyAndName(descriptors: List<RunContentDescriptor>): Pair<SMTestProxy.SMRootTestProxy, String?>? {
        for (descriptor in descriptors) {
            val root = extractRootProxy(descriptor.executionConsole)
            if (root != null) return Pair(root, descriptor.displayName)
        }
        return null
    }

    private fun extractRootProxy(console: ExecutionConsole?): SMTestProxy.SMRootTestProxy? =
        extractTestRunnerResultsViewer(console)?.root as? SMTestProxy.SMRootTestProxy

    internal fun extractTestRunnerResultsViewer(console: ExecutionConsole?): SMTestRunnerResultsForm? {
        if (console == null) return null
        try {
            if (console is JavaConsoleWithProfilerWidget && console.delegate is SMTRunnerConsoleView) {
                return (console.delegate as SMTRunnerConsoleView).resultsViewer
            }
            if (console is SMTRunnerConsoleView) {
                return console.resultsViewer
            }
            return null
        } catch (e: Exception) {
            LOG.debug("Failed to extract test results viewer", e)
            return null
        }
    }

    private fun computeSummary(leafTests: List<SMTestProxy>, runConfigName: String?): TestSummary {
        var passed = 0
        var failed = 0
        var ignored = 0

        for (test in leafTests) {
            when {
                test.isPassed -> passed++
                test.isIgnored -> ignored++
                else -> failed++
            }
        }

        return TestSummary(
            total = leafTests.size,
            passed = passed,
            failed = failed,
            ignored = ignored,
            runConfigName = runConfigName
        )
    }

    private fun filterTests(
        leafTests: List<SMTestProxy>,
        testResultFilter: String,
        severity: String
    ): List<SMTestProxy> {
        return leafTests.filter { test ->
            val matchesResultFilter = when (testResultFilter) {
                "all" -> true
                "failed" -> !test.isPassed && !test.isIgnored
                else -> !test.isPassed && !test.isIgnored
            }

            val matchesSeverity = when (severity) {
                "all" -> true
                "errors" -> !test.isPassed && !test.isIgnored
                "warnings" -> test.isIgnored
                else -> true
            }

            matchesResultFilter && matchesSeverity
        }
    }

    private fun toTestResultInfo(test: SMTestProxy, project: Project): TestResultInfo {
        val status = when {
            test.isPassed -> "PASSED"
            test.isIgnored -> "IGNORED"
            else -> "FAILED"
        }

        val stacktrace = test.stacktrace?.let {
            if (it.length > MAX_STACKTRACE_LENGTH) it.substring(0, MAX_STACKTRACE_LENGTH) + "..." else it
        }

        var file: String? = null
        var line: Int? = null
        try {
            val location = test.getLocation(project, com.intellij.psi.search.GlobalSearchScope.allScope(project))
            val psiElement = location?.psiElement
            if (psiElement != null) {
                val containingFile = psiElement.containingFile?.virtualFile
                if (containingFile != null) {
                    file = ProjectUtils.getRelativePath(project, containingFile.path)
                    val document = PsiDocumentManager.getInstance(project).getDocument(psiElement.containingFile)
                    if (document != null) {
                        line = document.getLineNumber(psiElement.textOffset) + 1
                    }
                }
            }
        } catch (_: Exception) {
            // Location extraction is best-effort
        }

        return TestResultInfo(
            name = test.name,
            suite = test.parent?.name,
            status = status,
            durationMs = test.duration,
            errorMessage = test.errorMessage,
            stacktrace = stacktrace,
            file = file,
            line = line
        )
    }
}
