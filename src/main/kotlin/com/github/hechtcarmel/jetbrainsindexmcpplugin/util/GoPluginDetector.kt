package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

object GoPluginDetector {
    val isGoPluginAvailable: Boolean get() = PluginDetectors.go.isAvailable

    inline fun <T> ifGoAvailable(block: () -> T): T? = PluginDetectors.go.ifAvailable(block)

    inline fun <T> ifGoAvailableOrElse(default: T, block: () -> T): T =
        PluginDetectors.go.ifAvailableOrElse(default, block)
}
