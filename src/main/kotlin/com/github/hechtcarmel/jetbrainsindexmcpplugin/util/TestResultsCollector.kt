package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.TestResultInfo
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.TestRunEntry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.TestStatus
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.TestSummary
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView
import com.intellij.execution.testframework.sm.runner.ui.SMTestRunnerResultsForm
import com.intellij.execution.ui.ConsoleViewWithDelegate
import com.intellij.execution.ui.ExecutionConsole
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.ui.RunContentManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager

data class TestCollectionResult(
    val testResults: List<TestResultInfo>,
    val testSummary: TestSummary,
    val truncated: Boolean
)

object TestResultsCollector {

    private const val MAX_STACKTRACE_LENGTH = 500

    /**
     * Per-test cap for ide_run_tests stack traces (issue #316). Generous enough that real traces
     * pass through untouched; only pathological ones (deeply chained causes) are trimmed.
     */
    internal const val MAX_RUN_ENTRY_STACKTRACE_LENGTH = 10_000

    /**
     * Aggregate stack-trace budget for one ide_run_tests result. A mass failure (broken shared
     * fixture, hundreds of failed tests) must not become a multi-MB response that MCP clients
     * truncate mid-JSON: earlier failures keep their traces, later ones fall back to
     * errorMessage only once the budget is spent.
     */
    internal const val MAX_RUN_TOTAL_STACKTRACE_CHARS = 100_000

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

    fun collectRunEntries(
        root: SMTestProxy.SMRootTestProxy,
        totalStackTraceBudget: Int = MAX_RUN_TOTAL_STACKTRACE_CHARS
    ): List<TestRunEntry> {
        var traceBudget = totalStackTraceBudget
        return root.allTests
            .filter { it !== root && it.isLeaf && !it.isSuite && !it.isConfig }
            .mapNotNull { test ->
                // TODO: Use `test.magnitudeInfo` once API is stable
                magnitudeIndexToStatus(test.magnitude)?.let { status ->
                    // Budget check before the stacktrace read: once spent, the (possibly huge)
                    // trace string is never materialized. Soft budget — the last attached trace
                    // may overshoot by at most one per-test cap.
                    val stackTrace = if (status.isFailure && traceBudget > 0) {
                        test.stacktrace?.takeIf(String::isNotBlank)?.let(::truncateStackTrace)
                            ?.also { traceBudget -= it.length }
                    } else null
                    TestRunEntry(
                        name = composeName(test.name, test.parent?.name),
                        status = status,
                        errorMessage = if (status.isFailure) test.errorMessage else null,
                        stackTrace = stackTrace
                    )
                }
            }
    }

    /**
     * Head+tail truncation: for chained exceptions the root cause sits at the BOTTOM of the trace
     * (the issue #316 repro is 500 nested causes), so keeping only the head would drop exactly the
     * part that explains the failure. Two thirds head keeps the message and throw site, one third
     * tail keeps the deepest causes. A cut point shifts by one char when it would split a
     * surrogate pair, so the output never carries an unpaired surrogate.
     */
    internal fun truncateStackTrace(trace: String, maxLength: Int = MAX_RUN_ENTRY_STACKTRACE_LENGTH): String {
        if (trace.length <= maxLength) return trace
        var head = maxLength * 2 / 3
        var tailStart = trace.length - (maxLength - head)
        // Bounds-guarded: a degenerate cap collapses head to 0 / tailStart to the end, and this
        // helper formats *error* output — crashing here would mask the real test failure.
        if (head > 0 && trace[head - 1].isHighSurrogate()) head--
        if (tailStart < trace.length && trace[tailStart].isLowSurrogate()) tailStart++
        return trace.substring(0, head) +
                "\n... [${tailStart - head} chars truncated] ...\n" +
                trace.substring(tailStart)
    }

    /** Composes a test display name from the test name and its optional parent (suite) name. */
    internal fun composeName(testName: String, parentName: String?): String {
        val suite = parentName?.takeIf { it.isNotBlank() }
        return if (suite != null) "$suite.$testName" else testName
    }

    /**
     * Maps the integer value of [SMTestProxy.getMagnitude] to a [TestStatus], or null for
     * non-terminal states (not-run, running, terminated).
     *
     * Values are obtained from the public [SMTestProxy.getMagnitude] method (which returns
     * Magnitude.getValue()) to avoid importing the @Internal Magnitude type directly.
     * Suite nodes (COMPLETE_INDEX = 1, same int as PASSED_INDEX) are excluded upstream in
     * [collectRunEntries] via isSuite(), so value 1 can be safely mapped to PASSED here.
     *
     */
    internal fun magnitudeIndexToStatus(index: Int): TestStatus? = when (index) {
        0, 5 -> TestStatus.SKIPPED  // SKIPPED_INDEX, IGNORED_INDEX
        1 -> TestStatus.PASSED      // PASSED_INDEX (suites share this value but are filtered upstream)
        6 -> TestStatus.FAILED      // FAILED_INDEX
        8 -> TestStatus.ERROR       // ERROR_INDEX
        else -> null                // NOT_RUN(2), RUNNING(3), TERMINATED(4)
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
        // Wrappers like the Ultimate profiler's JavaConsoleWithProfilerWidget expose the real
        // console via the platform ConsoleViewWithDelegate interface. Never reference such
        // wrapper classes directly — they are internal, Ultimate-only, and absent in most IDEs.
        val unwrapped = (console as? ConsoleViewWithDelegate)?.delegate ?: console
        return (unwrapped as? SMTRunnerConsoleView)?.resultsViewer
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

    internal fun toTestResultInfo(test: SMTestProxy, project: Project): TestResultInfo {
        val status = when {
            test.isPassed -> "PASSED"
            test.isIgnored -> "IGNORED"
            else -> "FAILED"
        }

        val stacktrace = test.stacktrace?.let { truncateStackTrace(it, MAX_STACKTRACE_LENGTH) }

        var file: String? = null
        var line: Int? = null
        try {
            // MCP tool calls arrive on Ktor worker threads with no read lock. Location lookup
            // goes through SMTestLocator implementations into PSI and the stub index, which
            // assert read access — without a read action the assertion is thrown, swallowed by
            // the catch below, and file/line silently come back null for every test.
            ReadAction.run<Exception> {
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
            }
        } catch (e: ProcessCanceledException) {
            throw e
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
