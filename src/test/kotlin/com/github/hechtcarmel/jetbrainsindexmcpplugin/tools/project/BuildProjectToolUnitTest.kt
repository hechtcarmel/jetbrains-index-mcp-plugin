package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project

import junit.framework.TestCase

class BuildProjectToolUnitTest : TestCase() {

    // ── in-progress result composition ────────────────────────────────────────

    /**
     * The in-progress payload is what an agent sees instead of a client-side timeout on long
     * builds (same client-timeout root cause as issue #277). It must carry the buildId both as a
     * field and inside an actionable poll instruction.
     */
    fun testInProgressResultTellsAgentHowToPoll() {
        val result = BuildProjectTool.buildInProgressResult(
            buildId = "bld-42",
            elapsedSeconds = 61,
            timeoutSeconds = 600
        )
        assertEquals("running", result.status)
        assertEquals("bld-42", result.buildId)
        assertEquals(61L, result.elapsedSeconds)
        assertEquals(600, result.timeoutSeconds)
        assertTrue("message must repeat the buildId for the poll call", result.message.contains("bld-42"))
        assertTrue("message must name the buildId parameter", result.message.contains("buildId"))
    }

    /**
     * ide_build_project historically allows omitting timeoutSeconds entirely (build waits
     * unbounded); the in-progress payload must survive that as a null limit.
     */
    fun testInProgressResultSupportsUnboundedBuilds() {
        val result = BuildProjectTool.buildInProgressResult(
            buildId = "bld-43",
            elapsedSeconds = 10,
            timeoutSeconds = null
        )
        assertNull(result.timeoutSeconds)
        assertTrue(result.message.contains("bld-43"))
    }
}
