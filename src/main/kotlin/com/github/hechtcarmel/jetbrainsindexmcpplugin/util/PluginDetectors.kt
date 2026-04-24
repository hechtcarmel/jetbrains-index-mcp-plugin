package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

object PluginDetectors {
    val java = PluginDetector(
        name = "Java",
        pluginIds = listOf("com.intellij.java", "com.intellij.modules.java")
    )

    val python = PluginDetector(
        name = "Python",
        pluginIds = listOf("Pythonid", "PythonCore")
    )

    val javaScript = PluginDetector(
        name = "JavaScript",
        pluginIds = listOf("JavaScript")
    )

    val go = PluginDetector(
        name = "Go",
        pluginIds = listOf("org.jetbrains.plugins.go")
    )

    val php = PluginDetector(
        name = "PHP",
        pluginIds = listOf("com.jetbrains.php")
    )

    val rust = PluginDetector(
        name = "Rust",
        pluginIds = listOf("com.jetbrains.rust"),
        fallbackClass = "org.rust.lang.core.psi.RsFile"
    )

    val markdown = PluginDetector(
        name = "Markdown",
        pluginIds = listOf("org.intellij.plugins.markdown"),
        fallbackClass = "org.intellij.plugins.markdown.lang.MarkdownLanguage"
    )

    val kotlin = PluginDetector(
        name = "Kotlin",
        pluginIds = listOf("org.jetbrains.kotlin")
    )
}
