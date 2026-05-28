package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

import com.intellij.ide.plugins.PluginManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.extensions.PluginId

class PluginDetector(
    val name: String,
    private val pluginIds: List<String>,
    private val fallbackClass: String? = null
) {
    private val log = logger<PluginDetector>()

    val isAvailable: Boolean by lazy { checkAvailable() }

    inline fun <T> ifAvailable(action: () -> T): T? =
        if (isAvailable) action() else null

    inline fun <T> ifAvailableOrElse(default: T, action: () -> T): T =
        if (isAvailable) action() else default

    private fun checkAvailable(): Boolean {
        for (pluginId in pluginIds) {
            try {
                val plugin = PluginManager.getInstance().findEnabledPlugin(PluginId.getId(pluginId))
                if (plugin != null) {
                    log.info("$name plugin detected via plugin ID ($pluginId)")
                    return true
                }
            } catch (e: Exception) {
                log.debug("Failed to check $name plugin $pluginId: ${e.message}")
            }
        }

        if (fallbackClass != null) {
            try {
                Class.forName(fallbackClass)
                log.info("$name support detected via PSI class ($fallbackClass)")
                return true
            } catch (e: ClassNotFoundException) {
                log.debug("$name PSI class not found: $fallbackClass")
            } catch (e: Exception) {
                log.debug("Failed to check $name PSI class: ${e.message}")
            }
        }

        log.info("$name plugin not available — $name-specific features will be disabled")
        return false
    }
}
