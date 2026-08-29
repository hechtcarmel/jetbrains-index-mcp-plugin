package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project

import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.BuildProjectResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ClionBuildOutcome
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.impl.ConsoleViewImpl
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.task.ProjectTask
import com.intellij.task.ProjectTaskContext
import com.intellij.task.ProjectTaskManager
import com.intellij.task.ProjectTaskState
import com.intellij.ui.content.ContentFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.function.BiPredicate

/**
 * Issue #213 end to end: a CLion CMake build fails, the compiler/build-view channels stay empty
 * (CLion publishes to neither), and ide_build_project must still return the actual compiler
 * diagnostics — parsed out of the build log CLion printed into the Messages tool window — plus
 * the cidr session's failure summary, instead of `success: false` with an empty message list.
 *
 * The cidr classes cannot run here, so the test seeds what their listener proxy would have
 * produced ([ClionBuildOutcome]) and builds the Messages tool window shape from platform pieces,
 * exactly as [com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ClionBuildCaptureBehaviorTest]
 * does for the scrape alone.
 */
class BuildProjectClionCaptureBehaviorTest : McpPlatformTestCase() {

    private val tool = BuildProjectTool()
    private val json = Json { ignoreUnknownKeys = true }
    private val registeredIds = mutableListOf<String>()

    override fun tearDown() {
        try {
            registeredIds.forEach { ActiveBuildRegistry.getInstance(project).remove(it) }
            registeredIds.clear()
            ToolWindowManager.getInstance(project).getToolWindow("Messages")
                ?.contentManager?.removeAllContents(true)
        } finally {
            super.tearDown()
        }
    }

    private fun failedResult(): ProjectTaskManager.Result = object : ProjectTaskManager.Result {
        override fun getContext(): ProjectTaskContext = ProjectTaskContext()
        override fun isAborted(): Boolean = false
        override fun hasErrors(): Boolean = true
        override fun anyTaskMatches(predicate: BiPredicate<in ProjectTask, in ProjectTaskState>): Boolean = false
    }

    private fun addMessagesConsole(text: String) {
        val manager = ToolWindowManager.getInstance(project)
        val toolWindow = manager.getToolWindow("Messages") ?: manager.registerToolWindow("Messages") { }
        val console = TextConsoleBuilderFactory.getInstance().createBuilder(project).console as ConsoleViewImpl
        Disposer.register(testRootDisposable, console)
        console.component
        console.print(text, ConsoleViewContentType.NORMAL_OUTPUT)
        toolWindow.contentManager.addContent(
            ContentFactory.getInstance().createContent(console.component, "Build", false)
        )
    }

    fun testFailedClionBuildReturnsParsedDiagnosticsAndSummary() {
        addMessagesConsole(
            "ninja: build stopped: subcommand failed.\n" +
                    "${project.basePath}/src/main.cpp:7:5: error: use of undeclared identifier 'x'\n"
        )

        val build = ActiveBuildRegistry.getInstance(project).register(
            ActiveBuildRegistry.ActiveBuild(
                id = "clion-build",
                startedAtMs = System.currentTimeMillis(),
                timeoutSeconds = 600,
                includeRawOutput = false,
                result = CompletableDeferred(),
                connection = null
            )
        )
        registeredIds.add("clion-build")
        build.clionOutcome = ClionBuildOutcome().apply {
            buildStarted("cidr-1")
            buildFinished("cidr-1", succeeded = false, canceled = false, errors = 1, warnings = 0, message = null)
        }
        build.result.complete(failedResult())

        val result = runBlocking {
            tool.execute(project, buildJsonObject {
                put("buildId", "clion-build")
                put("waitSeconds", 5)
            })
        }

        assertToolSucceeded("a completed failed build still returns a structured result", result)
        val payload = json.decodeFromString(BuildProjectResult.serializer(), toolText(result))

        assertFalse(payload.success)
        val messages = payload.buildMessages
        val positioned = messages.find { it.file == "src/main.cpp" }
        assertNotNull(
            "the compiler error from CLion's build log must be parsed into a positioned message, got: $messages",
            positioned
        )
        assertEquals("ERROR", positioned!!.category)
        assertEquals("use of undeclared identifier 'x'", positioned.message)
        assertEquals(7, positioned.line)
        assertEquals(5, positioned.column)

        assertTrue(
            "the cidr session's failure summary must be included, got: $messages",
            messages.any { it.message.contains("Build failed: 1 error, 0 warnings") }
        )
        assertEquals("both messages are errors", 2, payload.errors)
    }

    fun testFailedClionBuildWithUnparsableLogStillCarriesTheFailureSummary() {
        addMessagesConsole("FAILED: build stopped without a compiler location\n")

        val build = ActiveBuildRegistry.getInstance(project).register(
            ActiveBuildRegistry.ActiveBuild(
                id = "clion-build-2",
                startedAtMs = System.currentTimeMillis(),
                timeoutSeconds = 600,
                includeRawOutput = false,
                result = CompletableDeferred(),
                connection = null
            )
        )
        registeredIds.add("clion-build-2")
        build.clionOutcome = ClionBuildOutcome().apply {
            buildStarted("cidr-2")
            buildFinished("cidr-2", succeeded = false, canceled = false, errors = 2, warnings = 1, message = "Ninja failed")
        }
        build.result.complete(failedResult())

        val result = runBlocking {
            tool.execute(project, buildJsonObject {
                put("buildId", "clion-build-2")
                put("waitSeconds", 5)
            })
        }

        val payload = json.decodeFromString(BuildProjectResult.serializer(), toolText(result))
        assertFalse(payload.success)
        val messages = payload.buildMessages
        assertTrue(
            "with nothing parseable, the response must still say what failed, got: $messages",
            messages.any { it.category == "ERROR" && it.message == "Build failed: 2 errors, 1 warning — Ninja failed" }
        )
    }

    /** A successful CLion build must not be polluted by fallback failure messages. */
    fun testSuccessfulClionBuildStaysClean() {
        addMessagesConsole("[42/42] Linking CXX executable app\n")

        val build = ActiveBuildRegistry.getInstance(project).register(
            ActiveBuildRegistry.ActiveBuild(
                id = "clion-build-3",
                startedAtMs = System.currentTimeMillis(),
                timeoutSeconds = 600,
                includeRawOutput = false,
                result = CompletableDeferred(),
                connection = null
            )
        )
        registeredIds.add("clion-build-3")
        build.clionOutcome = ClionBuildOutcome().apply {
            buildStarted("cidr-3")
            buildFinished("cidr-3", succeeded = true, canceled = false, errors = 0, warnings = 0, message = null)
        }
        build.result.complete(object : ProjectTaskManager.Result {
            override fun getContext(): ProjectTaskContext = ProjectTaskContext()
            override fun isAborted(): Boolean = false
            override fun hasErrors(): Boolean = false
            override fun anyTaskMatches(predicate: BiPredicate<in ProjectTask, in ProjectTaskState>): Boolean = false
        })

        val result = runBlocking {
            tool.execute(project, buildJsonObject {
                put("buildId", "clion-build-3")
                put("waitSeconds", 5)
            })
        }

        val payload = json.decodeFromString(BuildProjectResult.serializer(), toolText(result))
        assertTrue(payload.success)
        assertTrue(
            "a clean build must not carry fabricated messages, got: ${payload.buildMessages}",
            payload.buildMessages.isEmpty()
        )
    }
}
