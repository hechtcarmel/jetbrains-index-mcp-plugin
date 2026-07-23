package com.github.hechtcarmel.jetbrainsindexmcpplugin.settings

import junit.framework.TestCase

class HeadlessModeManagerUnitTest : TestCase() {

    fun testSnapshotKeysAreDefined() {
        val keys = HeadlessModeManager.SNAPSHOT_KEYS
        assertTrue("Should have snapshot keys", keys.isNotEmpty())
        assertTrue("Should include syncOnFrameActivation", "syncOnFrameActivation" in keys)
        assertTrue("Should include backgroundSync", "backgroundSync" in keys)
        assertTrue("Should include showTipsOnStartup", "showTipsOnStartup" in keys)
        assertTrue("Should include confirmExit", "confirmExit" in keys)
        assertTrue("Should include checkNeeded", "checkNeeded" in keys)
    }

    fun testSnapshotRoundTripViaSettings() {
        val settings = McpSettings()
        val snapshot = mutableMapOf(
            "syncOnFrameActivation" to "true",
            "backgroundSync" to "false",
            "showTipsOnStartup" to "true",
            "confirmExit" to "true",
            "checkNeeded" to "true"
        )
        settings.state.headlessPreToggleSnapshot = snapshot

        assertEquals("true", settings.state.headlessPreToggleSnapshot["syncOnFrameActivation"])
        assertEquals("false", settings.state.headlessPreToggleSnapshot["backgroundSync"])
    }
}
