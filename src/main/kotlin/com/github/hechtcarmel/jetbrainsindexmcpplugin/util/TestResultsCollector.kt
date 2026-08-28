package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.TestResultInfo
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.TestRunEntry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.TestStatus
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.TestSummary
import com.intellij.execution.filters.HyperlinkInfo
import com.intellij.execution.testframework.Printable
import com.intellij.execution.testframework.Printer
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView
import com.intellij.execution.testframework.sm.runner.ui.SMTestRunnerResultsForm
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.execution.ui.ConsoleViewWithDelegate
import com.intellij.execution.ui.ExecutionConsole
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.ui.RunContentManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager

data class TestCollectionResult(
    val testResults: List<TestResultInfo>,
    val testSummary: TestSummary,
    val truncated: Boolean
)

object TestResultsCollector {

    private val LOG = logger<TestResultsCollector>()

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

    /**
     * Per-test cap for ide_run_tests console output (issue #346), head+tail truncated like
     * stack traces: a normal logging test passes through untouched, a spraying one keeps its
     * start and its end.
     */
    internal const val MAX_RUN_ENTRY_OUTPUT_LENGTH = 10_000

    /**
     * Aggregate console-output budget for one ide_run_tests result, in run order — same
     * anti-multi-MB-response rationale as [MAX_RUN_TOTAL_STACKTRACE_CHARS]: once spent, later
     * tests carry no output field.
     */
    internal const val MAX_RUN_TOTAL_OUTPUT_CHARS = 100_000

