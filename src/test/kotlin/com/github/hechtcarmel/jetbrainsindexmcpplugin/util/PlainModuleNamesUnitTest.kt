package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

import junit.framework.TestCase

class PlainModuleNamesUnitTest : TestCase() {
    fun testBasenameParentAndCounterHandleSecondaryCollisions() {
        val used = mutableSetOf("packages", "common-packages")
        assertEquals("common-packages-2", PlainModuleNames.unique("packages", "common", used::contains))
        assertEquals("frontend", PlainModuleNames.unique("frontend", "common", used::contains))
    }

    fun testNamesCannotEscapeConfigurationDirectoryOrUseReservedWindowsFiles() {
        assertEquals("module", PlainModuleNames.unique("..", "", { false }))
        assertEquals("module-CON", PlainModuleNames.unique("CON", "", { false }))
        assertEquals("module-aux.txt", PlainModuleNames.unique("aux.txt", "", { false }))
        assertEquals("a_b_c_", PlainModuleNames.unique("a/b:c?", "", { false }))
        assertEquals("frontend", PlainModuleNames.unique("frontend. ", "", { false }))
    }
}
