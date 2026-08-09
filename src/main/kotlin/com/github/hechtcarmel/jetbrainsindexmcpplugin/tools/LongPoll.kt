package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Shared per-call wait-budget policy for long-poll tools (ide_run_tests, ide_build_project,
 * ide_project_diagnostics).
 *
 * MCP clients enforce their own request timeout (60s by default in Claude Code / the MCP
 * TypeScript SDK) and the stateless Streamable HTTP transport cannot emit keep-alive progress
 * notifications (kotlin-sdk 0.10.0 drops them in JSON response mode), so no single tool call may
 * block longer than this budget — long operations return a poll id instead (issue #277).
 */
internal object LongPoll {
    const val DEFAULT_WAIT_SECONDS = 45
    const val MAX_WAIT_SECONDS = 55

    fun resolveWaitSeconds(arguments: JsonObject): Int =
        (arguments[ParamNames.WAIT_SECONDS]?.jsonPrimitive?.intOrNull ?: DEFAULT_WAIT_SECONDS)
            .coerceIn(0, MAX_WAIT_SECONDS)

    /** Missing/null/blank values are treated as absent, mirroring AbstractMcpTool.optionalStringArg. */
    fun optionalTrimmedString(arguments: JsonObject, name: String): String? =
        (arguments[name] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
}
