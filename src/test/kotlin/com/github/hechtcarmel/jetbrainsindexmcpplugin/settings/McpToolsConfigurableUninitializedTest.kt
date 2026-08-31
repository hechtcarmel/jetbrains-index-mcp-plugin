package com.github.hechtcarmel.jetbrainsindexmcpplugin.settings

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.McpServerService
import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Covers the "Server is initializing..." guard branch. The service singleton is app-level
 * and outlives any one test class, so the uninitialized state is forced via reflection and
 * restored afterwards — no public API un-initializes the service.
 */
class McpToolsConfigurableUninitializedTest : McpPlatformTestCase() {

    fun testShowsInitializingGuardWithoutServer() {
        val service = McpServerService.getInstance()
        val wasInitialized = setServiceInitialized(service, false)
        try {
            withConfigurable { configurable ->
                val panel = readField<JPanel>(configurable, "panel")
                assertNotNull(
                    "the page must show the initializing guard",
                    findLabel(panel, "Server is initializing...")
                )
                assertTrue(
                    "no tool checkboxes may exist before the server is initialized",
                    readField<Map<String, JBCheckBox>>(configurable, "toolCheckBoxes").isEmpty()
                )
                assertFalse(configurable.isModified())

                configurable.apply()
                configurable.reset()
            }
        } finally {
            setServiceInitialized(service, wasInitialized)
        }
    }

    private fun setServiceInitialized(service: McpServerService, initialized: Boolean): Boolean {
        val field = McpServerService::class.java.getDeclaredField("isInitialized")
        field.isAccessible = true
        val previous = field.getBoolean(service)
        field.setBoolean(service, initialized)
        return previous
    }

    private fun findLabel(root: JComponent, text: String): JBLabel? {
        val queue = ArrayDeque<JComponent>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (current is JBLabel && current.text == text) return current
            for (i in 0 until current.componentCount) {
                (current.getComponent(i) as? JComponent)?.let { queue.add(it) }
            }
        }
        return null
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
