package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation

import junit.framework.TestCase
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

class FindUsagesToolUnitTest : TestCase() {

    fun testSearchInfrastructureFailureMessageIncludesFallbackGuidance() {
        val error = NoSuchMethodError("WorkspaceFileIndexEx.getFileInfo")

        val message = FindUsagesTool.searchInfrastructureErrorMessage(error)

        assertTrue("Should mention search infrastructure failure", message.contains("Reference search failed due to IDE/plugin API incompatibility"))
        assertTrue("Should include original error type", message.contains("NoSuchMethodError"))
        assertTrue("Should suggest ide_search_text fallback", message.contains("ide_search_text"))
    }

    fun testBlankCursorShouldUseFreshSearchPath() {
        val arguments = buildJsonObject { put("cursor", JsonPrimitive("")) }

        val isCursorPath = usesCursorPaginationPath(arguments)

        assertFalse("Blank cursor should be treated as fresh search", isCursorPath)
    }

    fun testWhitespaceCursorShouldUseFreshSearchPath() {
        val arguments = buildJsonObject { put("cursor", JsonPrimitive("   \t  ")) }

        val isCursorPath = usesCursorPaginationPath(arguments)

        assertFalse("Whitespace cursor should be treated as fresh search", isCursorPath)
    }

    fun testMalformedNonEmptyCursorShouldUseCursorPathAndFailValidationLater() {
        val arguments = buildJsonObject { put("cursor", JsonPrimitive("not-a-valid-cursor")) }

        val isCursorPath = usesCursorPaginationPath(arguments)

        assertTrue("Non-empty malformed cursor must stay on cursor path for invalid-cursor handling", isCursorPath)
    }

    /**
     * Mirrors FindUsagesTool cursor-routing gate after normalization:
     * `val cursor = optionalStringArg(arguments, ParamNames.CURSOR); if (cursor != null) ...`
     *
     * Blank/whitespace cursors are normalized to null and use fresh-search path.
     * Non-empty malformed cursors stay non-null and use cursor path for validation.
     */
    private fun usesCursorPaginationPath(arguments: JsonObject): Boolean {
        val cursor = arguments["cursor"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        return cursor != null
    }
}
