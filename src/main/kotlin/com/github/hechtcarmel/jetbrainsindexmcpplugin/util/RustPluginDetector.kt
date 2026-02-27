package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

object RustPluginDetector {
    val isRustPluginAvailable: Boolean get() = PluginDetectors.rust.isAvailable

    inline fun <T> ifRustAvailable(block: () -> T): T? = PluginDetectors.rust.ifAvailable(block)

    inline fun <T> ifRustAvailableOrElse(default: T, block: () -> T): T =
        PluginDetectors.rust.ifAvailableOrElse(default, block)
}
