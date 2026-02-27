package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

object JavaPluginDetector {
    val isJavaPluginAvailable: Boolean get() = PluginDetectors.java.isAvailable

    inline fun <T> ifJavaAvailable(block: () -> T): T? = PluginDetectors.java.ifAvailable(block)

    inline fun <T> ifJavaAvailableOrElse(default: T, block: () -> T): T =
        PluginDetectors.java.ifAvailableOrElse(default, block)
}
