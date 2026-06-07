package com.github.hechtcarmel.jetbrainsindexmcpplugin.server

import com.github.hechtcarmel.jetbrainsindexmcpplugin.McpConstants
import com.github.hechtcarmel.jetbrainsindexmcpplugin.settings.McpSettings

object McpProtocolVersions {
    val streamableHttpSupportedVersions = listOf(
        McpConstants.STREAMABLE_HTTP_MCP_PROTOCOL_VERSION,
        McpConstants.MCP_PROTOCOL_VERSION_2025_06_18,
        McpConstants.MCP_PROTOCOL_VERSION_2025_11_25
    )

    val allSupportedVersions = setOf(McpConstants.LEGACY_MCP_PROTOCOL_VERSION) + streamableHttpSupportedVersions

    fun negotiateStreamableHttpVersion(
        requestedVersion: String?,
        mode: McpSettings.ProtocolVersionMode
    ): String {
        forcedVersion(mode)?.let { return it }
        return requestedVersion
            ?.takeIf { it in streamableHttpSupportedVersions }
            ?: McpConstants.LATEST_STREAMABLE_HTTP_MCP_PROTOCOL_VERSION
    }

    fun effectiveStreamableHttpVersion(
        headerVersion: String?,
        mode: McpSettings.ProtocolVersionMode
    ): String? {
        forcedVersion(mode)?.let { return it }
        if (headerVersion.isNullOrBlank()) {
            return McpConstants.STREAMABLE_HTTP_MCP_PROTOCOL_VERSION
        }
        return headerVersion.takeIf { it in streamableHttpSupportedVersions }
    }

    fun supportsStructuredOutput(protocolVersion: String): Boolean =
        protocolVersion == McpConstants.MCP_PROTOCOL_VERSION_2025_06_18 ||
            protocolVersion == McpConstants.MCP_PROTOCOL_VERSION_2025_11_25

    fun currentMode(): McpSettings.ProtocolVersionMode =
        runCatching { McpSettings.getInstance().protocolVersionMode }
            .getOrDefault(McpSettings.ProtocolVersionMode.AUTO)

    private fun forcedVersion(mode: McpSettings.ProtocolVersionMode): String? =
        when (mode) {
            McpSettings.ProtocolVersionMode.AUTO -> null
            McpSettings.ProtocolVersionMode.FORCE_2025_03_26 -> McpConstants.STREAMABLE_HTTP_MCP_PROTOCOL_VERSION
            McpSettings.ProtocolVersionMode.FORCE_2025_06_18 -> McpConstants.MCP_PROTOCOL_VERSION_2025_06_18
            McpSettings.ProtocolVersionMode.FORCE_2025_11_25 -> McpConstants.MCP_PROTOCOL_VERSION_2025_11_25
        }
}
