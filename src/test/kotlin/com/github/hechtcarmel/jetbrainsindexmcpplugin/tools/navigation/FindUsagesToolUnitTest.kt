package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.PaginationService
import junit.framework.TestCase

class FindUsagesToolUnitTest : TestCase() {

    fun testSearchInfrastructureFailureMessageIncludesFallbackGuidance() {
        val error = NoSuchMethodError("WorkspaceFileIndexEx.getFileInfo")

        val message = FindUsagesTool.searchInfrastructureErrorMessage(error)

        assertTrue("Should mention search infrastructure failure", message.contains("Reference search failed due to IDE/plugin API incompatibility"))
        assertTrue("Should include original error type", message.contains("NoSuchMethodError"))
        assertTrue("Should suggest ide_search_text fallback", message.contains("ide_search_text"))
    }

    fun testTotalIsExactWhenInitialSearchWasExhausted() {
        // The fresh search enumerated everything: exact even while more pages remain in the cache.
        assertTrue(
            FindUsagesTool.computeTotalIsExact(
                metadata = mapOf("searchExhausted" to "true"),
                hasMore = true,
                totalCollected = 300
            )
        )
    }

    fun testTotalIsExactWhenExtenderExhaustedBelowCap() {
        // Initial search hit the collection cap, but a later extender probe found nothing new.
        assertTrue(
            FindUsagesTool.computeTotalIsExact(
                metadata = mapOf("searchExhausted" to "false"),
                hasMore = false,
                totalCollected = 1200
            )
        )
    }

    fun testTotalNotExactWhileMorePagesAndSearchNotExhausted() {
        assertFalse(
            FindUsagesTool.computeTotalIsExact(
                metadata = mapOf("searchExhausted" to "false"),
                hasMore = true,
                totalCollected = 500
            )
        )
        // Missing metadata (legacy cursor) must not claim exactness while pages remain.
        assertFalse(
            FindUsagesTool.computeTotalIsExact(
                metadata = emptyMap(),
                hasMore = true,
                totalCollected = 500
            )
        )
    }

    fun testTotalNotExactAtHardCacheCap() {
        // At MAX_CACHED_RESULTS_PER_CURSOR extension is skipped and hasMore=false is forced,
        // so exactness cannot be inferred from hasMore there.
        assertFalse(
            FindUsagesTool.computeTotalIsExact(
                metadata = mapOf("searchExhausted" to "false"),
                hasMore = false,
                totalCollected = PaginationService.MAX_CACHED_RESULTS_PER_CURSOR
            )
        )
    }
}
