package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.RunTestsResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.TestStatus
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
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
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.execution.testframework.sm.runner.ui.TestResultsViewer
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
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class RunTestsTool : AbstractMcpTool() {

    companion object {
        private val LOG = logger<RunTestsTool>()
        private const val DEFAULT_TIMEOUT_SECONDS = 120
        private val PROCESS_START_TIMEOUT = 15.seconds

        /** Grace period to let the IDE's test tree finalize after the process exits. Normally instant. */
        private val TEST_TREE_FINALIZE_TIMEOUT = 10.seconds
        private const val MAX_OUTPUT_CHARS = 10_000
    }

    override val requiresPsiSync: Boolean = false

    override val name = ToolNames.RUN_TESTS

    override val description = """
        Run tests using the IDE's run configuration infrastructure. Waits for completion and returns results.

        The target can be:
        - An existing run configuration name (e.g. "All Tests") — works for ANY language/framework
        - A fully qualified class name (e.g. "com.example.MyTest") — Java/Kotlin only
        - A class and method separated by '#' (e.g. "com.example.MyTest#testFoo") — Java/Kotlin only

        Creating a run config from a class/method FQN is supported only for Java/Kotlin. For other
        languages (Python, JS/TS, Go, PHP, Rust), pass an existing run configuration name instead.

        Returns: success status, exit code, pass/fail/error counts, per-test results, and console output.
        Results are read directly from the IDE's test runner, so they reflect this run (not stale report
        files) and work with any Service-Message-based framework (JUnit, TestNG, pytest, Jest, Go test, PHPUnit).

        Parameters:
        - project_path (optional): required when multiple projects are open.
        - target (required): existing run config name, fully qualified class (com.example.MyTest), or class#method (com.example.MyTest#testFoo).
        - timeoutSeconds (optional, default 120): maximum seconds to wait for the test run to complete.

        Example: {"target": "com.example.MyTest"} or {"target": "All Tests", "timeoutSeconds": 60}
    """.trimIndent()

    override val inputSchema: JsonObject = SchemaBuilder.tool()
        .projectPath()
        .stringProperty(
            ParamNames.TARGET,
            "Test target: existing run config name, fully qualified class (com.example.MyTest), or class#method (com.example.MyTest#testFoo).",
            required = true
        )
        .intProperty(ParamNames.TIMEOUT_SECONDS, "Timeout in seconds. Default: $DEFAULT_TIMEOUT_SECONDS.")
        .build()

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val target = requiredStringArg(arguments, ParamNames.TARGET).getOrElse {
            return createErrorResult(it.message ?: "target is required")
        }
        val timeoutSeconds = arguments[ParamNames.TIMEOUT_SECONDS]?.jsonPrimitive?.intOrNull ?: DEFAULT_TIMEOUT_SECONDS
        if (timeoutSeconds <= 0) {
            return createErrorResult("timeoutSeconds must be a positive integer.")
        }

        val runConfiguration = resolveRunConfiguration(project, target)
            ?: return createErrorResult(
                "Could not find or create a run configuration for target '$target'. " +
                        "Provide an existing run configuration name or a fully qualified Java/Kotlin class name."
            )

        return runAndCollectResults(project, runConfiguration, timeoutSeconds.seconds)
    }

    /**
     * Launches the configuration, waits for termination or [timeout], and reads the test results
     * straight from the IDE's live test tree (uses the same model that backs the IDE's test tool window).
     */
    private suspend fun runAndCollectResults(
        project: Project,
        runConfiguration: RunnerAndConfigurationSettings,
        timeout: Duration
    ): ToolCallResult {
        val configName = runConfiguration.name
        val executor = DefaultRunExecutor.getRunExecutorInstance()
        val env = ExecutionEnvironmentBuilder.createOrNull(executor, runConfiguration)?.build()
            ?: return createErrorResult("Could not build execution environment for '$configName'.")

        val output = StringBuilder()
        val exitCodeDeferred = CompletableDeferred<Int>()
        val processHandlerDeferred = CompletableDeferred<ProcessHandler>()
        val testCompletionDeferred = CompletableDeferred<SMTestProxy.SMRootTestProxy?>()

        val processListener = object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                // Keep a rolling window of the most recent output — for tests the tail
                // (failures, stack traces, summary) is the useful part.
                synchronized(output) {
                    output.append(event.text)
                    val overflow = output.length - MAX_OUTPUT_CHARS
                    if (overflow > 0) output.delete(0, overflow)
                }
            }

            override fun processTerminated(event: ProcessEvent) {
                exitCodeDeferred.complete(event.exitCode)
            }
        }

        val connection = project.messageBus.connect()
        connection.completeDeferredOnProcessStarted(env, processListener, processHandlerDeferred, configName)

        try {
            val handler = try {
                edtAction { ExecutionManager.getInstance(project).restartRunProfile(env) }
                withTimeoutOrNull(PROCESS_START_TIMEOUT) { processHandlerDeferred.await() }
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (e: Exception) {
                return createErrorResult(e.message ?: "Test process failed to start for '$configName'.")
            } ?: return createErrorResult(
                "Test process did not start within ${PROCESS_START_TIMEOUT.inWholeSeconds} seconds for '$configName'."
            )

            val runContentDescriptor = RunContentManager.getInstance(project).allDescriptors.find { it.processHandler === handler }
            val resultsViewer = extractTestRunnerResultsViewer(runContentDescriptor?.executionConsole)
            resultsViewer?.addEventsListener(object : TestResultsViewer.EventsListener {
                override fun onTestingFinished(sender: TestResultsViewer) {
                    testCompletionDeferred.complete(sender.testsRootNode.root)
                }
            })

            val exitCode = withTimeoutOrNull(timeout) { exitCodeDeferred.await() }
            if (exitCode == null) {
                handler.destroyProcess()
            }

            val smRoot = resultsViewer?.let {
                withTimeoutOrNull(TEST_TREE_FINALIZE_TIMEOUT) { testCompletionDeferred.await() }
            }
            if (smRoot == null) {
                LOG.debug("No SM test tree for '$configName'; returning empty structured results.")
            }

            val tests = smRoot?.let { edtAction { TestResultsCollector.collectRunEntries(it) } } ?: emptyList()
            val consoleOutput = synchronized(output) { output.toString() }
            val passed = tests.count { it.status == TestStatus.PASSED }
            val failed = tests.count { it.status == TestStatus.FAILED }
            val errors = tests.count { it.status == TestStatus.ERROR }

            return createJsonResult(
                RunTestsResult(
                    success = exitCode == 0 && failed == 0 && errors == 0,
                    timedOut = exitCode == null,
                    noTestsFound = tests.isEmpty() && exitCode == 0,
                    exitCode = exitCode ?: -1,
                    passed = passed,
                    failed = failed,
                    errors = errors,
                    total = tests.size,
                    tests = tests,
                    output = consoleOutput
                )
            )
        } finally {
            connection.disconnect()
        }
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

    private fun parseTarget(target: String): Pair<String, String?> {
        if (target.contains('#')) {
            val parts = target.split('#', limit = 2)
            return parts[0] to parts[1].takeIf { it.isNotBlank() }
        }
        val lastDot = target.lastIndexOf('.')
        if (lastDot > 0) {
            val lastSegment = target.substring(lastDot + 1)
            if (lastSegment.isNotEmpty() && lastSegment[0].isLowerCase()) {
                return target.substring(0, lastDot) to lastSegment
            }
        }
        return target to null
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
