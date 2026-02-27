package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

object JavaScriptPluginDetector {
    val isJavaScriptPluginAvailable: Boolean get() = PluginDetectors.javaScript.isAvailable

    inline fun <T> ifJavaScriptAvailable(block: () -> T): T? = PluginDetectors.javaScript.ifAvailable(block)

    inline fun <T> ifJavaScriptAvailableOrElse(default: T, block: () -> T): T =
        PluginDetectors.javaScript.ifAvailableOrElse(default, block)
}
