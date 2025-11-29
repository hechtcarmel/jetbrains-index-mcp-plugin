package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.extensions.PluginId

/**
 * Utility for detecting JavaScript/TypeScript plugin availability.
 *
 * This class caches the JavaScript plugin availability check to avoid repeated checks.
 * The check is performed once at class initialization and the result is cached
 * for the lifetime of the JVM.
 *
 * ## Why This Matters
 *
 * Many tools in this plugin can use JavaScript-specific PSI APIs (JSClass, JSFunction, etc.)
 * that are only available when the JavaScript plugin is installed. In non-JavaScript IDEs,
 * these classes don't exist and would cause NoClassDefFoundError.
 *
 * ## Usage
 *
 * ```kotlin
 * if (JavaScriptPluginDetector.isJavaScriptPluginAvailable) {
 *     // Safe to use JavaScript-specific APIs
 *     val jsClass = JSClassResolver.getInstance().findClass(...)
 * }
 * ```
 *
 * ## IDE Compatibility
 *
 * | IDE | JavaScript Plugin Available |
 * |-----|----------------------------|
 * | WebStorm | Yes (bundled) |
 * | IntelliJ IDEA Ultimate | Yes (bundled) |
 * | PhpStorm | Yes (bundled) |
 * | PyCharm Professional | Yes (bundled) |
 * | Rider | Yes (bundled) |
 * | IntelliJ IDEA Community | No |
 * | PyCharm Community | No |
 * | GoLand | No |
 * | CLion | No |
 *
 * ## Supported Languages
 *
 * The JavaScript plugin handles multiple language variants:
 * - JavaScript (ES5, ES6+)
 * - TypeScript
 * - JSX / TSX
 * - Flow
 */
object JavaScriptPluginDetector {

    private val LOG = logger<JavaScriptPluginDetector>()

    /**
     * Plugin ID for the JavaScript plugin.
     *
     * This single plugin provides support for JavaScript, TypeScript, and related languages.
     */
    private const val JAVASCRIPT_PLUGIN_ID = "JavaScript"

    /**
     * Cached result of JavaScript plugin availability check.
     *
     * This is computed once at class initialization using a lazy delegate
     * that is thread-safe by default (LazyThreadSafetyMode.SYNCHRONIZED).
     */
    val isJavaScriptPluginAvailable: Boolean by lazy {
        checkJavaScriptPluginAvailable()
    }

    /**
     * Performs the actual check for JavaScript plugin availability.
     *
     * We check if the JavaScript plugin is installed and enabled.
     */
    private fun checkJavaScriptPluginAvailable(): Boolean {
        return try {
            val pluginId = PluginId.getId(JAVASCRIPT_PLUGIN_ID)
            val plugin = PluginManagerCore.getPlugin(pluginId)
            val available = plugin != null && plugin.isEnabled

            if (available) {
                LOG.info("JavaScript plugin detected - JavaScript/TypeScript-specific tools will be available")
            } else {
                LOG.info("JavaScript plugin not available - JavaScript/TypeScript-specific features will be disabled")
            }
            available
        } catch (e: Exception) {
            LOG.warn("Failed to check JavaScript plugin availability: ${e.message}")
            false
        }
    }

    /**
     * Executes the given block only if the JavaScript plugin is available.
     *
     * @param block The code block to execute if JavaScript plugin is available
     * @return The result of the block, or null if JavaScript plugin is not available
     */
    inline fun <T> ifJavaScriptAvailable(block: () -> T): T? {
        return if (isJavaScriptPluginAvailable) {
            block()
        } else {
            null
        }
    }

    /**
     * Executes the given block only if the JavaScript plugin is available,
     * otherwise returns the provided default value.
     *
     * @param default The default value to return if JavaScript plugin is not available
     * @param block The code block to execute if JavaScript plugin is available
     * @return The result of the block, or the default value if JavaScript plugin is not available
     */
    inline fun <T> ifJavaScriptAvailableOrElse(default: T, block: () -> T): T {
        return if (isJavaScriptPluginAvailable) {
            block()
        } else {
            default
        }
    }
}
