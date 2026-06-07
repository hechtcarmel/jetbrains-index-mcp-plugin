package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.extensions.PluginId

/**
 * Utility for detecting Scala plugin availability.
 *
 * This class caches the Scala plugin availability check to avoid repeated checks.
 * The check is performed once at class initialization and the result is cached
 * for the lifetime of the JVM.
 *
 * ## Why This Matters
 *
 * Many tools in this plugin can use Scala-specific PSI APIs (ScClass, ScTrait, ScFunction, etc.)
 * that are only available when the Scala plugin is installed. In non-Scala IDEs,
 * these classes don't exist and would cause NoClassDefFoundError.
 *
 * ## Usage
 *
 * ```kotlin
 * if (ScalaPluginDetector.isScalaPluginAvailable) {
 *     // Safe to use Scala-specific APIs
 *     val scClass = ClassInheritorsSearch.search(...)
 * }
 * ```
 *
 * ## IDE Compatibility
 *
 * | IDE | Scala Plugin Available |
 * |-----|------------------------|
 * | IntelliJ IDEA (with Scala plugin) | Yes |
 * | IntelliJ IDEA (without Scala plugin) | No |
 * | PyCharm | No |
 * | WebStorm | No |
 * | GoLand | No |
 * | CLion | No |
 * | Rider | No |
 *
 * ## Plugin ID
 *
 * - `org.intellij.scala` - Official JetBrains Scala plugin
 */
object ScalaPluginDetector {

    private val LOG = logger<ScalaPluginDetector>()

    /**
     * Plugin ID for the official Scala plugin.
     */
    private const val SCALA_PLUGIN_ID = "org.intellij.scala"

    /**
     * Cached result of Scala plugin availability check.
     *
     * This is computed once at class initialization using a lazy delegate
     * that is thread-safe by default (LazyThreadSafetyMode.SYNCHRONIZED).
     */
    val isScalaPluginAvailable: Boolean by lazy {
        checkScalaPluginAvailable()
    }

    /**
     * Performs the actual check for Scala plugin availability.
     *
     * Checks if the official Scala plugin is installed and enabled.
     */
    private fun checkScalaPluginAvailable(): Boolean {
        return try {
            val pluginId = PluginId.getId(SCALA_PLUGIN_ID)
            val plugin = PluginManagerCore.getPlugin(pluginId)
            if (plugin != null && plugin.isEnabled) {
                LOG.info("Scala plugin detected (version: ${plugin.version}) - Scala-specific tools will be available")
                return true
            }

            LOG.info("Scala plugin not available - Scala-specific features will be disabled")
            false
        } catch (e: Exception) {
            LOG.warn("Failed to check Scala plugin availability: ${e.message}")
            false
        }
    }

    /**
     * Executes the given block only if the Scala plugin is available.
     *
     * @param block The code block to execute if Scala plugin is available
     * @return The result of the block, or null if Scala plugin is not available
     */
    inline fun <T> ifScalaAvailable(block: () -> T): T? {
        return if (isScalaPluginAvailable) {
            block()
        } else {
            null
        }
    }

    /**
     * Executes the given block only if the Scala plugin is available,
     * otherwise returns the provided default value.
     *
     * @param default The default value to return if Scala plugin is not available
     * @param block The code block to execute if Scala plugin is available
     * @return The result of the block, or the default value if Scala plugin is not available
     */
    inline fun <T> ifScalaAvailableOrElse(default: T, block: () -> T): T {
        return if (isScalaPluginAvailable) {
            block()
        } else {
            default
        }
    }
}
