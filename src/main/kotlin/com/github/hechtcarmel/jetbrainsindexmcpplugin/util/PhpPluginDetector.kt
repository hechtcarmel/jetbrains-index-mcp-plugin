package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

object PhpPluginDetector {
    val isPhpPluginAvailable: Boolean get() = PluginDetectors.php.isAvailable

    inline fun <T> ifPhpAvailable(block: () -> T): T? = PluginDetectors.php.ifAvailable(block)

    inline fun <T> ifPhpAvailableOrElse(default: T, block: () -> T): T =
        PluginDetectors.php.ifAvailableOrElse(default, block)
}
