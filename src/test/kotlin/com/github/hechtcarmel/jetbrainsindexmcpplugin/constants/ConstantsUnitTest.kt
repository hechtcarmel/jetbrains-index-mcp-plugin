package com.github.hechtcarmel.jetbrainsindexmcpplugin.constants

import com.github.hechtcarmel.jetbrainsindexmcpplugin.McpConstants
import junit.framework.TestCase

class ConstantsUnitTest : TestCase() {

    // ToolNames tests

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
            ToolNames.EDIT_MEMBER,
            ToolNames.INDEX_STATUS,
            ToolNames.SYNC_FILES,
            ToolNames.BUILD_PROJECT,
            ToolNames.IMPORT_MODULES,
            ToolNames.CHANGE_SIGNATURE,
            ToolNames.CREATE_FILE,
            ToolNames.STRUCTURAL_SEARCH_REPLACE,
            ToolNames.INSERT_MEMBER,
            ToolNames.LIST_TESTS,
            ToolNames.RUN_TESTS,
            ToolNames.REFACTOR_RENAME,
            ToolNames.REFACTOR_SAFE_DELETE,
            ToolNames.REFACTOR_MOVE,
            ToolNames.REFORMAT_CODE,
            ToolNames.OPTIMIZE_IMPORTS,
            ToolNames.CONVERT_JAVA_TO_KOTLIN,
            ToolNames.GET_ACTIVE_FILE,
            ToolNames.OPEN_FILE,
            ToolNames.INSTALL_PLUGIN,
            ToolNames.RELOAD_PROJECT,
            ToolNames.REPLACE_TEXT_IN_FILE,
            ToolNames.REPLACE_MEMBER,
            ToolNames.RESTART_IDE,
            ToolNames.CLOSE_PROJECT,
            ToolNames.OPEN_PROJECT,
            ToolNames.OPEN_WORKSPACE,
            ToolNames.SET_POWER_SAVE_MODE,
            // Lifecycle management
            ToolNames.ENROLL_ALL_PROJECTS,
            ToolNames.GET_PROJECT_MODES,
            ToolNames.LIFECYCLE_LOG,
            ToolNames.LIFECYCLE_LOG_FILE,
            ToolNames.PROJECT_STATUS,
            ToolNames.RELEASE_ALL_PROJECTS,
            ToolNames.RELEASE_PROJECT,
            ToolNames.SET_ALL_PROJECT_MODES,
            ToolNames.SET_PROJECT_MODE
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

    // ErrorMessages tests

    fun testErrorMessagesProjectErrors() {
        assertEquals("no_project_open", ErrorMessages.ERROR_NO_PROJECT_OPEN)
        assertEquals("project_not_found", ErrorMessages.ERROR_PROJECT_NOT_FOUND)
        assertEquals("multiple_projects_open", ErrorMessages.ERROR_MULTIPLE_PROJECTS)
    }

    fun testNoSymbolReferenceHandlerWithSupportedLanguages() {
        val message = ErrorMessages.noSymbolReferenceHandler("Python", listOf("Java", "Kotlin", "Java"))
        assertTrue(message.contains("Unsupported language for symbol references: Python"))
        assertTrue(message.contains("Use file+line+column instead"))
        assertTrue(message.contains("Currently supported languages: Java, Kotlin"))
    }

    fun testNoSymbolReferenceHandlerWithoutSupportedLanguages() {
        val message = ErrorMessages.noSymbolReferenceHandler("Python", emptyList())
        assertTrue(message.contains("Unsupported language for symbol references: Python"))
        assertTrue(message.contains("No symbol reference handlers are available in this IDE session"))
        assertTrue(message.contains("Use file+line+column instead"))
    }

    // McpConstants tests

    /**
     * Covers the whole build-stamped-version chain: the resource has to exist, `processResources`
     * has to have expanded the placeholder, and the read has to find the key. A missing resource
     * yields "unknown" and a dropped `expand` yields the literal "${pluginVersion}" — both fail here.
     */
    fun testServerVersionIsStampedByTheBuild() {
        val version = McpConstants.getServerVersion()
        assertTrue(
            "Expected a version stamped from gradle.properties, got '$version'",
            version.matches(Regex("""\d+\.\d+\.\d+.*"""))
        )
    }
}
