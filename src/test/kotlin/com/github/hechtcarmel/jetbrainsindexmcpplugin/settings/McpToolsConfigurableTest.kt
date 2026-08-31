package com.github.hechtcarmel.jetbrainsindexmcpplugin.settings

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.McpServerService
import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBCheckBox

/**
 * Drives the real [McpToolsConfigurable] the same way the settings dialog does
 * (createComponent -> reset -> apply/reset -> disposeUIResources), with the server
 * service initialized so the tool list is populated.
 */
class McpToolsConfigurableTest : McpPlatformTestCase() {

    private lateinit var originalSettings: McpSettings.State

    override fun setUp() {
        super.setUp()
        McpServerService.getInstance().initialize()
        originalSettings = McpSettings.getInstance().state.copy(
            disabledTools = McpSettings.getInstance().state.disabledTools.toMutableSet()
        )
    }

    override fun tearDown() {
        try {
            McpSettings.getInstance().loadState(originalSettings)
        } finally {
            super.tearDown()
        }
    }

    fun testComponentShowsCheckboxForEveryRegisteredTool() {
        val allTools = McpServerService.getInstance().getToolRegistry()
            .getAllToolDefinitions().sortedBy { it.name }
        assertTrue("the tool registry must expose tools for the settings page to list", allTools.isNotEmpty())

        withConfigurable { configurable ->
            val checkboxes = readField<Map<String, JBCheckBox>>(configurable, "toolCheckBoxes")
            assertEquals(
                "the page must list every registered tool exactly once",
                allTools.map { it.name }.toSet(),
                checkboxes.keys
            )

            val settings = McpSettings.getInstance()
            for (tool in allTools) {
                val checkbox = checkboxes.getValue(tool.name)
                assertEquals(tool.name, checkbox.text)
                assertEquals(tool.description, checkbox.toolTipText)
                assertEquals(settings.isToolEnabled(tool.name), checkbox.isSelected)
            }
        }
    }

    fun testCheckboxesAreListedSortedByName() {
        val expected = McpServerService.getInstance().getToolRegistry()
            .getAllToolDefinitions().map { it.name }.sorted()

        withConfigurable { configurable ->
            val checkboxes = readField<Map<String, JBCheckBox>>(configurable, "toolCheckBoxes")
            assertEquals("tools must be listed in sorted order", expected, checkboxes.values.map { it.text })
        }
    }

    fun testIsModifiedReflectsCheckboxState() {
        withConfigurable { configurable ->
            assertFalse("a freshly reset page must not report modifications", configurable.isModified())

            val checkboxes = readField<Map<String, JBCheckBox>>(configurable, "toolCheckBoxes")
            val checkbox = checkboxes.values.first()
            checkbox.isSelected = !checkbox.isSelected

            assertTrue("toggling a checkbox must mark the page modified", configurable.isModified())

            configurable.reset()
            assertFalse("reset must restore checkboxes from settings", configurable.isModified())
        }
    }

    fun testApplyPersistsToggleThroughRealMcpSettings() {
        val settings = McpSettings.getInstance()
        val toolName = McpServerService.getInstance().getToolRegistry().getAllToolDefinitions().first().name
        val enabledBefore = settings.isToolEnabled(toolName)

        withConfigurable { configurable ->
            val checkboxes = readField<Map<String, JBCheckBox>>(configurable, "toolCheckBoxes")
            checkboxes.getValue(toolName).isSelected = !enabledBefore

            configurable.apply()

            assertEquals(
                "apply must persist the toggle into real McpSettings",
                !enabledBefore,
                settings.isToolEnabled(toolName)
            )
            assertFalse("apply must leave the page unmodified", configurable.isModified())

            configurable.reset()
            assertEquals(
                "reset must reload checkboxes from the applied settings",
                !enabledBefore,
                checkboxes.getValue(toolName).isSelected
            )
        }
    }

    fun testChildRegisteredUnderMcpSettingsParentInPluginXml() {
        val eps = Configurable.APPLICATION_CONFIGURABLE.extensionList
        val child = eps.firstOrNull { it.id == "mcp.settings.tools" }
        assertNotNull("the Exposed Tools page must be registered in plugin.xml", child)
        assertEquals("the Exposed Tools page must be nested under the main page", "mcp.settings", child!!.parentId)
        assertEquals(
            "the Exposed Tools page must be backed by McpToolsConfigurable",
            McpToolsConfigurable::class.java.name,
            child.instanceClass
        )
        assertNotNull("the main settings page registration must remain", eps.firstOrNull { it.id == "mcp.settings" })
    }

    private fun withConfigurable(block: (McpToolsConfigurable) -> Unit) {
        val configurable = McpToolsConfigurable()
        try {
            configurable.createComponent()
            configurable.reset()
            block(configurable)
        } finally {
            configurable.disposeUIResources()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> readField(configurable: McpToolsConfigurable, name: String): T {
        val field = McpToolsConfigurable::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.get(configurable) as T
    }
}
