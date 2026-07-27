package com.github.hechtcarmel.jetbrainsindexmcpplugin.ui

import com.github.hechtcarmel.jetbrainsindexmcpplugin.McpConstants
import com.github.hechtcarmel.jetbrainsindexmcpplugin.actions.RefreshAction
import com.github.hechtcarmel.jetbrainsindexmcpplugin.history.CommandEntry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.history.CommandHistoryService
import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.TestActionEvent
import com.intellij.util.ui.UIUtil
import kotlinx.serialization.json.buildJsonObject
import javax.swing.DefaultListModel

class McpToolWindowFactoryTest : McpPlatformTestCase() {

    private lateinit var historyService: CommandHistoryService

    override fun setUp() {
        super.setUp()
        historyService = CommandHistoryService.getInstance(project)
        historyService.clearHistory()
    }

    override fun tearDown() {
        try {
            historyService.clearHistory()
        } finally {
            super.tearDown()
        }
    }

    fun testContentDisposalUnsubscribesPanelFromHistoryUpdates() {
        val toolWindow = toolWindow()
        McpToolWindowFactory().createToolWindowContent(project, toolWindow)
        val content = toolWindow.contentManager.contents.last()
        val panel = findPanel(toolWindow)

        recordCommand("before_dispose")
        assertEquals(listOf("before_dispose"), visibleToolNames(panel))

        toolWindow.contentManager.removeContent(content, true)

        recordCommand("after_dispose")
        assertEquals(
            "Removing the tool window content must dispose the panel and detach its history listener",
            listOf("before_dispose"),
            visibleToolNames(panel)
        )
    }

    fun testRefreshActionReachesPanelInsideWrapperComponent() {
        val toolWindow = toolWindow()
        McpToolWindowFactory().createToolWindowContent(project, toolWindow)
        val content = toolWindow.contentManager.contents.last()
        try {
            val panel = findPanel(toolWindow)

            recordCommand("recorded_entry")
            assertEquals(listOf("recorded_entry"), visibleToolNames(panel))

            // Simulate stale UI: only an explicit refresh may repopulate the list.
            historyListModel(panel).clear()
            assertEquals(emptyList<String>(), visibleToolNames(panel))

            val action = RefreshAction()
            action.actionPerformed(
                TestActionEvent.createTestEvent(action, SimpleDataContext.getProjectContext(project))
            )
            dispatchUiEvents()

            assertEquals(
                "Refresh action must locate the panel nested inside the wrapper component",
                listOf("recorded_entry"),
                visibleToolNames(panel)
            )
        } finally {
            toolWindow.contentManager.removeContent(content, true)
        }
    }

    private fun toolWindow(): ToolWindow {
        val manager = ToolWindowManager.getInstance(project)
        return manager.getToolWindow(McpConstants.TOOL_WINDOW_ID)
            ?: manager.registerToolWindow(McpConstants.TOOL_WINDOW_ID) {}
    }

    private fun findPanel(toolWindow: ToolWindow): McpToolWindowPanel {
        val component = toolWindow.contentManager.contents.last().component
        return UIUtil.findComponentOfType(component, McpToolWindowPanel::class.java)
            ?: error("McpToolWindowPanel not found inside the tool window content")
    }

    private fun recordCommand(toolName: String) {
        historyService.recordCommand(CommandEntry(toolName = toolName, parameters = buildJsonObject { }))
        dispatchUiEvents()
    }

    @Suppress("UNCHECKED_CAST")
    private fun historyListModel(panel: McpToolWindowPanel): DefaultListModel<CommandEntry> {
        val modelField = McpToolWindowPanel::class.java.getDeclaredField("historyListModel")
        modelField.isAccessible = true
        return modelField.get(panel) as DefaultListModel<CommandEntry>
    }

    private fun visibleToolNames(panel: McpToolWindowPanel): List<String> {
        val model = historyListModel(panel)
        return (0 until model.size()).map { model.getElementAt(it).toolName }
    }

    private fun dispatchUiEvents() {
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    }
}
