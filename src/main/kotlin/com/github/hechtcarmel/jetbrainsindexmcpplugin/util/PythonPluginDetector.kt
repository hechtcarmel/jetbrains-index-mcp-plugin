package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

object PythonPluginDetector {
    val isPythonPluginAvailable: Boolean get() = PluginDetectors.python.isAvailable

    inline fun <T> ifPythonAvailable(block: () -> T): T? = PluginDetectors.python.ifAvailable(block)

    inline fun <T> ifPythonAvailableOrElse(default: T, block: () -> T): T =
        PluginDetectors.python.ifAvailableOrElse(default, block)
}
