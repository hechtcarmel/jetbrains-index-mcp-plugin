package com.github.hechtcarmel.jetbrainsindexmcpplugin.constants

import junit.framework.TestCase

class ConstantsUnitTest : TestCase() {

    // ToolNames tests

    fun testToolNamesNavigationTools() {
        assertEquals("ide_find_references", ToolNames.FIND_REFERENCES)
        assertEquals("ide_find_definition", ToolNames.FIND_DEFINITION)
        assertEquals("ide_type_hierarchy", ToolNames.TYPE_HIERARCHY)
        assertEquals("ide_call_hierarchy", ToolNames.CALL_HIERARCHY)
        assertEquals("ide_find_implementations", ToolNames.FIND_IMPLEMENTATIONS)
        assertEquals("ide_find_symbol", ToolNames.FIND_SYMBOL)
        assertEquals("ide_find_super_methods", ToolNames.FIND_SUPER_METHODS)
    }

    fun testToolNamesIntelligenceTools() {
        assertEquals("ide_diagnostics", ToolNames.DIAGNOSTICS)
    }

    fun testToolNamesProjectTools() {
        assertEquals("ide_index_status", ToolNames.INDEX_STATUS)
        assertEquals("ide_build_project", ToolNames.BUILD_PROJECT)
    }

    fun testToolNamesRefactoringTools() {
        assertEquals("ide_refactor_rename", ToolNames.REFACTOR_RENAME)
        assertEquals("ide_refactor_safe_delete", ToolNames.REFACTOR_SAFE_DELETE)
        assertEquals("ide_move_file", ToolNames.REFACTOR_MOVE)
        assertEquals("ide_reformat_code", ToolNames.REFORMAT_CODE)
    }

    fun testToolNamesEditorTools() {
        assertEquals("ide_get_active_file", ToolNames.GET_ACTIVE_FILE)
        assertEquals("ide_open_file", ToolNames.OPEN_FILE)
    }

    fun testToolNamesHaveIdePrefix() {
        ToolNames.ALL.forEach { name ->
            assertTrue("Tool name '$name' should start with 'ide_'", name.startsWith("ide_"))
        }
    }

    fun testToolNamesAllContainsEveryConstant() {
        val expectedNames = listOf(
            ToolNames.FIND_REFERENCES,
            ToolNames.FIND_DEFINITION,
            ToolNames.TYPE_HIERARCHY,
            ToolNames.CALL_HIERARCHY,
            ToolNames.FIND_IMPLEMENTATIONS,
            ToolNames.FIND_SYMBOL,
            ToolNames.FIND_SUPER_METHODS,
            ToolNames.FILE_STRUCTURE,
            ToolNames.FIND_CLASS,
            ToolNames.FIND_FILE,
            ToolNames.SEARCH_TEXT,
            ToolNames.READ_FILE,
            ToolNames.DIAGNOSTICS,
            ToolNames.INDEX_STATUS,
            ToolNames.SYNC_FILES,
            ToolNames.BUILD_PROJECT,
            ToolNames.REFACTOR_RENAME,
            ToolNames.REFACTOR_SAFE_DELETE,
            ToolNames.REFACTOR_MOVE,
            ToolNames.REFORMAT_CODE,
            ToolNames.OPTIMIZE_IMPORTS,
            ToolNames.CONVERT_JAVA_TO_KOTLIN,
            ToolNames.GET_ACTIVE_FILE,
            ToolNames.OPEN_FILE
        )

        for (name in expectedNames) {
            assertTrue("ToolNames.ALL should contain '$name'", ToolNames.ALL.contains(name))
        }
        assertEquals("ToolNames.ALL should have exactly ${expectedNames.size} entries",
            expectedNames.size, ToolNames.ALL.size)
    }

    fun testToolNamesAllIsSorted() {
        val sorted = ToolNames.ALL.sorted()
        assertEquals("ToolNames.ALL should be sorted alphabetically", sorted, ToolNames.ALL)
    }

    // JsonRpcMethods tests

    fun testJsonRpcMethodsValues() {
        assertEquals("initialize", JsonRpcMethods.INITIALIZE)
        assertEquals("notifications/initialized", JsonRpcMethods.NOTIFICATIONS_INITIALIZED)
        assertEquals("ping", JsonRpcMethods.PING)
        assertEquals("tools/list", JsonRpcMethods.TOOLS_LIST)
        assertEquals("tools/call", JsonRpcMethods.TOOLS_CALL)
    }

    // ParamNames tests

    fun testParamNamesCommon() {
        assertEquals("project_path", ParamNames.PROJECT_PATH)
        assertEquals("file", ParamNames.FILE)
        assertEquals("line", ParamNames.LINE)
        assertEquals("column", ParamNames.COLUMN)
        assertEquals("name", ParamNames.NAME)
        assertEquals("uri", ParamNames.URI)
        assertEquals("arguments", ParamNames.ARGUMENTS)
    }

