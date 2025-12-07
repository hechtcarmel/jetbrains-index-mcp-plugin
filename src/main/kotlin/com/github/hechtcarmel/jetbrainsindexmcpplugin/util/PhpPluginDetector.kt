package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.extensions.PluginId

/**
 * Utility for detecting PHP plugin availability.
 *
 * This class caches the PHP plugin availability check to avoid repeated checks.
 * The check is performed once at class initialization and the result is cached
 * for the lifetime of the JVM.
 *
 * ## Why This Matters
 *
 * Many tools in this plugin can use PHP-specific PSI APIs (PhpClass, Method, etc.)
 * that are only available when the PHP plugin is installed. In non-PHP IDEs,
 * these classes don't exist and would cause NoClassDefFoundError.
 *
 * ## Usage
 *
 * ```kotlin
 * if (PhpPluginDetector.isPhpPluginAvailable) {
 *     // Safe to use PHP-specific APIs
 *     val phpClass = PhpIndex.getInstance(project).getClassesByFQN(...)
 * }
 * ```
 *
 * ## IDE Compatibility
 *
 * | IDE | PHP Plugin Available |
 * |-----|---------------------|
 * | PhpStorm | Yes |
 * | IntelliJ IDEA Ultimate (with PHP plugin) | Yes |
 * | IntelliJ IDEA Community | No |
 * | WebStorm | No |
 * | PyCharm | No |
 * | GoLand | No |
 * | CLion | No |
 * | RubyMine | No |
 *
 * ## Plugin ID
 *
 * - `com.jetbrains.php` - PhpStorm / IntelliJ Ultimate PHP plugin
 */
object PhpPluginDetector {

    private val LOG = logger<PhpPluginDetector>()

    /**
     * Plugin ID for PHP support (PhpStorm, IntelliJ Ultimate).
     */
    private const val PHP_PLUGIN_ID = "com.jetbrains.php"

    /**
     * Cached result of PHP plugin availability check.
     *
     * This is computed once at class initialization using a lazy delegate
     * that is thread-safe by default (LazyThreadSafetyMode.SYNCHRONIZED).
     */
    val isPhpPluginAvailable: Boolean by lazy {
        checkPhpPluginAvailable()
    }

    /**
     * Performs the actual check for PHP plugin availability.
     */
    private fun checkPhpPluginAvailable(): Boolean {
        return try {
            val pluginId = PluginId.getId(PHP_PLUGIN_ID)
            val plugin = PluginManagerCore.getPlugin(pluginId)
            val available = plugin != null && plugin.isEnabled

            if (available) {
                LOG.info("PHP plugin detected - PHP-specific tools will be available")
            } else {
                LOG.info("PHP plugin not available - PHP-specific features will be disabled")
            }
            available
        } catch (e: Exception) {
            LOG.warn("Failed to check PHP plugin availability: ${e.message}")
            false
        }
    }

    /**
     * Executes the given block only if the PHP plugin is available.
     *
     * @param block The code block to execute if PHP plugin is available
     * @return The result of the block, or null if PHP plugin is not available
     */
    inline fun <T> ifPhpAvailable(block: () -> T): T? {
        return if (isPhpPluginAvailable) {
            block()
        } else {
            null
        }
    }

    /**
     * Executes the given block only if the PHP plugin is available,
     * otherwise returns the provided default value.
     *
     * @param default The default value to return if PHP plugin is not available
     * @param block The code block to execute if PHP plugin is available
     * @return The result of the block, or the default value if PHP plugin is not available
     */
    inline fun <T> ifPhpAvailableOrElse(default: T, block: () -> T): T {
        return if (isPhpPluginAvailable) {
            block()
        } else {
            default
        }
    }
}
