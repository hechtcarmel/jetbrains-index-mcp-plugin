package com.github.hechtcarmel.jetbrainsindexmcpplugin.server

import com.github.hechtcarmel.jetbrainsindexmcpplugin.McpConstants
import com.github.hechtcarmel.jetbrainsindexmcpplugin.settings.McpSettings
import junit.framework.TestCase

class McpProtocolVersionsUnitTest : TestCase() {

    fun testAutoNegotiatesRequestedSupportedVersion() {
        assertEquals(
            McpConstants.MCP_PROTOCOL_VERSION_2025_06_18,
            McpProtocolVersions.negotiateStreamableHttpVersion(
                requestedVersion = McpConstants.MCP_PROTOCOL_VERSION_2025_06_18,
                mode = McpSettings.ProtocolVersionMode.AUTO
            )
        )
    }

    fun testAutoFallsBackToLatestForUnsupportedRequestedVersion() {
        assertEquals(
            McpConstants.LATEST_STREAMABLE_HTTP_MCP_PROTOCOL_VERSION,
            McpProtocolVersions.negotiateStreamableHttpVersion(
                requestedVersion = "2099-01-01",
                mode = McpSettings.ProtocolVersionMode.AUTO
            )
        )
    }

    fun testAutoFallsBackToLatestWhenRequestedVersionIsMissing() {
        assertEquals(
            McpConstants.LATEST_STREAMABLE_HTTP_MCP_PROTOCOL_VERSION,
            McpProtocolVersions.negotiateStreamableHttpVersion(
                requestedVersion = null,
                mode = McpSettings.ProtocolVersionMode.AUTO
            )
        )
    }

    fun testForced20250326OverridesRequestedVersion() {
        assertEquals(
            McpConstants.STREAMABLE_HTTP_MCP_PROTOCOL_VERSION,
            McpProtocolVersions.negotiateStreamableHttpVersion(
                requestedVersion = McpConstants.MCP_PROTOCOL_VERSION_2025_11_25,
                mode = McpSettings.ProtocolVersionMode.FORCE_2025_03_26
            )
        )
    }

    fun testForced20250618OverridesRequestedVersion() {
        assertEquals(
            McpConstants.MCP_PROTOCOL_VERSION_2025_06_18,
            McpProtocolVersions.negotiateStreamableHttpVersion(
                requestedVersion = McpConstants.MCP_PROTOCOL_VERSION_2025_11_25,
                mode = McpSettings.ProtocolVersionMode.FORCE_2025_06_18
            )
        )
    }

    fun testForced20251125OverridesHeaderVersion() {
        assertEquals(
            McpConstants.MCP_PROTOCOL_VERSION_2025_11_25,
            McpProtocolVersions.effectiveStreamableHttpVersion(
                headerVersion = McpConstants.MCP_PROTOCOL_VERSION_2025_06_18,
                mode = McpSettings.ProtocolVersionMode.FORCE_2025_11_25
            )
        )
    }

    fun testMissingHeaderDefaultsToInitialStreamableVersionInAutoMode() {
        assertEquals(
            McpConstants.STREAMABLE_HTTP_MCP_PROTOCOL_VERSION,
            McpProtocolVersions.effectiveStreamableHttpVersion(
                headerVersion = null,
                mode = McpSettings.ProtocolVersionMode.AUTO
            )
        )
    }

    fun testBlankHeaderDefaultsToInitialStreamableVersionInAutoMode() {
        assertEquals(
            McpConstants.STREAMABLE_HTTP_MCP_PROTOCOL_VERSION,
            McpProtocolVersions.effectiveStreamableHttpVersion(
                headerVersion = "   ",
                mode = McpSettings.ProtocolVersionMode.AUTO
            )
        )
    }

    fun testSupportedHeaderVersionIsEffectiveInAutoMode() {
        assertEquals(
            McpConstants.MCP_PROTOCOL_VERSION_2025_11_25,
            McpProtocolVersions.effectiveStreamableHttpVersion(
                headerVersion = McpConstants.MCP_PROTOCOL_VERSION_2025_11_25,
                mode = McpSettings.ProtocolVersionMode.AUTO
            )
        )
    }

    fun testUnsupportedHeaderVersionReturnsNullInAutoMode() {
        assertNull(
            McpProtocolVersions.effectiveStreamableHttpVersion(
                headerVersion = "2099-01-01",
                mode = McpSettings.ProtocolVersionMode.AUTO
            )
        )
    }

    fun testForcedHeaderVersionOverridesUnsupportedHeaderVersion() {
        assertEquals(
            McpConstants.MCP_PROTOCOL_VERSION_2025_06_18,
            McpProtocolVersions.effectiveStreamableHttpVersion(
                headerVersion = "2099-01-01",
                mode = McpSettings.ProtocolVersionMode.FORCE_2025_06_18
            )
        )
    }
}
