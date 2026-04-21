package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

import com.github.hechtcarmel.jetbrainsindexmcpplugin.settings.McpSettings
import dev.toonformat.jtoon.JToon

object ResponseFormatter {

    fun formatStructuredPayload(jsonText: String, format: McpSettings.ResponseFormat): String {
        return when (format) {
            McpSettings.ResponseFormat.JSON -> jsonText
            McpSettings.ResponseFormat.TOON -> JToon.encodeJson(jsonText)
        }
    }
}