    /**
     * Cap for run-level output not attributed to any test (framework/suite messages, build-runner
     * log lines). Larger than the per-test cap because a Gradle-run build log lands here.
     */
    internal const val MAX_RUN_LEVEL_OUTPUT_LENGTH = 20_000

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
        totalStackTraceBudget: Int = MAX_RUN_TOTAL_STACKTRACE_CHARS,
        outputs: Map<SMTestProxy, String> = emptyMap()
    ): List<TestRunEntry> {
        var traceBudget = totalStackTraceBudget
        return root.allTests
            .filter { isRunEntryLeaf(root, it) }
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
                        stackTrace = stackTrace,
                        output = outputs[test]
                    )
                }
            }
    }

    /** The nodes [collectRunEntries] reports as per-test entries; everything else is run-level. */
    private fun isRunEntryLeaf(root: SMTestProxy.SMRootTestProxy, node: SMTestProxy): Boolean =
        node !== root && node.isLeaf && !node.isSuite && !node.isConfig

    /**
     * Console output of one finished test run (issue #346), split the way [collectRunEntries]
     * splits the tree: [perTest] holds each reportable test's own output, [unattributed]
     * everything printed by the root, suites, and config nodes (framework messages,
     * `@BeforeAll`/`@AfterAll` prints, build-runner log lines).
     */
    class RunOutputs(
        val perTest: Map<SMTestProxy, String>,
        val unattributed: String?
    )

    /**
     * Replays each node's own console printables — the same data the IDE's test console renders —
     * and returns them as plain text: stdout and stderr merged in print order, ANSI escapes
     * stripped by [Printer]'s default `printWithAnsiColoring`, system messages (the launch
     * command line, "Process finished with exit code …") excluded. Failure state is NOT part of a
     * node's own printables (`printOwnPrintablesOn` with an explicit `skipFileContent` skips the
     * state, exactly like the platform's own `TestResultsXmlFormatter`), so a failed test's
     * output never duplicates the errorMessage/stackTrace fields.
     *
     * MUST run off the EDT in production: [com.intellij.execution.testframework.CompositePrintable]
     * defers EDT-initiated replay to a background executor, so an EDT caller would return before
     * any text arrived and silently collect nothing. Callers on a background thread get a fully
     * synchronous replay. In unit-test mode the EDT replay is synchronous, so tests may call this
     * directly.
     */
    fun collectRunOutputs(
        root: SMTestProxy.SMRootTestProxy,
        perTestLimit: Int = MAX_RUN_ENTRY_OUTPUT_LENGTH,
        totalOutputBudget: Int = MAX_RUN_TOTAL_OUTPUT_CHARS,
        runLevelLimit: Int = MAX_RUN_LEVEL_OUTPUT_LENGTH
    ): RunOutputs {
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread && !application.isUnitTestMode) {
            LOG.error("collectRunOutputs called on the EDT: replay would be deferred and collect nothing")
        }
        val perTest = HashMap<SMTestProxy, String>()
        val runLevel = BoundedTextCollector(runLevelLimit)
        // Soft budget, decremented in run order like the stack-trace budget: the last attached
        // output may overshoot by at most one per-test cap.
        var outputBudget = totalOutputBudget
        for (node in root.allTests) {
            // Same split as collectRunEntries: structural leaf AND terminal magnitude. A leaf
            // killed mid-run (watchdog at timeoutSeconds → RUNNING/TERMINATED) gets no entry
            // there, so its output — often exactly what explains the hang — must ride on the
            // run-level field instead of being collected into perTest and silently orphaned.
            val isReportedEntry = isRunEntryLeaf(root, node) && magnitudeIndexToStatus(node.magnitude) != null
            if (isReportedEntry) {
                if (outputBudget <= 0) continue
                val collector = BoundedTextCollector(perTestLimit)
                replayOwnOutput(node, collector, skipFileContent = false)
                val text = collector.build()?.takeIf(String::isNotBlank) ?: continue
                perTest[node] = text
                outputBudget -= text.length
            } else {
                // A node with children flushes its printables file with the children's replayed
                // output embedded (CompositePrintable.flush past 500 printables), so reading it
                // would duplicate per-test output — skip the file there and lose only the
                // pathological >500-direct-chunk history. Childless nodes' files are purely
                // their own output.
                replayOwnOutput(node, runLevel, skipFileContent = !node.isLeaf)
            }
        }
        return RunOutputs(perTest, runLevel.build()?.takeIf(String::isNotBlank))
    }

    private fun replayOwnOutput(node: SMTestProxy, collector: BoundedTextCollector, skipFileContent: Boolean) {
        val printer = object : Printer {
            override fun print(text: String, contentType: ConsoleViewContentType) {
                if (contentType != ConsoleViewContentType.SYSTEM_OUTPUT) {
                    collector.append(text)
                }
            }

            override fun printHyperlink(text: String, info: HyperlinkInfo?) {
                collector.append(text)
            }

            override fun onNewAvailable(printable: Printable) {}

            override fun mark() {}
        }
        // The two-arg CompositePrintable overload: own printables only (child proxies filtered,
        // no failure state appended) — the platform's export-to-XML extraction path.
        node.printOwnPrintablesOn(printer, skipFileContent)
    }

    /**
     * Head+tail truncation via [BoundedTextCollector] (the single implementation of the policy):
     * for chained exceptions the root cause sits at the BOTTOM of the trace (the issue #316
     * repro is 500 nested causes), so keeping only the head would drop exactly the part that
     * explains the failure. Two thirds head keeps the message and throw site, one third tail
     * keeps the deepest causes, and a cut never leaves an unpaired surrogate.
     */
    internal fun truncateStackTrace(trace: String, maxLength: Int = MAX_RUN_ENTRY_STACKTRACE_LENGTH): String {
        if (trace.length <= maxLength) return trace
        // A non-positive cap can't go through the collector (it requires a positive cap) but must
        // still truncate — not pass the oversized trace through, not crash: this helper formats
        // *error* output, and either failure mode would mask the real test failure
        // (testDegenerateCapDoesNotCrash pins this).
        if (maxLength <= 0) return "\n... [${trace.length} chars truncated] ...\n"
        return BoundedTextCollector(maxLength).apply { append(trace) }.build()
            ?: trace // unreachable for non-empty input; never crash while formatting error output
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
