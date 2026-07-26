package com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil

import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * Reads a [ToolSchema] the way it appears on the wire.
 *
 * Tool schemas used to be plain `JsonObject`s, and ~40 assertions across the suite are written as
 * `schema["properties"]?.jsonObject?.get("mode")`. The MCP SDK's [ToolSchema] is a typed record
 * instead, so this maps the three keys it can produce back to their JSON form.
 *
 * `properties` is handed back as-is — the same object the production code holds — so these
 * assertions still read real production state rather than a reconstruction. `type` and `required`
 * are the only synthesised values, and both are one-liners over the same typed fields the
 * serializer uses.
 */
operator fun ToolSchema.get(key: String): JsonElement? = when (key) {
    "type" -> JsonPrimitive(type)
    "properties" -> properties
    "required" -> required?.let { names -> JsonArray(names.map(::JsonPrimitive)) }
    "\$defs" -> defs
    else -> null
}
