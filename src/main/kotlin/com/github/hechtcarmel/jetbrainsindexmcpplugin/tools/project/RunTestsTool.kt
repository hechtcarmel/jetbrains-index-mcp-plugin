package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.LongPoll
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.RunTestsInProgressResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.RunTestsResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.TestStatus
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PsiUtils
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.TestResultsCollector
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.TestResultsCollector.extractTestRunnerResultsViewer
import com.intellij.execution.*
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.execution.testframework.sm.runner.ui.TestResultsViewer
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.ui.RunContentManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.util.messages.MessageBusConnection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class RunTestsTool : AbstractMcpTool() {

    /** How a single ide_run_tests call should behave: start a new run, or attach to a live one. */
    internal sealed interface RequestMode {
        data class Start(val target: String) : RequestMode
        data class Attach(val runId: String) : RequestMode
        data class Invalid(val message: String) : RequestMode
    }

    companion object {
        private val LOG = logger<RunTestsTool>()
        private const val DEFAULT_TIMEOUT_SECONDS = 120
        private val PROCESS_START_TIMEOUT = 15.seconds

        /** Grace period to let the IDE's test tree finalize after the process exits. Normally instant. */
        private val TEST_TREE_FINALIZE_TIMEOUT = 10.seconds

        /** See [LongPoll] for why every call must return well under the MCP client's own timeout. */
        internal const val DEFAULT_WAIT_SECONDS = LongPoll.DEFAULT_WAIT_SECONDS
        internal const val MAX_WAIT_SECONDS = LongPoll.MAX_WAIT_SECONDS

        /** `target` starts a run, `runId` polls one already in flight — exactly one must be given. */
        internal fun resolveRequestMode(arguments: JsonObject): RequestMode {
            val target = LongPoll.optionalTrimmedString(arguments, ParamNames.TARGET)
            val runId = LongPoll.optionalTrimmedString(arguments, ParamNames.RUN_ID)
            return when {
                target != null && runId != null -> RequestMode.Invalid(
                    "Provide either 'target' (to start a test run) or 'runId' (to poll a running one), not both."
                )
                runId != null -> RequestMode.Attach(runId)
                target != null -> RequestMode.Start(target)
                else -> RequestMode.Invalid(
                    "Either 'target' (to start a test run) or 'runId' (to poll a running one) is required."
                )
            }
        }

        internal fun resolveWaitSeconds(arguments: JsonObject): Int = LongPoll.resolveWaitSeconds(arguments)

        /**
         * Floor for [finalizeWaitMillis]: collecting the test tree after a late exit still gets a
         * short grace even when the wait budget is spent, because the run is removed right after
         * collection — returning empty-handed would drop the results permanently.
         */
        private const val MIN_FINALIZE_WAIT_MS = 3_000L

        /**
         * The tree-finalize grace must fit in what remains of the call's wait budget, or a process
         * exit landing near the end of the window stacks wait + finalize past the MCP client's
         * request timeout — the client aborts, and the just-collected-and-removed results are lost.
         */
        internal fun finalizeWaitMillis(waitSeconds: Int, callStartMs: Long, nowMs: Long): Long {
            val budgetLeftMs = waitSeconds * 1000L - (nowMs - callStartMs)
            return minOf(TEST_TREE_FINALIZE_TIMEOUT.inWholeMilliseconds, maxOf(MIN_FINALIZE_WAIT_MS, budgetLeftMs))
        }

        internal fun buildInProgressResult(
            runId: String,
            configName: String,
            elapsedSeconds: Long,
            timeoutSeconds: Int
        ): RunTestsInProgressResult = RunTestsInProgressResult(
            status = "running",
            runId = runId,
            configName = configName,
            elapsedSeconds = elapsedSeconds,
            timeoutSeconds = timeoutSeconds,
            message = "Test run '$configName' is still executing (${elapsedSeconds}s elapsed, " +
                    "${timeoutSeconds}s limit). The run continues in the IDE. Call ide_run_tests again " +
                    "with {\"runId\": \"$runId\"} to keep waiting for its results (include the same " +
                    "project_path if you provided one)."
        )

        /**
         * Parses a target string into a class name and optional method name.
         * - `"com.example.MyTest"` → `("com.example.MyTest", null)`
         * - `"com.example.MyTest#testFoo"` → `("com.example.MyTest", "testFoo")`
         * - `"com.example.MyTest#"` → `("com.example.MyTest", null)` (blank method)
         * - `"All Tests"` (no `#`) → `("All Tests", null)` (run-config name passthrough)
         */
        internal fun parseTarget(target: String): Pair<String, String?> {
            if (target.contains('#')) {
                val parts = target.split('#', limit = 2)
                return parts[0] to parts[1].takeIf { it.isNotBlank() }
            }
            return target to null
        }

        /**
         * Defaults to false: agent-driven runs return results in the response, so popping the Run
         * tool window only steals the user's focus (issue #278). The run content is still added to
         * the tool window — it just isn't activated.
         */
        internal fun shouldActivateToolWindow(arguments: JsonObject): Boolean =
            arguments[ParamNames.ACTIVATE_TOOL_WINDOW]?.jsonPrimitive?.booleanOrNull ?: false

        /**
         * `ExecutionManagerImpl` copies the run configuration's activate/focus flags onto the
         * descriptor and then invokes the environment callback, before `RunContentManagerImpl`
         * reads [RunContentDescriptor.isActivateToolWindowWhenAdded] to decide whether to open the
         * Run tool window. Overriding the flags here therefore suppresses activation for this run
         * only, without mutating the user's persisted run configuration
         * (`activateToolWindowBeforeRun`).
         */
        internal fun suppressToolWindowActivation() = ProgramRunner.Callback { descriptor ->
            descriptor?.isActivateToolWindowWhenAdded = false
            descriptor?.isAutoFocusContent = false
        }
    }

    override val name = ToolNames.RUN_TESTS

    override val description = """
        Run tests using the IDE's run configuration infrastructure and return structured results.

        The target can be:
        - An existing run configuration name (e.g. "All Tests") — works for ANY language/framework
        - A fully qualified class name (e.g. "com.example.MyTest") — Java/Kotlin only
        - A class and method separated by '#' (e.g. "com.example.MyTest#testFoo") — Java/Kotlin only

        Creating a run config from a class/method FQN is supported only for Java/Kotlin. For other
        languages (Python, JS/TS, Go, PHP, Rust), pass an existing run configuration name instead.

        Long-running runs: each call blocks at most waitSeconds (default $DEFAULT_WAIT_SECONDS) so your MCP client's
        request timeout is never hit. If the tests are still executing when the wait budget ends, the
        call returns {"status": "running", "runId": "..."} while the run continues inside the IDE —
        call this tool again with that runId (and no target) to keep waiting. The run itself is
        bounded by timeoutSeconds: once it expires the process is killed and the next poll reports
        timedOut: true.

        Returns: success status, exit code, pass/fail/error counts, and per-test results.
        Results are read directly from the IDE's test runner, so they reflect this run (not stale report
        files) and work with any Service-Message-based framework (JUnit, TestNG, pytest, Jest, Go test, PHPUnit).

        Parameters:
        - project_path (optional): required when multiple projects are open.
        - target: existing run config name, fully qualified class (com.example.MyTest), or class#method
          (com.example.MyTest#testFoo). Exactly one of target / runId is required.
        - runId: id from a previous {"status": "running"} response; attaches to that run and keeps waiting.
        - timeoutSeconds (optional, default $DEFAULT_TIMEOUT_SECONDS): maximum seconds the test RUN may take before its
          process is killed. Applies to the whole run, across polls; ignored when runId is given.
        - waitSeconds (optional, default $DEFAULT_WAIT_SECONDS, max $MAX_WAIT_SECONDS): maximum seconds THIS CALL may block before
          returning results or a "running" status. Keep it below your MCP client's request timeout.
        - activateToolWindow (optional, default false): open the Run tool window for this run. By default
          the run stays in the background without stealing focus; its content is still added to the Run
          tool window for manual inspection.

        Example: {"target": "com.example.MyTest", "timeoutSeconds": 7200}, then if a "running" status
        comes back: {"runId": "<runId from that response>"}
    """.trimIndent()

    override val inputSchema: ToolSchema = SchemaBuilder.tool()
        .projectPath()
        .stringProperty(
            ParamNames.TARGET,
            "Test target: existing run config name, fully qualified class (com.example.MyTest), or " +
                    "class#method (com.example.MyTest#testFoo). Exactly one of target/runId is required."
        )
        .stringProperty(
            ParamNames.RUN_ID,
            "runId from a previous {\"status\": \"running\"} response: attaches to that run and keeps " +
                    "waiting instead of starting a new one. Exactly one of target/runId is required."
        )
        .intProperty(
            ParamNames.TIMEOUT_SECONDS,
            "Maximum seconds the whole test run may take before its process is killed (enforced across " +
                    "polls). Default: $DEFAULT_TIMEOUT_SECONDS. Ignored when runId is given."
        )
        .intProperty(
            ParamNames.WAIT_SECONDS,
            "Maximum seconds this call may block before returning results or a \"running\" status. " +
                    "Default: $DEFAULT_WAIT_SECONDS, max: $MAX_WAIT_SECONDS. Keep below your MCP client's request timeout."
        )
        .booleanProperty(
            ParamNames.ACTIVATE_TOOL_WINDOW,
            "Open (activate) the Run tool window for this run. Default: false — the run executes in " +
                    "the background without stealing focus; its content is still added to the Run tool window."
        )
        .build()

    /** Attach polls (`runId`) read no PSI until final collection — skip the per-call sync tax. */
    override fun needsPsiSync(arguments: JsonObject): Boolean =
        LongPoll.optionalTrimmedString(arguments, ParamNames.RUN_ID) == null

    override suspend fun doExecute(project: Project, arguments: JsonObject): CallToolResult {
        val callStartMs = System.currentTimeMillis()
        val waitSeconds = resolveWaitSeconds(arguments)

        return when (val mode = resolveRequestMode(arguments)) {
            is RequestMode.Invalid -> createErrorResult(mode.message)

            is RequestMode.Attach -> {
                val run = ActiveTestRunRegistry.getInstance(project).get(mode.runId)
                    ?: return createErrorResult(
                        "No active test run with id '${mode.runId}'. Its results may have already been " +
                                "collected, the run may have been evicted after completing, or the IDE was " +
                                "restarted. Start a new run by passing 'target' instead."
                    )
                awaitRunResult(project, run, waitSeconds, callStartMs)
            }

            is RequestMode.Start -> {
                val timeoutSeconds =
                    arguments[ParamNames.TIMEOUT_SECONDS]?.jsonPrimitive?.intOrNull ?: DEFAULT_TIMEOUT_SECONDS
                if (timeoutSeconds <= 0) {
                    return createErrorResult("timeoutSeconds must be a positive integer.")
                }

                val runConfiguration = resolveRunConfiguration(project, mode.target)
                    ?: return createErrorResult(
                        "Could not find or create a run configuration for target '${mode.target}'. " +
                                "Provide an existing run configuration name or a fully qualified Java/Kotlin class name."
                    )

                startRun(
                    project,
                    runConfiguration,
                    timeoutSeconds,
                    shouldActivateToolWindow(arguments),
                    waitSeconds,
                    callStartMs
                )
            }
        }
    }

    /**
     * Launches the configuration and registers the run in [ActiveTestRunRegistry], which owns its
     * lifetime from here on: the registry watchdog kills the process at `timeoutSeconds`, and the
     * message-bus connection is disconnected when the run is collected or evicted. This call only
     * borrows the run to wait on it within the call's wait budget.
     */
    private suspend fun startRun(
        project: Project,
        runConfiguration: RunnerAndConfigurationSettings,
        timeoutSeconds: Int,
        activateToolWindow: Boolean,
        waitSeconds: Int,
        callStartMs: Long
    ): CallToolResult {
        val configName = runConfiguration.name
        val executor = DefaultRunExecutor.getRunExecutorInstance()
        val env = ExecutionEnvironmentBuilder.createOrNull(executor, runConfiguration)
            ?.build(if (activateToolWindow) null else suppressToolWindowActivation())
            ?: return createErrorResult("Could not build execution environment for '$configName'.")

        val exitCodeDeferred = CompletableDeferred<Int>()
        val processHandlerDeferred = CompletableDeferred<ProcessHandler>()
        val testCompletionDeferred = CompletableDeferred<SMTestProxy.SMRootTestProxy?>()

        val processListener = object : ProcessListener {
            override fun processTerminated(event: ProcessEvent) {
                exitCodeDeferred.complete(event.exitCode)
            }
        }

        val connection = project.messageBus.connect()
        connection.completeDeferredOnProcessStarted(env, processListener, processHandlerDeferred, configName)

        val handler = try {
            edtAction { ExecutionManager.getInstance(project).restartRunProfile(env) }
            withTimeoutOrNull(PROCESS_START_TIMEOUT) { processHandlerDeferred.await() }
        } catch (e: ProcessCanceledException) {
            connection.disconnect()
            throw e
        } catch (e: Exception) {
            connection.disconnect()
            return createErrorResult(e.message ?: "Test process failed to start for '$configName'.")
        } ?: run {
            connection.disconnect()
            return createErrorResult(
                "Test process did not start within ${PROCESS_START_TIMEOUT.inWholeSeconds} seconds for '$configName'."
            )
        }

        // From here to registration nothing may leak the connection: an exception (e.g. the
        // project closing mid-call) would otherwise leave an untracked run with no watchdog.
        val run = try {
            // The run's timeoutSeconds budget starts when the process starts, not when the tool
            // was called — config resolution and process spawn-up must not be billed to the run.
            val startedAtMs = System.currentTimeMillis()

            val runContentDescriptor = RunContentManager.getInstance(project).allDescriptors.find { it.processHandler === handler }
            val resultsViewer = extractTestRunnerResultsViewer(runContentDescriptor?.executionConsole)
            resultsViewer?.addEventsListener(object : TestResultsViewer.EventsListener {
                override fun onTestingFinished(sender: TestResultsViewer) {
                    testCompletionDeferred.complete(sender.testsRootNode.root)
                }
            })

            ActiveTestRunRegistry.getInstance(project).register(
                ActiveTestRunRegistry.ActiveTestRun(
                    id = UUID.randomUUID().toString(),
                    configName = configName,
                    startedAtMs = startedAtMs,
                    timeoutSeconds = timeoutSeconds,
                    handler = handler,
                    exitCode = exitCodeDeferred,
                    testRoot = testCompletionDeferred,
                    hasResultsViewer = resultsViewer != null,
                    connection = connection
                )
            )
        } catch (t: Throwable) {
            connection.disconnect()
            throw t
        }

        return awaitRunResult(project, run, waitSeconds, callStartMs)
    }

    /**
     * Waits for the run within what remains of this call's wait budget, then returns either the
     * final [RunTestsResult] (and removes the run from the registry) or a
     * [RunTestsInProgressResult] carrying the runId to poll with.
     */
    private suspend fun awaitRunResult(
        project: Project,
        run: ActiveTestRunRegistry.ActiveTestRun,
        waitSeconds: Int,
        callStartMs: Long
    ): CallToolResult {
        val exitCode: Int? = run.awaitWithinBudget(run.exitCode, waitSeconds, callStartMs)

        if (exitCode == null && !run.timedOutByWatchdog) {
            val elapsedSeconds = (System.currentTimeMillis() - run.startedAtMs) / 1000
            return createJsonResult(
                buildInProgressResult(run.id, run.configName, elapsedSeconds, run.timeoutSeconds)
            )
        }

        // Terminal: the process exited, or the watchdog killed it at timeoutSeconds (even if the
        // process has not confirmed its death yet).
        val smRoot = if (run.hasResultsViewer) {
            val finalizeMs = finalizeWaitMillis(waitSeconds, callStartMs, System.currentTimeMillis())
            withTimeoutOrNull(finalizeMs.milliseconds) { run.testRoot.await() }
        } else {
            null
        }
        if (smRoot == null) {
            LOG.debug("No SM test tree for '${run.configName}'; returning empty structured results.")
        }

        val tests = smRoot?.let { edtAction { TestResultsCollector.collectRunEntries(it) } } ?: emptyList()
        val passed = tests.count { it.status == TestStatus.PASSED }
        val failed = tests.count { it.status == TestStatus.FAILED }
        val errors = tests.count { it.status == TestStatus.ERROR }

        ActiveTestRunRegistry.getInstance(project).remove(run.id)

        val timedOut = run.timedOutByWatchdog
        val reportedExitCode = if (timedOut || exitCode == null) -1 else exitCode
        return createJsonResult(
            RunTestsResult(
                success = reportedExitCode == 0 && failed == 0 && errors == 0,
                timedOut = timedOut,
                noTestsFound = tests.isEmpty() && reportedExitCode == 0,
                exitCode = reportedExitCode,
                passed = passed,
                failed = failed,
                errors = errors,
                total = tests.size,
                tests = tests
            )
        )
    }

    private suspend fun resolveRunConfiguration(project: Project, target: String): RunnerAndConfigurationSettings? {
        val runManager = RunManager.getInstance(project)

        // Reuse an existing run configuration if the target names one directly.
        runManager.allSettings.find { it.name == target }?.let { return it }

        // Otherwise interpret the target as className (+ optional #method) and build a config from PSI.
        val (className, methodName) = parseTarget(target)

        requireSmartMode(project)

        val psiElement = suspendingReadAction {
            findClassByName(project, className)
                ?.let { if (methodName == null) it else findMethodElement(it, methodName) }
        } ?: return null

        return edtAction {
            val config = createConfigurationFromContext(project, psiElement) ?: return@edtAction null
            runManager.setTemporaryConfiguration(config)
            config
        }
    }

    private fun findMethodElement(psiClass: PsiElement, methodName: String): PsiMethod? =
        PsiUtils.resolveAsPsiClass(psiClass)?.methods?.firstOrNull { it.name == methodName }

    private fun createConfigurationFromContext(
        project: Project,
        psiElement: PsiElement
    ): RunnerAndConfigurationSettings? {
        val dataContext = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, project)
            .add(Location.DATA_KEY, PsiLocation.fromPsiElement(psiElement))
            .build()
        return ConfigurationContext.getFromContext(dataContext, ActionPlaces.UNKNOWN)
            .createConfigurationsFromContext()
            ?.firstOrNull()
            ?.configurationSettings
    }

    private fun MessageBusConnection.completeDeferredOnProcessStarted(
        env: ExecutionEnvironment,
        processListener: ProcessListener,
        processHandlerDeferred: CompletableDeferred<ProcessHandler>,
        configName: String
    ) {
        subscribe(ExecutionManager.EXECUTION_TOPIC, object : ExecutionListener {
            override fun processStarting(executorId: String, environment: ExecutionEnvironment, handler: ProcessHandler) {
                if (environment !== env) return
                handler.addProcessListener(processListener)
            }

            override fun processStarted(executorId: String, environment: ExecutionEnvironment, handler: ProcessHandler) {
                if (environment !== env) return
                processHandlerDeferred.complete(handler)
            }

            override fun processNotStarted(executorId: String, environment: ExecutionEnvironment) {
                if (environment !== env) return
                processHandlerDeferred.completeExceptionally(
                    IllegalStateException("Test process failed to start for '$configName'.")
                )
            }
        })
    }
}
