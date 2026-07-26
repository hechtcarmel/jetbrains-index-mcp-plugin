package com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent

/**
 * MCP models `isError` as an optional `Boolean?`, where absent means "not an error". Tests care
 * about the two-valued question, so read it through this rather than sprinkling `== true`.
 */
val CallToolResult.isFailure: Boolean
    get() = isError == true

/** Concatenated text of every text block, which is what almost every assertion actually wants. */
val CallToolResult.text: String
    get() = content.filterIsInstance<TextContent>().joinToString("\n") { it.text }
