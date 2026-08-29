package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.impl.ConsoleViewImpl
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.ContentFactory

/**
 * CLion prints its CMake build log into ConsoleViewImpl-based tabs of the "Messages" tool
 * window ([ClionBuildCapture]). The scrape must read that text so [BuildOutputParser] can turn
 * it into positioned diagnostics — issue #213. The cidr classes themselves cannot be on the
 * test classpath, so these tests build the same tool-window shape out of platform pieces.
 */
class ClionBuildCaptureBehaviorTest : McpPlatformTestCase() {

    private fun messagesToolWindow(): ToolWindow {
        val manager = ToolWindowManager.getInstance(project)
        return manager.getToolWindow("Messages")
            ?: manager.registerToolWindow("Messages") { }
    }

    override fun tearDown() {
        try {
            ToolWindowManager.getInstance(project).getToolWindow("Messages")
                ?.contentManager?.removeAllContents(true)
        } finally {
            super.tearDown()
        }
    }

    private fun addBuildConsoleContent(tabName: String, text: String): ConsoleViewImpl {
        val console = TextConsoleBuilderFactory.getInstance().createBuilder(project).console as ConsoleViewImpl
        Disposer.register(testRootDisposable, console)
        console.component // initializes the console editor, as CLion's build tab creation does
        console.print(text, ConsoleViewContentType.NORMAL_OUTPUT)

        val content = ContentFactory.getInstance().createContent(console.component, tabName, false)
        messagesToolWindow().contentManager.addContent(content)
        return console
    }

    fun testSubscribeDegradesToNullWithoutClionClasses() {
        assertFalse("cidr classes must not be on the IU test classpath", ClionBuildCapture.isAvailable())

        val connection = project.messageBus.connect(testRootDisposable)
        assertNull(
            "outside CLion the subscription must be a silent no-op, not an error",
            ClionBuildCapture.subscribe(connection) { }
        )
    }

    fun testCollectConsoleOutputReadsMessagesToolWindowConsoles() {
        addBuildConsoleContent(
            "Build",
            "ninja: build stopped\n/repo/src/main.cpp:7:5: error: use of undeclared identifier 'x'\n"
        )

        val collected = ClionBuildCapture.collectConsoleOutput(project, emptyList(), 100_000)

        assertTrue(
            "the build console's text must be captured, got: <$collected>",
            collected.contains("error: use of undeclared identifier 'x'")
        )
    }

    fun testCollectConsoleOutputKeepsTheTailWhenOverCap() {
        val filler = "x".repeat(200) + "\n"
        addBuildConsoleContent("Build", filler.repeat(10) + "THE-FINAL-ERROR-LINE\n")

        val collected = ClionBuildCapture.collectConsoleOutput(project, emptyList(), 300)

        assertTrue("collected output must respect the cap", collected.length <= 300)
        assertTrue(
            "the tail — where compiler errors cluster — must survive the cap",
            collected.contains("THE-FINAL-ERROR-LINE")
        )
    }

    fun testCollectConsoleOutputConcatenatesMultipleTabs() {
        addBuildConsoleContent("Debug", "first-profile-output\n")
        addBuildConsoleContent("Release", "second-profile-output\n")

        val collected = ClionBuildCapture.collectConsoleOutput(project, emptyList(), 100_000)

        assertTrue(collected.contains("first-profile-output"))
        assertTrue(collected.contains("second-profile-output"))
    }

    fun testCollectConsoleOutputIsEmptyWhenNoBuildTabsExist() {
        messagesToolWindow() // window may exist from a sibling test; what matters is it has no tabs

        assertEquals("", ClionBuildCapture.collectConsoleOutput(project, emptyList(), 100_000))
    }
}
