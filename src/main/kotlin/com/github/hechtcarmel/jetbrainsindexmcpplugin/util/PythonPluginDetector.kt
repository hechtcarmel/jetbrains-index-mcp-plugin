package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.extensions.PluginId

/**
 * Utility for detecting Python plugin availability.
 *
 * This class caches the Python plugin availability check to avoid repeated checks.
 * The check is performed once at class initialization and the result is cached
 * for the lifetime of the JVM.
 *
 * ## Why This Matters
 *
 * Many tools in this plugin can use Python-specific PSI APIs (PyClass, PyFunction, etc.)
 * that are only available when the Python plugin is installed. In non-Python IDEs,
 * these classes don't exist and would cause NoClassDefFoundError.
 *
 * ## Usage
 *
 * ```kotlin
 * if (PythonPluginDetector.isPythonPluginAvailable) {
 *     // Safe to use Python-specific APIs
 *     val pyClass = PyClassInheritorsSearch.search(...)
 * }
 * ```
 *
 * ## IDE Compatibility
 *
 * | IDE | Python Plugin Available |
 * |-----|------------------------|
 * | PyCharm Professional | Yes |
 * | PyCharm Community | Yes |
 * | IntelliJ IDEA Ultimate (with Python plugin) | Yes |
 * | IntelliJ IDEA Community (with Python CE plugin) | Yes |
 * | WebStorm | No |
 * | GoLand | No |
 * | CLion | No |
 * | Rider | No |
 *
 * ## Plugin IDs
 *
 * - `Pythonid` - PyCharm Professional / IntelliJ Ultimate Python plugin
 * - `PythonCore` - PyCharm Community / IntelliJ Community Python plugin
 */
object PythonPluginDetector {

    private val LOG = logger<PythonPluginDetector>()

    /**
     * Plugin ID for Python Professional (PyCharm Pro, IntelliJ Ultimate).
     */
    private const val PYTHON_PRO_PLUGIN_ID = "Pythonid"

    /**
     * Plugin ID for Python Community (PyCharm Community, IntelliJ Community plugin).
     */
    private const val PYTHON_CE_PLUGIN_ID = "PythonCore"

    /**
     * Cached result of Python plugin availability check.
     *
     * This is computed once at class initialization using a lazy delegate
     * that is thread-safe by default (LazyThreadSafetyMode.SYNCHRONIZED).
     */
    val isPythonPluginAvailable: Boolean by lazy {
        checkPythonPluginAvailable()
    }

    /**
     * Performs the actual check for Python plugin availability.
     *
     * We check if either the Professional or Community Python plugin
     * is installed and enabled.
     */
    private fun checkPythonPluginAvailable(): Boolean {
        return try {
            // Check for Professional Python plugin first
            val proPluginId = PluginId.getId(PYTHON_PRO_PLUGIN_ID)
            val proPlugin = PluginManagerCore.getPlugin(proPluginId)
            if (proPlugin != null && proPlugin.isEnabled) {
                LOG.info("Python Professional plugin detected - Python-specific tools will be available")
                return true
            }

            // Check for Community Python plugin
            val cePluginId = PluginId.getId(PYTHON_CE_PLUGIN_ID)
            val cePlugin = PluginManagerCore.getPlugin(cePluginId)
            if (cePlugin != null && cePlugin.isEnabled) {
                LOG.info("Python Community plugin detected - Python-specific tools will be available")
                return true
            }

            LOG.info("Python plugin not available - Python-specific features will be disabled")
            false
        } catch (e: Exception) {
            LOG.warn("Failed to check Python plugin availability: ${e.message}")
            false
        }
    }

    /**
     * Executes the given block only if the Python plugin is available.
     *
     * @param block The code block to execute if Python plugin is available
     * @return The result of the block, or null if Python plugin is not available
     */
    inline fun <T> ifPythonAvailable(block: () -> T): T? {
        return if (isPythonPluginAvailable) {
            block()
        } else {
            null
        }
    }

    /**
     * Executes the given block only if the Python plugin is available,
     * otherwise returns the provided default value.
     *
     * @param default The default value to return if Python plugin is not available
     * @param block The code block to execute if Python plugin is available
     * @return The result of the block, or the default value if Python plugin is not available
     */
    inline fun <T> ifPythonAvailableOrElse(default: T, block: () -> T): T {
        return if (isPythonPluginAvailable) {
            block()
        } else {
            default
        }
    }
}
