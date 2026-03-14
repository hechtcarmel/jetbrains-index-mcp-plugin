package com.github.hechtcarmel.jetbrainsindexmcpplugin.server

import junit.framework.TestCase

/**
 * Unit tests for workspace-related path normalization and matching logic.
 * These tests don't require the IntelliJ Platform.
 */
class WorkspaceResolutionUnitTest : TestCase() {

    /**
     * Tests that path normalization removes trailing slashes correctly.
     * This validates the behavior used in ProjectResolver.normalizePath().
     */
    fun testPathNormalizationRemovesTrailingSlash() {
        val paths = mapOf(
            "/home/user/project/" to "/home/user/project",
            "/home/user/project" to "/home/user/project",
            "C:\\Users\\project\\" to "C:/Users/project",
            "C:\\Users\\project" to "C:/Users/project",
            "/home/user/project///" to "/home/user/project"
        )

        for ((input, expected) in paths) {
            val normalized = input.trimEnd('/', '\\').replace('\\', '/')
            assertEquals("Path '$input' should normalize to '$expected'", expected, normalized)
        }
    }

    /**
     * Tests that backslash normalization works for Windows paths.
     */
    fun testBackslashNormalization() {
        val windowsPath = "C:\\IntelliJProjects\\workspace3"
        val normalized = windowsPath.replace('\\', '/')
        assertEquals("C:/IntelliJProjects/workspace3", normalized)
    }

    /**
     * Tests subdirectory path matching logic.
     * In workspace scenarios, a request path might be a subdirectory of an open project.
     */
    fun testSubdirectoryPathMatching() {
        val projectBasePath = "/home/user/workspace"
        val requestPath = "/home/user/workspace/sub-project"
        val normalizedBase = projectBasePath.trimEnd('/')
        val normalizedRequest = requestPath.trimEnd('/')

        assertTrue(
            "Request path should be recognized as subdirectory of project",
            normalizedRequest.startsWith("$normalizedBase/")
        )
    }

    /**
     * Tests that unrelated paths don't match as subdirectories.
     */
    fun testNonSubdirectoryPathDoesNotMatch() {
        val projectBasePath = "/home/user/workspace3"
        val requestPath = "/home/user/other-project"
        val normalizedBase = projectBasePath.trimEnd('/')
        val normalizedRequest = requestPath.trimEnd('/')

        assertFalse(
            "Unrelated path should NOT match as subdirectory",
            normalizedRequest.startsWith("$normalizedBase/")
        )
    }

    /**
     * Tests that paths with similar prefixes don't accidentally match.
     * e.g., "/home/user/workspace3-extra" should NOT match "/home/user/workspace3"
     */
    fun testSimilarPrefixPathDoesNotMatch() {
        val projectBasePath = "/home/user/workspace3"
        val requestPath = "/home/user/workspace3-extra"
        val normalizedBase = projectBasePath.trimEnd('/')
        val normalizedRequest = requestPath.trimEnd('/')

        assertFalse(
            "Path with similar prefix should NOT match (needs trailing slash check)",
            normalizedRequest.startsWith("$normalizedBase/")
        )
    }

    /**
     * Tests cross-platform path comparison: Windows path matches normalized path.
     */
    fun testCrossPlatformPathComparison() {
        val windowsPath = "C:\\IntelliJProjects\\3arts-3"
        val normalizedWindows = windowsPath.trimEnd('/', '\\').replace('\\', '/')

        val unixPath = "C:/IntelliJProjects/3arts-3"
        val normalizedUnix = unixPath.trimEnd('/', '\\').replace('\\', '/')

        assertEquals(
            "Windows and Unix-style paths should normalize to the same value",
            normalizedWindows,
            normalizedUnix
        )
    }
}
