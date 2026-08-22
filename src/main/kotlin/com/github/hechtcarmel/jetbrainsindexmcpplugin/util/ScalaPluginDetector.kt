package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

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

    /**
     * Cached result of Scala plugin availability check.
     *
     * Delegates to [PluginDetectors.scala], which uses only the public
     * `PluginManagerCore.isLoaded`/`isDisabled` APIs (see `PluginDetector`) —
     * never the `@ApiStatus.Internal` `PluginManagerCore.getPlugin`, which the
     * plugin verifier rejects.
     */
    val isScalaPluginAvailable: Boolean
        get() = PluginDetectors.scala.isAvailable

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
