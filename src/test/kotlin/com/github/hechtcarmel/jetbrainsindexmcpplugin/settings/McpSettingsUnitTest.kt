package com.github.hechtcarmel.jetbrainsindexmcpplugin.settings

import junit.framework.TestCase

class McpSettingsUnitTest : TestCase() {

    // State default values tests

    fun testStateDefaultValues() {
        val state = McpSettings.State()

        assertEquals("Default maxHistorySize should be 100", 100, state.maxHistorySize)
        assertFalse("Default syncExternalChanges should be false", state.syncExternalChanges)
        assertEquals("Default serverHost should be 127.0.0.1", "127.0.0.1", state.serverHost)
    }

    // State mutability tests

    fun testStateMaxHistorySizeMutable() {
        val state = McpSettings.State()
        state.maxHistorySize = 200

        assertEquals(200, state.maxHistorySize)
    }

    fun testStateServerHostMutable() {
        val state = McpSettings.State()
        state.serverHost = "0.0.0.0"

        assertEquals("0.0.0.0", state.serverHost)
    }

    fun testStateSyncExternalChangesMutable() {
        val state = McpSettings.State()
        state.syncExternalChanges = true

        assertTrue(state.syncExternalChanges)
    }

    // State custom constructor tests

    fun testStateCustomConstructor() {
        val state = McpSettings.State(
            maxHistorySize = 500,
            syncExternalChanges = true
        )

        assertEquals(500, state.maxHistorySize)
        assertTrue(state.syncExternalChanges)
    }

    // State copy tests

    fun testStateCopy() {
        val original = McpSettings.State(maxHistorySize = 50)
        val copy = original.copy(maxHistorySize = 150)

        assertEquals(50, original.maxHistorySize)
        assertEquals(150, copy.maxHistorySize)
        assertEquals(original.syncExternalChanges, copy.syncExternalChanges)
    }

    // State equals and hashCode tests

    fun testStateEquals() {
        val state1 = McpSettings.State()
        val state2 = McpSettings.State()

        assertEquals(state1, state2)
    }

    fun testStateNotEqualsWhenDifferent() {
        val state1 = McpSettings.State(maxHistorySize = 100)
        val state2 = McpSettings.State(maxHistorySize = 200)

        assertFalse(state1 == state2)
    }

    fun testStateHashCode() {
        val state1 = McpSettings.State()
        val state2 = McpSettings.State()

        assertEquals(state1.hashCode(), state2.hashCode())
    }

    // McpSettings instance tests

    fun testMcpSettingsInitialization() {
        val settings = McpSettings()

        // Should have default state
        assertNotNull(settings.state)
        assertEquals(100, settings.maxHistorySize)
    }

    fun testMcpSettingsPropertyDelegation() {
        val settings = McpSettings()

        settings.maxHistorySize = 250
        settings.syncExternalChanges = true

        assertEquals(250, settings.maxHistorySize)
        assertTrue(settings.syncExternalChanges)
    }

    fun testMcpSettingsLoadState() {
        val settings = McpSettings()
        val newState = McpSettings.State(
            maxHistorySize = 75,
            syncExternalChanges = true
        )

        settings.loadState(newState)

        assertEquals(75, settings.maxHistorySize)
        assertTrue(settings.syncExternalChanges)
    }

    fun testMcpSettingsGetStateReturnsCurrentState() {
        val settings = McpSettings()
        settings.maxHistorySize = 300

        val state = settings.state

        assertEquals(300, state.maxHistorySize)
    }

    // Edge case tests

    fun testMaxHistorySizeZero() {
        val state = McpSettings.State(maxHistorySize = 0)
        assertEquals(0, state.maxHistorySize)
    }

    fun testMaxHistorySizeNegative() {
        val state = McpSettings.State(maxHistorySize = -1)
        assertEquals(-1, state.maxHistorySize)
    }

    fun testHostValidationLogic() {
        assertTrue("127.0.0.1 should be valid", McpSettingsConfigurable.isValidHost("127.0.0.1"))
        assertTrue("0.0.0.0 should be valid", McpSettingsConfigurable.isValidHost("0.0.0.0"))
        assertTrue("localhost should be valid", McpSettingsConfigurable.isValidHost("localhost"))
        assertTrue("  127.0.0.1  should be valid (trimmed)", McpSettingsConfigurable.isValidHost("  127.0.0.1  "))

        // Validate numeric IPs with octet range
        assertTrue("255.255.255.255 should be valid", 
            McpSettingsConfigurable.isValidHost("255.255.255.255"))
        assertFalse("999.999.999.999 should be invalid (octets out of range)", 
            McpSettingsConfigurable.isValidHost("999.999.999.999"))
        assertFalse("256.0.0.1 should be invalid (octet out of range)", 
            McpSettingsConfigurable.isValidHost("256.0.0.1"))
        
        assertFalse("Numeric IP with 2 parts should be invalid", McpSettingsConfigurable.isValidHost("127.1"))
        assertFalse("Numeric IP with 3 parts should be invalid", McpSettingsConfigurable.isValidHost("192.168.1"))
        assertFalse("Numeric IP with 5 parts should be invalid", McpSettingsConfigurable.isValidHost("1.2.3.4.5"))
        assertFalse("Numeric IP with empty parts should be invalid", McpSettingsConfigurable.isValidHost("1..1.1"))

        assertFalse("Empty string should be invalid", McpSettingsConfigurable.isValidHost(""))
        assertFalse("Blank string should be invalid", McpSettingsConfigurable.isValidHost("   "))
        // Use a host that definitely shouldn't resolve and has invalid chars for IP
        assertFalse("Invalid hostname should be invalid", McpSettingsConfigurable.isValidHost("invalid_host_name_!@#"))
    }
}
