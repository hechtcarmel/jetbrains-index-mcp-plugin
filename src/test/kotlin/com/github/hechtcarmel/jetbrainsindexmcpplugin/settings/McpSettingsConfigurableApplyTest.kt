package com.github.hechtcarmel.jetbrainsindexmcpplugin.settings

import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.intellij.openapi.options.ConfigurationException
import com.intellij.ui.components.JBTextField
import java.net.InetSocketAddress
import java.net.ServerSocket
import javax.swing.JSpinner

/**
 * Covers the two apply-time validation traps in [McpSettingsConfigurable]:
 *
 * 1. `isHostValidationPending` is set on every keystroke but was only cleared by the async
 *    focus-lost validation, so a keyboard-driven Apply (Enter while the host field has focus)
 *    was rejected forever. apply() must instead validate the host synchronously.
 * 2. The port-availability bind check used to run even when host/port were unchanged, so an
 *    externally occupied port blocked applying unrelated settings.
 */
class McpSettingsConfigurableApplyTest : McpPlatformTestCase() {

    private var originalHost: String = ""
    private var originalPort: Int = 0
    private var originalMaxHistorySize: Int = 0

    override fun setUp() {
        super.setUp()
        val settings = McpSettings.getInstance()
        originalHost = settings.serverHost
        originalPort = settings.serverPort
        originalMaxHistorySize = settings.maxHistorySize
    }

    override fun tearDown() {
        try {
            val settings = McpSettings.getInstance()
            settings.serverHost = originalHost
            settings.serverPort = originalPort
            settings.maxHistorySize = originalMaxHistorySize
        } finally {
            super.tearDown()
        }
    }

    fun testApplySucceedsWhileHostValidationIsPendingForValidHost() {
        withConfigurable { configurable ->
            val hostField = hostField(configurable)

            // Typing marks validation as pending; only losing focus would clear it, and focus
            // never leaves the field when the dialog is confirmed from the keyboard. The
            // trailing space is trimmed by apply(), so the effective address stays unchanged.
            hostField.text = McpSettings.getInstance().serverHost + " "
            assertTrue(
                "Editing the host field must mark validation as pending",
                isHostValidationPending(configurable)
            )

            configurable.apply()

            assertFalse(
                "Synchronous validation during apply must clear the pending flag",
                isHostValidationPending(configurable)
            )
        }
    }

    fun testApplyWhileHostValidationIsPendingRejectsInvalidHost() {
        withConfigurable { configurable ->
            val hostField = hostField(configurable)

            hostField.text = "999.999.999.999"
            assertTrue(isHostValidationPending(configurable))

            try {
                configurable.apply()
                fail("apply() must reject an invalid host even while async validation is pending")
            } catch (e: ConfigurationException) {
                assertTrue(
                    "Error must report the invalid host, not a pending validation: ${e.messageHtml}",
                    e.messageHtml.toString().contains("999.999.999.999")
                )
            }

            assertEquals(
                "An invalid host must not be persisted",
                originalHost,
                McpSettings.getInstance().serverHost
            )
        }
    }

    fun testApplyWithUnchangedAddressSucceedsWhilePortIsOccupiedExternally() {
        val settings = McpSettings.getInstance()
        ServerSocket().use { externalListener ->
            externalListener.reuseAddress = true
            externalListener.bind(InetSocketAddress(settings.serverHost, 0))
            settings.serverPort = externalListener.localPort

            withConfigurable { configurable ->
                maxHistorySizeSpinner(configurable).value = 275

                // Host and port are unchanged, so the bind check must be skipped even though
                // the configured address is currently held by another process.
                configurable.apply()
            }

            assertEquals(
                "Unrelated settings must apply while the unchanged port is externally occupied",
                275,
                settings.maxHistorySize
            )
        }
    }

    private fun withConfigurable(block: (McpSettingsConfigurable) -> Unit) {
        val configurable = McpSettingsConfigurable()
        try {
            configurable.createComponent()
            configurable.reset()
            block(configurable)
        } finally {
            configurable.disposeUIResources()
        }
    }

    private fun hostField(configurable: McpSettingsConfigurable): JBTextField =
        readField(configurable, "serverHostField")

    private fun maxHistorySizeSpinner(configurable: McpSettingsConfigurable): JSpinner =
        readField(configurable, "maxHistorySizeSpinner")

    private fun isHostValidationPending(configurable: McpSettingsConfigurable): Boolean =
        readField(configurable, "isHostValidationPending")

    @Suppress("UNCHECKED_CAST")
    private fun <T> readField(configurable: McpSettingsConfigurable, name: String): T {
        val field = McpSettingsConfigurable::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.get(configurable) as T
    }
}
