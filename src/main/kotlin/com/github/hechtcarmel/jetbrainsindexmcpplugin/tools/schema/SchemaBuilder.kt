package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.SchemaConstants
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.BuiltInSearchScope
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.LanguageHandlerRegistry
import kotlinx.serialization.json.*
import kotlinx.serialization.json.put

class SchemaBuilder private constructor() {
    private val properties = linkedMapOf<String, JsonObject>()
    private val requiredFields = mutableListOf<String>()

    fun projectPath() = apply {
        properties[ParamNames.PROJECT_PATH] = buildJsonObject {
            put(SchemaConstants.TYPE, SchemaConstants.TYPE_STRING)
            put(SchemaConstants.DESCRIPTION, SchemaConstants.DESC_PROJECT_PATH)
        }
    }

    fun file(required: Boolean = true, description: String = SchemaConstants.DESC_FILE) = apply {
        properties[ParamNames.FILE] = buildJsonObject {
            put(SchemaConstants.TYPE, SchemaConstants.TYPE_STRING)
            put(SchemaConstants.DESCRIPTION, description)
        }
        if (required) requiredFields.add(ParamNames.FILE)
    }

    fun lineAndColumn(required: Boolean = true) = apply {
        properties[ParamNames.LINE] = buildJsonObject {
            put(SchemaConstants.TYPE, SchemaConstants.TYPE_INTEGER)
            put(SchemaConstants.DESCRIPTION, SchemaConstants.DESC_LINE)
        }
        properties[ParamNames.COLUMN] = buildJsonObject {
            put(SchemaConstants.TYPE, SchemaConstants.TYPE_INTEGER)
            put(SchemaConstants.DESCRIPTION, SchemaConstants.DESC_COLUMN)
        }
        if (required) {
            requiredFields.add(ParamNames.LINE)
            requiredFields.add(ParamNames.COLUMN)
        }
    }

    fun languageAndSymbol(required: Boolean = true) = apply {
        val supportedLanguages = LanguageHandlerRegistry.getSupportedLanguageNamesForSymbolReference()
        properties[ParamNames.LANGUAGE] = buildJsonObject {
            put(SchemaConstants.TYPE, SchemaConstants.TYPE_STRING)
            val languageDescription = buildString {
                append(SchemaConstants.DESC_LANGUAGE)
                if (supportedLanguages.isEmpty()) {
                    append(" No symbol reference handlers are currently available.")
                } else {
                    append(" Currently supported languages: ${supportedLanguages.joinToString(", ")}.")
                }
            }
            put(SchemaConstants.DESCRIPTION, languageDescription)
            if (supportedLanguages.isNotEmpty()) {
                putJsonArray("enum") { supportedLanguages.forEach { add(JsonPrimitive(it)) } }
            }
        }
        properties[ParamNames.SYMBOL] = buildJsonObject {
            put(SchemaConstants.TYPE, SchemaConstants.TYPE_STRING)
            put(SchemaConstants.DESCRIPTION, SchemaConstants.DESC_SYMBOL)
        }
        if (required) {
            requiredFields.add(ParamNames.LANGUAGE)
            requiredFields.add(ParamNames.SYMBOL)
        }
    }

    fun stringProperty(
        name: String,
        description: String,
        required: Boolean = false,
        nullable: Boolean = false
    ) = apply {
        properties[name] = typedSchema(SchemaConstants.TYPE_STRING, description, nullable)
        if (required) requiredFields.add(name)
    }

    fun stringProperty(name: String, description: String, required: Boolean) =
        stringProperty(name, description, required, nullable = false)

    fun intProperty(
        name: String,
        description: String,
        required: Boolean = false,
        nullable: Boolean = false
    ) = apply {
        properties[name] = typedSchema(SchemaConstants.TYPE_INTEGER, description, nullable)
        if (required) requiredFields.add(name)
    }

    fun intProperty(name: String, description: String, required: Boolean) =
        intProperty(name, description, required, nullable = false)

