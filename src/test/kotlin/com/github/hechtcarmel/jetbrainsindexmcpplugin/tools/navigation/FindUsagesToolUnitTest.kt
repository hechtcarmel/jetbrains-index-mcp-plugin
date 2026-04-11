package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation

import junit.framework.TestCase

class FindUsagesToolUnitTest : TestCase() {

    fun testSearchInfrastructureFailureMessageIncludesFallbackGuidance() {
        val error = NoSuchMethodError("WorkspaceFileIndexEx.getFileInfo")

        val message = FindUsagesTool.searchInfrastructureErrorMessage(error)

        assertTrue("Should mention search infrastructure failure", message.contains("Reference search failed due to IDE/plugin API incompatibility"))
        assertTrue("Should include original error type", message.contains("NoSuchMethodError"))
        assertTrue("Should suggest ide_search_text fallback", message.contains("ide_search_text"))
    }
}
