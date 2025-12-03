package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.extensions.PluginId

/**
 * Utility for detecting Go plugin availability.
 *
 * This class caches the Go plugin availability check to avoid repeated checks.
 * The check is performed once at class initialization and the result is cached
 * for the lifetime of the JVM.
 *
 * ## Why This Matters
 *
 * Many tools in this plugin can use Go-specific PSI APIs (GoFile, GoTypeSpec,
 * GoFunctionDeclaration, etc.) that are only available when the Go plugin is installed.
 * In non-Go IDEs, these classes don't exist and would cause NoClassDefFoundError.
 *
 * ## Usage
 *
 * ```kotlin
 * if (GoPluginDetector.isGoPluginAvailable) {
 *     // Safe to use Go-specific APIs
 *     val goFile = psiFile as? GoFile
 * }
 * ```
 *
 * ## IDE Compatibility
 *
 * | IDE | Go Plugin Available |
 * |-----|---------------------|
 * | GoLand | Yes (bundled) |
 * | IntelliJ IDEA Ultimate | Yes (bundled/installable) |
 * | IntelliJ IDEA Community | No |
 * | PyCharm Professional | No |
 * | PyCharm Community | No |
 * | WebStorm | No |
 * | CLion | No |
 * | Rider | No |
 * | DataGrip | No |
 *
 * ## Plugin ID
 *
 * - `org.jetbrains.plugins.go` - Go plugin for GoLand and IntelliJ IDEA
 */
object GoPluginDetector {

    private val LOG = logger<GoPluginDetector>()

    /**
     * Plugin ID for the Go plugin.
     */
    private const val GO_PLUGIN_ID = "org.jetbrains.plugins.go"

    /**
     * Cached result of Go plugin availability check.
     *
     * This is computed once at class initialization using a lazy delegate
     * that is thread-safe by default (LazyThreadSafetyMode.SYNCHRONIZED).
     */
    val isGoPluginAvailable: Boolean by lazy {
        checkGoPluginAvailable()
    }

    /**
     * Performs the actual check for Go plugin availability.
     *
     * We check if the Go plugin is installed and enabled.
     */
    private fun checkGoPluginAvailable(): Boolean {
        return try {
            val pluginId = PluginId.getId(GO_PLUGIN_ID)
            val plugin = PluginManagerCore.getPlugin(pluginId)
            val available = plugin != null && plugin.isEnabled

            if (available) {
                LOG.info("Go plugin detected - Go-specific tools will be available")
            } else {
                LOG.info("Go plugin not available - Go-specific features will be disabled")
            }
            available
        } catch (e: Exception) {
            LOG.warn("Failed to check Go plugin availability: ${e.message}")
            false
        }
    }

    /**
     * Executes the given block only if the Go plugin is available.
     *
     * @param block The code block to execute if Go plugin is available
     * @return The result of the block, or null if Go plugin is not available
     */
    inline fun <T> ifGoAvailable(block: () -> T): T? {
        return if (isGoPluginAvailable) {
            block()
        } else {
            null
        }
    }

    /**
     * Executes the given block only if the Go plugin is available,
     * otherwise returns the provided default value.
     *
     * @param default The default value to return if Go plugin is not available
     * @param block The code block to execute if Go plugin is available
     * @return The result of the block, or the default value if Go plugin is not available
     */
    inline fun <T> ifGoAvailableOrElse(default: T, block: () -> T): T {
        return if (isGoPluginAvailable) {
            block()
        } else {
            default
        }
    }
}
