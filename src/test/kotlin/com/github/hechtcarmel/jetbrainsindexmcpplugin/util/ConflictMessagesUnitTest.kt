package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

import junit.framework.TestCase

class ConflictMessagesUnitTest : TestCase() {

    fun testStripsIdeDialogMarkupAndDecodesEntities() {
        val raw = "Class <b><code>Foo</code></b> already exists in package &lt;default&gt;"

        assertEquals(
            "Class Foo already exists in package <default>",
            ConflictMessages.sanitize(raw)
        )
    }

    fun testPlainMessagePassesThroughUnchanged() {
        assertEquals("nothing to strip", ConflictMessages.sanitize("nothing to strip"))
    }

    fun testSanitizeAllDropsEmptyAndDuplicateMessages() {
        val messages = listOf(
            "<b>same</b>",
            "same",
            "<i></i>",
            "other"
        )

        assertEquals(listOf("same", "other"), ConflictMessages.sanitizeAll(messages))
    }
}
