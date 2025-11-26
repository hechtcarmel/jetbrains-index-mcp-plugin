package com.github.hechtcarmel.jetbrainsindexmcpplugin

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Platform-dependent tests that require IntelliJ Platform indexing.
 * For pure unit tests that don't need the platform, see McpPluginUnitTest.
 */
class McpPluginTest : BasePlatformTestCase() {

    fun testProjectAvailable() {
        assertNotNull("Project should be available", project)
        assertNotNull("Project base path should be available", project.basePath)
    }
}
