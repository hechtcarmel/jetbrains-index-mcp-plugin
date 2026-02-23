package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.SchemaConstants
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Tool that inserts content after a symbol (class, method, function, field).
 *
 * The symbol is identified by file + line + column position. The provided body text is inserted
 * immediately after the end of the symbol's definition. The insertion is auto-reformatted
 * and supports undo (Ctrl+Z).
 *
 * Uses a two-phase approach:
 * 1. **Read Phase**: Find the PSI element at the position and walk up to a named element
 * 2. **Write Phase**: Insert body text after the element using document-level insertion
 */
class InsertAfterSymbolTool : AbstractInsertSymbolTool() {

    override val insertPosition = InsertPosition.AFTER

    override val name = ToolNames.INSERT_AFTER_SYMBOL

    override val description = """
        Insert content after a symbol (class, method, function, field).
        The symbol is identified by file + line + column. The body text is inserted
        immediately after the symbol's definition. Auto-reformats after insertion. Supports undo (Ctrl+Z).

        A typical use case is to insert a new class, function, method, field or variable assignment.

        Returns: success status with file path and new line range.

        Parameters: file + line + column + body (all required).

        Example: {"file": "src/UserService.java", "line": 25, "column": 10, "body": "public void delete(User u) {\n    repo.delete(u);\n}"}
    """.trimIndent()

    override val inputSchema: JsonObject = buildJsonObject {
        put(SchemaConstants.TYPE, SchemaConstants.TYPE_OBJECT)
        putJsonObject(SchemaConstants.PROPERTIES) {
            putJsonObject(ParamNames.PROJECT_PATH) {
                put(SchemaConstants.TYPE, SchemaConstants.TYPE_STRING)
                put(SchemaConstants.DESCRIPTION, SchemaConstants.DESC_PROJECT_PATH)
            }
            putJsonObject(ParamNames.FILE) {
                put(SchemaConstants.TYPE, SchemaConstants.TYPE_STRING)
                put(SchemaConstants.DESCRIPTION, "Path to file relative to project root. REQUIRED.")
            }
            putJsonObject(ParamNames.LINE) {
                put(SchemaConstants.TYPE, SchemaConstants.TYPE_INTEGER)
                put(SchemaConstants.DESCRIPTION, "1-based line number where the symbol is located. REQUIRED.")
            }
            putJsonObject(ParamNames.COLUMN) {
                put(SchemaConstants.TYPE, SchemaConstants.TYPE_INTEGER)
                put(SchemaConstants.DESCRIPTION, "1-based column number. REQUIRED.")
            }
            putJsonObject(ParamNames.BODY) {
                put(
                    SchemaConstants.TYPE, SchemaConstants.TYPE_STRING
                )
                put(
                    SchemaConstants.DESCRIPTION,
                    "The source code to insert after the symbol. Must be valid for the target language. REQUIRED."
                )
            }
        }
        putJsonArray(SchemaConstants.REQUIRED) {
            add(JsonPrimitive(ParamNames.FILE))
            add(JsonPrimitive(ParamNames.LINE))
            add(JsonPrimitive(ParamNames.COLUMN))
            add(JsonPrimitive(ParamNames.BODY))
        }
    }
}
