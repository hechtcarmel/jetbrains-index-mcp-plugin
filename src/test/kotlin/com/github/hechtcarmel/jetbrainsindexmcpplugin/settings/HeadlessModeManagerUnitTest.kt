package com.github.hechtcarmel.jetbrainsindexmcpplugin.settings

import junit.framework.TestCase

class HeadlessModeManagerUnitTest : TestCase() {
    fun testSnapshotKeysAreDefined() {
        val keys = HeadlessModeManager.SNAPSHOT_KEYS
        assertTrue("Should have snapshot keys", keys.isNotEmpty())
        assertTrue("syncOnFrameActivation", "syncOnFrameActivation" in keys)
        assertTrue("backgroundSync", "backgroundSync" in keys)
        assertTrue("showTipsOnStartup", "showTipsOnStartup" in keys)
        assertTrue("confirmExit", "confirmExit" in keys)
        assertTrue("checkNeeded", "checkNeeded" in keys)
    }

    fun testSnapshotRoundTripViaSettings() {
        val settings = McpSettings()
        val snapshot = mutableMapOf("syncOnFrameActivation" to "true", "backgroundSync" to "false")
        settings.state.headlessPreToggleSnapshot = snapshot
        assertEquals("true", settings.state.headlessPreToggleSnapshot["syncOnFrameActivation"])
        assertEquals("false", settings.state.headlessPreToggleSnapshot["backgroundSync"])
    }

    fun testVcsSnapshotStorageRoundTrips() {
        val settings = McpSettings()
        val vcsSnapshots = mutableMapOf(
            "/path/to/project" to mutableMapOf("vcsAdd" to "SHOW_CONFIRMATION", "vcsRemove" to "SHOW_CONFIRMATION")
        )
        settings.state.headlessVcsSnapshots = vcsSnapshots
        assertEquals("SHOW_CONFIRMATION", settings.state.headlessVcsSnapshots["/path/to/project"]?.get("vcsAdd"))
    }
}