    fun numberProperty(
        name: String,
        description: String,
        required: Boolean = false,
        nullable: Boolean = false
    ) = apply {
        properties[name] = typedSchema("number", description, nullable)
        if (required) requiredFields.add(name)
    }

    fun booleanProperty(
        name: String,
        description: String,
        required: Boolean = false,
        nullable: Boolean = false
    ) = apply {
        properties[name] = typedSchema(SchemaConstants.TYPE_BOOLEAN, description, nullable)
        if (required) requiredFields.add(name)
    }

    fun booleanProperty(name: String, description: String, required: Boolean) =
        booleanProperty(name, description, required, nullable = false)

    fun enumProperty(
        name: String,
        description: String,
        values: List<String>,
        required: Boolean = false,
        nullable: Boolean = false
    ) = apply {
        properties[name] = buildJsonObject {
            putType(SchemaConstants.TYPE_STRING, nullable)
            put(SchemaConstants.DESCRIPTION, description)
            putJsonArray("enum") {
                values.forEach { add(JsonPrimitive(it)) }
                if (nullable) add(JsonNull)
            }
        }
        if (required) requiredFields.add(name)
    }

    fun enumProperty(name: String, description: String, values: List<String>, required: Boolean) =
        enumProperty(name, description, values, required, nullable = false)

    fun arrayProperty(
        name: String,
        description: String,
        items: JsonObject,
        required: Boolean = false,
        nullable: Boolean = false
    ) = apply {
        properties[name] = buildJsonObject {
            putType(SchemaConstants.TYPE_ARRAY, nullable)
            put(SchemaConstants.DESCRIPTION, description)
            put("items", items)
        }
        if (required) requiredFields.add(name)
    }

    fun stringArrayProperty(
        name: String,
        description: String,
        required: Boolean = false,
        nullable: Boolean = false
    ) =
        arrayProperty(name, description, items = buildJsonObject {
            put(SchemaConstants.TYPE, SchemaConstants.TYPE_STRING)
        }, required = required, nullable = nullable)


    fun scopeProperty(description: String, required: Boolean = false) = apply {
        enumProperty(
            name = ParamNames.SCOPE,
            description = description,
            values = BuiltInSearchScope.supportedWireValues(),
            required = required
        )
    }

    fun property(name: String, schema: JsonObject, required: Boolean = false, nullable: Boolean = false) = apply {
        properties[name] = if (nullable) schema.withNullableType() else schema
        if (required) requiredFields.add(name)
    }

    fun property(name: String, schema: JsonObject, required: Boolean) =
        property(name, schema, required, nullable = false)

    fun build(): JsonObject = buildJsonObject {
        put(SchemaConstants.TYPE, SchemaConstants.TYPE_OBJECT)
        putJsonObject(SchemaConstants.PROPERTIES) {
            for ((name, schema) in properties) {
                put(name, schema)
            }
        }
        if (requiredFields.isNotEmpty()) {
            putJsonArray(SchemaConstants.REQUIRED) {
                for (field in requiredFields) {
                    add(JsonPrimitive(field))
                }
            }
        }
    }

    companion object {
        fun tool() = SchemaBuilder()
    }
}

private fun typedSchema(type: String, description: String, nullable: Boolean = false): JsonObject = buildJsonObject {
    putType(type, nullable)
    put(SchemaConstants.DESCRIPTION, description)
}

private fun JsonObject.withNullableType(): JsonObject = buildJsonObject {
    for ((key, value) in this@withNullableType) {
        if (key == SchemaConstants.TYPE && value is JsonPrimitive && value.isString) {
            putJsonArray(SchemaConstants.TYPE) {
                add(value)
                add(JsonPrimitive("null"))
            }
        } else {
            put(key, value)
        }
    }
}

private fun JsonObjectBuilder.putType(type: String, nullable: Boolean) {
    if (nullable) {
        putJsonArray(SchemaConstants.TYPE) {
            add(JsonPrimitive(type))
            add(JsonPrimitive("null"))
        }
    } else {
        put(SchemaConstants.TYPE, type)
    }
}

