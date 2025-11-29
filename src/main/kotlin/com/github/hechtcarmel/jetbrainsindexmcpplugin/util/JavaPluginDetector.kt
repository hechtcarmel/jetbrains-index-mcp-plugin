package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.extensions.PluginId

/**
 * Utility for detecting Java plugin availability.
 *
 * This class caches the Java plugin availability check to avoid repeated class loading attempts.
 * The check is performed once at class initialization and the result is cached for the lifetime
 * of the JVM.
 *
 * ## Why This Matters
 *
 * Many tools in this plugin use Java-specific PSI APIs (PsiClass, PsiMethod, JavaPsiFacade, etc.)
 * that are only available when the Java plugin is installed. In non-Java IDEs like WebStorm or
 * PyCharm (without Java support), these classes don't exist and would cause NoClassDefFoundError.
 *
 * ## Usage
 *
 * ```kotlin
 * if (JavaPluginDetector.isJavaPluginAvailable) {
 *     // Safe to use Java-specific APIs
 *     val clazz = JavaPsiFacade.getInstance(project).findClass(...)
 * }
 * ```
 *
 * ## IDE Compatibility
 *
 * | IDE | Java Plugin Available |
 * |-----|----------------------|
 * | IntelliJ IDEA Community | Yes |
 * | IntelliJ IDEA Ultimate | Yes |
 * | Android Studio | Yes |
 * | PyCharm Professional (with Java) | Yes |
 * | PyCharm Community | No |
 * | WebStorm | No |
 * | GoLand | No |
 * | CLion | No |
 * | Rider | No |
 * | DataGrip | No |
 */
object JavaPluginDetector {

    private val LOG = logger<JavaPluginDetector>()

    /**
     * Cached result of Java plugin availability check.
     *
     * This is computed once at class initialization using a lazy delegate that is thread-safe
     * by default (LazyThreadSafetyMode.SYNCHRONIZED).
     */
    val isJavaPluginAvailable: Boolean by lazy {
        checkJavaPluginAvailable()
    }

    /**
     * Performs the actual check for Java plugin availability.
     *
     * We check if the Java plugin (com.intellij.java) is installed and enabled
     * using the PluginManager API. This is more reliable than class existence checks
     * since some Java PSI classes may exist in non-Java IDEs but without full functionality.
     */
    private fun checkJavaPluginAvailable(): Boolean {
        return try {
            val pluginId = PluginId.getId("com.intellij.java")
            val plugin = PluginManagerCore.getPlugin(pluginId)
            val available = plugin != null && plugin.isEnabled
            if (available) {
                LOG.info("Java plugin detected - Java-specific tools will be available")
            } else {
                LOG.info("Java plugin not available - Java-specific tools will be disabled")
            }
            available
        } catch (e: Exception) {
            LOG.warn("Failed to check Java plugin availability: ${e.message}")
            false
        }
    }

    /**
     * Executes the given block only if the Java plugin is available.
     *
     * @param block The code block to execute if Java plugin is available
     * @return The result of the block, or null if Java plugin is not available
     */
    inline fun <T> ifJavaAvailable(block: () -> T): T? {
        return if (isJavaPluginAvailable) {
            block()
        } else {
            null
        }
    }

    /**
     * Executes the given block only if the Java plugin is available,
     * otherwise returns the provided default value.
     *
     * @param default The default value to return if Java plugin is not available
     * @param block The code block to execute if Java plugin is available
     * @return The result of the block, or the default value if Java plugin is not available
     */
    inline fun <T> ifJavaAvailableOrElse(default: T, block: () -> T): T {
        return if (isJavaPluginAvailable) {
            block()
        } else {
            default
        }
    }
}
