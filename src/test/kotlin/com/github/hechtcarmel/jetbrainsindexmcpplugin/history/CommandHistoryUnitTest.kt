package com.github.hechtcarmel.jetbrainsindexmcpplugin.history

import junit.framework.TestCase
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class CommandHistoryUnitTest : TestCase() {

    fun testCommandEntryTimestamp() {
        val beforeCreate = java.time.Instant.now()
        val entry = CommandEntry(
            toolName = "test",
            parameters = buildJsonObject { }
        )
        val afterCreate = java.time.Instant.now()

        assertTrue("Timestamp should be after test start", !entry.timestamp.isBefore(beforeCreate))
        assertTrue("Timestamp should be before test end", !entry.timestamp.isAfter(afterCreate))
    }

    fun testCommandEntryId() {
        val entry1 = CommandEntry(toolName = "test1", parameters = buildJsonObject { })
        val entry2 = CommandEntry(toolName = "test2", parameters = buildJsonObject { })

        assertFalse("Each entry should have unique ID", entry1.id == entry2.id)
    }

    fun testCommandEntryDefaultStatus() {
        val entry = CommandEntry(
            toolName = "test_tool",
            parameters = buildJsonObject {
                put("param1", "value1")
            }
        )

        assertEquals("test_tool", entry.toolName)
        assertEquals(CommandStatus.PENDING, entry.status)
        assertNull(entry.result)
        assertNull(entry.durationMs)
    }

    fun testCommandFilterByToolName() {
        val filter = CommandFilter(toolName = "find_usages")

        assertEquals("find_usages", filter.toolName)
        assertNull(filter.status)
    }

    fun testCommandFilterByStatus() {
        val filter = CommandFilter(status = CommandStatus.ERROR)

        assertNull(filter.toolName)
        assertEquals(CommandStatus.ERROR, filter.status)
    }

    fun testCommandFilterCombined() {
        val filter = CommandFilter(toolName = "find_definition", status = CommandStatus.SUCCESS)

        assertEquals("find_definition", filter.toolName)
        assertEquals(CommandStatus.SUCCESS, filter.status)
    }

    fun testCommandStatusValues() {
        val statuses = CommandStatus.values()

        assertTrue("Should have PENDING status", statuses.contains(CommandStatus.PENDING))
        assertTrue("Should have SUCCESS status", statuses.contains(CommandStatus.SUCCESS))
        assertTrue("Should have ERROR status", statuses.contains(CommandStatus.ERROR))
    }
}
