package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

object PluginDetectors {
    val java = PluginDetector(
        name = "Java",
        pluginIds = listOf("com.intellij.java", "com.intellij.modules.java"),
        fallbackClass = "com.intellij.psi.PsiJavaFile"
    )

    val python = PluginDetector(
        name = "Python",
        pluginIds = listOf("Pythonid", "PythonCore"),
        fallbackClass = "com.jetbrains.python.psi.PyClass"
    )

    val javaScript = PluginDetector(
        name = "JavaScript",
        pluginIds = listOf("JavaScript"),
        fallbackClass = "com.intellij.lang.javascript.psi.JSFunction"
    )

    val go = PluginDetector(
        name = "Go",
        pluginIds = listOf("org.jetbrains.plugins.go"),
        fallbackClass = "com.goide.psi.GoFile"
    )

    val php = PluginDetector(
        name = "PHP",
        pluginIds = listOf("com.jetbrains.php"),
        fallbackClass = "com.jetbrains.php.lang.psi.elements.PhpClass"
    )

    val ruby = PluginDetector(
        name = "Ruby",
        pluginIds = listOf("org.jetbrains.plugins.ruby"),
        fallbackClass = "org.jetbrains.plugins.ruby.ruby.lang.psi.controlStructures.classes.RClass"
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
        pluginIds = listOf("org.jetbrains.kotlin"),
        fallbackClass = "org.jetbrains.kotlin.psi.KtFile"
    )

    val maven = PluginDetector(
        name = "Maven",
        pluginIds = listOf("org.jetbrains.idea.maven"),
        fallbackClass = "org.jetbrains.idea.maven.project.MavenProjectsManager"
    )
}