    fun testParamNamesRefactoring() {
        assertEquals("destination", ParamNames.DESTINATION)
        assertEquals("update_references", ParamNames.UPDATE_REFERENCES)
        assertEquals("newName", ParamNames.NEW_NAME)
        assertEquals("methodName", ParamNames.METHOD_NAME)
        assertEquals("variableName", ParamNames.VARIABLE_NAME)
        assertEquals("startLine", ParamNames.START_LINE)
        assertEquals("endLine", ParamNames.END_LINE)
        assertEquals("force", ParamNames.FORCE)
        assertEquals("optimizeImports", ParamNames.OPTIMIZE_IMPORTS)
        assertEquals("rearrangeCode", ParamNames.REARRANGE_CODE)
    }

    fun testParamNamesNavigation() {
        assertEquals("className", ParamNames.CLASS_NAME)
        assertEquals("direction", ParamNames.DIRECTION)
    }

    fun testParamNamesSymbolSearch() {
        assertEquals("query", ParamNames.QUERY)
        assertEquals("includeLibraries", ParamNames.INCLUDE_LIBRARIES)
        assertEquals("limit", ParamNames.LIMIT)
    }

    fun testParamNamesBuild() {
        assertEquals("rebuild", ParamNames.REBUILD)
        assertEquals("includeRawOutput", ParamNames.INCLUDE_RAW_OUTPUT)
        assertEquals("timeoutSeconds", ParamNames.TIMEOUT_SECONDS)
    }

    // UsageTypes tests

    fun testUsageTypesValues() {
        assertEquals("METHOD_CALL", UsageTypes.METHOD_CALL)
        assertEquals("REFERENCE", UsageTypes.REFERENCE)
        assertEquals("FIELD_ACCESS", UsageTypes.FIELD_ACCESS)
        assertEquals("IMPORT", UsageTypes.IMPORT)
        assertEquals("PARAMETER", UsageTypes.PARAMETER)
        assertEquals("VARIABLE", UsageTypes.VARIABLE)
    }

    // ErrorMessages tests

    fun testErrorMessagesFileErrors() {
        assertTrue(ErrorMessages.DOCUMENT_NOT_FOUND.contains("document"))
        assertTrue(ErrorMessages.DEFINITION_FILE_NOT_FOUND.contains("file"))
    }

    fun testErrorMessagesSymbolErrors() {
        assertTrue(ErrorMessages.SYMBOL_NOT_RESOLVED.contains("symbol"))
        assertTrue(ErrorMessages.NO_NAMED_ELEMENT.contains("element"))
        assertTrue(ErrorMessages.COULD_NOT_RESOLVE_SYMBOL.contains("symbol"))
    }

    fun testErrorMessagesProjectErrors() {
        assertEquals("no_project_open", ErrorMessages.ERROR_NO_PROJECT_OPEN)
        assertEquals("project_not_found", ErrorMessages.ERROR_PROJECT_NOT_FOUND)
        assertEquals("multiple_projects_open", ErrorMessages.ERROR_MULTIPLE_PROJECTS)
    }

    fun testErrorMessagesProjectMessages() {
        assertTrue(ErrorMessages.MSG_NO_PROJECT_OPEN.contains("project"))
        assertTrue(ErrorMessages.MSG_MULTIPLE_PROJECTS.contains("project_path"))
    }

    fun testErrorMessagesJsonRpc() {
        assertTrue(ErrorMessages.PARSE_ERROR.contains("parse") || ErrorMessages.PARSE_ERROR.contains("JSON"))
        assertTrue(ErrorMessages.MISSING_PARAMS.contains("params"))
        assertTrue(ErrorMessages.MISSING_TOOL_NAME.contains("tool"))
        assertTrue(ErrorMessages.MISSING_RESOURCE_URI.contains("URI"))
    }

    // SchemaConstants tests

    fun testSchemaConstantsKeys() {
        assertEquals("type", SchemaConstants.TYPE)
        assertEquals("description", SchemaConstants.DESCRIPTION)
        assertEquals("properties", SchemaConstants.PROPERTIES)
        assertEquals("required", SchemaConstants.REQUIRED)
        assertEquals("items", SchemaConstants.ITEMS)
        assertEquals("enum", SchemaConstants.ENUM)
    }

    fun testSchemaConstantsTypes() {
        assertEquals("object", SchemaConstants.TYPE_OBJECT)
        assertEquals("string", SchemaConstants.TYPE_STRING)
        assertEquals("integer", SchemaConstants.TYPE_INTEGER)
        assertEquals("boolean", SchemaConstants.TYPE_BOOLEAN)
        assertEquals("array", SchemaConstants.TYPE_ARRAY)
    }

    fun testSchemaConstantsDescriptions() {
        assertTrue(SchemaConstants.DESC_PROJECT_PATH.contains("project"))
        assertTrue(SchemaConstants.DESC_FILE.contains("file"))
        assertTrue(SchemaConstants.DESC_LINE.contains("line"))
        assertTrue(SchemaConstants.DESC_COLUMN.contains("column"))
    }
}
