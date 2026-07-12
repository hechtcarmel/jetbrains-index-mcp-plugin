package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.settings.McpSettings
import junit.framework.TestCase
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class OpenWorkspaceToolUnitTest : TestCase() {

    fun testToolNameMatchesConstant() {
        assertEquals(ToolNames.OPEN_WORKSPACE, OpenWorkspaceTool().name)
    }

    fun testSchemaHasPathAndModulesParams() {
        val schema = OpenWorkspaceTool().inputSchema
        val properties = schema["properties"]?.jsonObject
        assertNotNull("Should have path property", properties?.get("path"))
        assertNotNull("Should have modules property", properties?.get("modules"))
        val required = schema["required"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        assertFalse("path should not be required (mutually exclusive with modules)", required.contains("path"))
        assertFalse("modules should not be required (mutually exclusive with path)", required.contains("modules"))
    }

    fun testModulesSchemaIsArrayOfStrings() {
        val schema = OpenWorkspaceTool().inputSchema
        val modulesSchema = schema["properties"]?.jsonObject?.get("modules")?.jsonObject
        assertEquals("array", modulesSchema?.get("type")?.jsonPrimitive?.content)
        val itemsSchema = modulesSchema?.get("items")?.jsonObject
        assertEquals("string", itemsSchema?.get("type")?.jsonPrimitive?.content)
        assertEquals("1", modulesSchema?.get("minItems")?.jsonPrimitive?.content)
    }

    fun testToolIsDisabledByDefault() {
        assertTrue(ToolNames.OPEN_WORKSPACE in McpSettings.DEFAULT_DISABLED_TOOLS)
    }

    fun testToolNameInAll() {
        assertTrue(ToolNames.ALL.contains(ToolNames.OPEN_WORKSPACE))
    }

    fun testDescriptionMentionsBothModes() {
        val description = OpenWorkspaceTool().description
        assertTrue("Should mention path mode", description.contains("path"))
        assertTrue("Should mention modules mode", description.contains("modules"))
        assertTrue("Should mention mutually exclusive", description.contains("mutually exclusive"))
    }

    fun testDescriptionMentionsCaching() {
        val description = OpenWorkspaceTool().description
        assertTrue("Should mention caching", description.contains("cached") || description.contains("reuses"))
    }
}
