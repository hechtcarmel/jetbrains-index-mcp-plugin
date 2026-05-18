package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema

import kotlinx.serialization.json.JsonObject

object ToolResultSchemas {

    val symbolMatch: JsonObject = SchemaBuilder.tool()
        .stringProperty("name", "Symbol display name.", required = true)
        .stringProperty(
            "qualifiedName",
            "Fully qualified symbol name, when available.",
            required = true,
            nullable = true
        )
        .stringProperty("kind", "Symbol kind, such as class, method, field, or function.", required = true)
        .stringProperty("file", "Project-relative file path containing the symbol.", required = true)
        .intProperty("line", "1-based line number of the symbol.", required = true)
        .intProperty("column", "1-based column number of the symbol.", required = true)
        .stringProperty(
            "containerName",
            "Containing class, object, or namespace name, when available.",
            required = true,
            nullable = true
        )
        .stringProperty(
            "language",
            "Programming language for the symbol, when known.",
            required = true,
            nullable = true
        )
        .build()

}
