package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

import junit.framework.TestCase

class PluginDetectorsUnitTest : TestCase() {

    fun testPluginDetectorBasicProperties() {
        val detector = PluginDetector("Test", listOf("com.test.plugin"))
        assertEquals("Test", detector.name)
    }

    fun testPluginDetectorWithFallbackClass() {
        val detector = PluginDetector("Test", listOf("com.test.plugin"), fallbackClass = "com.nonexistent.Class")
        assertFalse(detector.isAvailable)
    }

    fun testIfAvailableReturnsNullWhenUnavailable() {
        val detector = PluginDetector("Test", listOf("com.nonexistent.plugin"))
        val result = detector.ifAvailable { "found" }
        assertNull(result)
    }

    fun testIfAvailableOrElseReturnsFallbackWhenUnavailable() {
        val detector = PluginDetector("Test", listOf("com.nonexistent.plugin"))
        val result = detector.ifAvailableOrElse("default") { "found" }
        assertEquals("default", result)
    }

    fun testPluginDetectorsRegistryHasAllLanguages() {
        assertNotNull(PluginDetectors.java)
        assertNotNull(PluginDetectors.python)
        assertNotNull(PluginDetectors.javaScript)
        assertNotNull(PluginDetectors.go)
        assertNotNull(PluginDetectors.php)
        assertNotNull(PluginDetectors.rust)
        assertNotNull(PluginDetectors.markdown)
    }

    fun testPluginDetectorsHaveCorrectNames() {
        assertEquals("Java", PluginDetectors.java.name)
        assertEquals("Python", PluginDetectors.python.name)
        assertEquals("JavaScript", PluginDetectors.javaScript.name)
        assertEquals("Go", PluginDetectors.go.name)
        assertEquals("PHP", PluginDetectors.php.name)
        assertEquals("Rust", PluginDetectors.rust.name)
        assertEquals("Markdown", PluginDetectors.markdown.name)
    }
}
