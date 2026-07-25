package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.SchemaConstants
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.BuiltInSearchScope
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.CallHierarchyTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.FindClassTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.FindFileTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.FindImplementationsTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.FindSymbolTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.FindUsagesTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.TypeHierarchyTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.settings.McpSettings
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring.RenameSymbolTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.LanguageHandlerRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.isExcludedPath
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import junit.framework.TestCase
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class ToolsUnitTest : TestCase() {
    private fun assertHasScopeAndNoLegacyFilters(toolName: String, properties: kotlinx.serialization.json.JsonObject?) {
        val scopeProperty = properties?.get(ParamNames.SCOPE)?.jsonObject
        assertNotNull("$toolName should have scope property", scopeProperty)
        assertEquals(
            "$toolName scope enum should match BuiltInSearchScope wire values",
            BuiltInSearchScope.supportedWireValues(),
            scopeProperty?.get("enum")?.jsonArray?.map { it.jsonPrimitive.content }
        )
        assertNull("$toolName should not have includeLibraries property", properties?.get("includeLibraries"))
        assertNull("$toolName should not have includeTests property", properties?.get("includeTests"))
    }

    override fun setUp() {
        super.setUp()
        mockkObject(LanguageHandlerRegistry)
        every { LanguageHandlerRegistry.getSupportedLanguageNamesForSymbolReference() } returns listOf("Java", "Kotlin", "JavaScript", "TypeScript")
    }

    override fun tearDown() {
        try {
            unmockkObject(LanguageHandlerRegistry)
        } finally {
            super.tearDown()
        }
    }

    /**
     * Every search-scoped tool must expose `scope` with exactly the wire values production
     * declares in [BuiltInSearchScope], and must not have regressed to the legacy
     * `includeLibraries` / `includeTests` booleans.
     */
    fun testScopeBearingToolsExposeBuiltInScopeEnum() {
        val tools = listOf(
            FindUsagesTool(),
            FindClassTool(),
            FindFileTool(),
            FindSymbolTool(),
            FindImplementationsTool(),
            TypeHierarchyTool(),
            CallHierarchyTool()
        )

        for (tool in tools) {
            assertHasScopeAndNoLegacyFilters(tool.name, tool.inputSchema[SchemaConstants.PROPERTIES]?.jsonObject)
        }
    }

    fun testAllToolsHaveProjectPathInSchema() {
        val registry = ToolRegistry()
        registry.registerBuiltInTools()

        val tools = registry.getAllTools()

        for (tool in tools) {
            val schema = tool.inputSchema
            val properties = schema[SchemaConstants.PROPERTIES]?.jsonObject

            assertNotNull("${tool.name} schema should have properties", properties)

            val projectPathProp = properties?.get(ParamNames.PROJECT_PATH)?.jsonObject
            assertNotNull("${tool.name} schema should include project_path property", projectPathProp)
        }
    }

    // ── rename mode resolution ─────────────────────────────────────────────────

    fun testRenameSymbolToolResolvesFileModeWhenTargetTypeFileEvenWithZeroCoordinates() {
        val decision = invokeRenameModeResolver("file", 0, 0)

        assertEquals("File rename should win when targetType=file", "FileRenameMode", decision.javaClass.simpleName)
    }

    fun testRenameSymbolToolRejectsZeroCoordinatesInSymbolMode() {
        val decision = invokeRenameModeResolver("symbol", 0, 0)

        assertEquals("Symbol mode with 0/0 should be rejected", "InvalidRenameMode", decision.javaClass.simpleName)
        val errorMessage = decision.javaClass.getMethod("getError").invoke(decision) as String
        assertTrue(errorMessage.contains("1-based"))
        assertTrue(errorMessage.contains("targetType=file"))
    }

    fun testRenameSymbolToolPreservesLegacyNullNullFileMode() {
        val decision = invokeRenameModeResolver(null, null, null)

        assertEquals("Legacy null/null request should still be file rename", "FileRenameMode", decision.javaClass.simpleName)
    }

    fun testRenameSymbolToolFileModeIgnoresMalformedCoordinates() {
        val decision = RenameSymbolTool.resolveRenameMode(
            buildJsonObject {
                put(ParamNames.TARGET_TYPE_CAMEL, kotlinx.serialization.json.JsonPrimitive("file"))
                put(ParamNames.LINE, kotlinx.serialization.json.JsonPrimitive("not-a-number"))
                put(ParamNames.COLUMN, kotlinx.serialization.json.JsonPrimitive("still-not-a-number"))
            }
        )

        assertEquals("File rename should short-circuit before parsing coordinates", "FileRenameMode", decision.javaClass.simpleName)
    }

    fun testRenameSymbolToolSymbolModeRejectsMalformedCoordinates() {
        val decision = RenameSymbolTool.resolveRenameMode(
            buildJsonObject {
                put(ParamNames.TARGET_TYPE_CAMEL, kotlinx.serialization.json.JsonPrimitive("symbol"))
                put(ParamNames.LINE, kotlinx.serialization.json.JsonPrimitive("not-a-number"))
                put(ParamNames.COLUMN, kotlinx.serialization.json.JsonPrimitive("still-not-a-number"))
            }
        )

        assertEquals("Symbol rename should reject malformed coordinates", "InvalidRenameMode", decision.javaClass.simpleName)
    }

    private fun invokeRenameModeResolver(targetType: String?, line: Int?, column: Int?): Any {
        return RenameSymbolTool.resolveRenameMode(targetType, line, column)
    }

    fun testRenameSymbolToolBypassesDialogSubstitutionForHeadlessJsTsFileRename() {
        assertTrue(
            "TypeScript file rename should keep the PsiFile target in headless mode to avoid JS/TS related-symbol dialogs",
            RenameSymbolTool.shouldBypassDialogSubstitutionForFileRename("TypeScript", "rename_base")
        )
        assertTrue(
            "JavaScript file rename should keep the PsiFile target in headless mode to avoid JS/TS related-symbol dialogs",
            RenameSymbolTool.shouldBypassDialogSubstitutionForFileRename("JavaScript", "rename_only_current")
        )

        assertFalse(
            "Explicit ask mode should preserve IDE dialog behavior",
            RenameSymbolTool.shouldBypassDialogSubstitutionForFileRename("TypeScript", "ask")
        )
        assertFalse(
            "Non-JS/TS file renames should keep the existing substitution behavior",
            RenameSymbolTool.shouldBypassDialogSubstitutionForFileRename("JAVA", "rename_base")
        )
    }

    fun testRenameSymbolToolRetargetsJsTsFileRenamesSemantically() {
        assertTrue(
            "TypeScript file renames should use semantic file-retargeting hooks when not asking the user",
            RenameSymbolTool.shouldRetargetJsTsFileRenameSemantically("TypeScript", "rename_base")
        )
        assertTrue(
            "JavaScript file renames should use semantic file-retargeting hooks when not asking the user",
            RenameSymbolTool.shouldRetargetJsTsFileRenameSemantically("JavaScript", "rename_only_current")
        )
        assertFalse(
            "Explicit ask mode must preserve the interactive dialog path",
            RenameSymbolTool.shouldRetargetJsTsFileRenameSemantically("TypeScript", "ask")
        )
        assertFalse(
            "Non-JS/TS files should stay on the generic rename path",
            RenameSymbolTool.shouldRetargetJsTsFileRenameSemantically("JAVA", "rename_base")
        )
    }

    // ── isExcludedPath pure logic tests ─────────────────────────────────────

    fun testIsExcludedPathDetectsBuildDirs() {
        assertTrue("bin/ should be excluded",     isExcludedPath("bin/Main.class"))
        assertTrue("build/ should be excluded",   isExcludedPath("build/libs/app.jar"))
        assertTrue("out/ should be excluded",     isExcludedPath("out/production/Main.class"))
        assertTrue(".gradle/ should be excluded", isExcludedPath(".gradle/cache/file.jar"))
    }

    fun testIsExcludedPathDetectsVenvDirs() {
        // Root-level venv dirs
        assertTrue(".venv/ should be excluded",   isExcludedPath(".venv/lib/python3.11/site-packages/flask/__init__.py"))
        assertTrue("venv/ should be excluded",    isExcludedPath("venv/lib/python3.11/site-packages/flask/__init__.py"))
        assertTrue(".env/ should be excluded",    isExcludedPath(".env/lib/python3.11/site-packages/flask/__init__.py"))
        assertTrue("env/ should be excluded",     isExcludedPath("env/lib/python3.11/site-packages/flask/__init__.py"))
        // Nested venv dirs (e.g. multi-module projects like python-services/.venv/)
        assertTrue("nested .venv/ should be excluded", isExcludedPath("python-services/.venv/lib/python3.13/site-packages/h11/_writers.py"))
        assertTrue("nested venv/ should be excluded",  isExcludedPath("backend/venv/lib/python3.11/flask/__init__.py"))
    }

    fun testIsExcludedPathDetectsNodeModules() {
        assertTrue("node_modules/ should be excluded",        isExcludedPath("node_modules/@types/react/index.d.ts"))
        assertTrue("nested node_modules/ should be excluded", isExcludedPath("packages/ui/node_modules/react/index.js"))
    }

    fun testIsExcludedPathDetectsWorktrees() {
        assertTrue(".worktrees/ should be excluded",        isExcludedPath(".worktrees/feature-branch/src/Main.kt"))
        assertTrue(".claude/worktrees/ should be excluded", isExcludedPath(".claude/worktrees/fix-123/src/Main.kt"))
    }

    fun testIsExcludedPathAllowsSourcePaths() {
        assertFalse("src/ should not be excluded",        isExcludedPath("src/main/kotlin/Foo.kt"))
        assertFalse("nested bin path should not match",   isExcludedPath("src/bin/config.txt"))
        assertFalse("nested build path should not match", isExcludedPath("src/build/notes.md"))
        assertFalse("root file should not be excluded",   isExcludedPath("README.md"))
    }

    // ── opt-in tools must ship disabled ────────────────────────────────────────

    fun testReplaceTextInFileToolDisabledByDefault() {
        assertTrue(
            "ide_replace_text_in_file should be disabled by default",
            McpSettings.DEFAULT_DISABLED_TOOLS.contains("ide_replace_text_in_file")
        )
    }

    fun testStructuralSearchReplaceToolIsDisabledByDefault() {
        assertTrue(ToolNames.STRUCTURAL_SEARCH_REPLACE in McpSettings.DEFAULT_DISABLED_TOOLS)
    }

    fun testChangeSignatureToolIsDisabledByDefault() {
        assertTrue(ToolNames.CHANGE_SIGNATURE in McpSettings.DEFAULT_DISABLED_TOOLS)
    }

    fun testCreateFileToolIsDisabledByDefault() {
        assertTrue(ToolNames.CREATE_FILE in McpSettings.DEFAULT_DISABLED_TOOLS)
    }

    fun testCodeEditingToolsAreDisabledByDefault() {
        val defaultDisabled = McpSettings.DEFAULT_DISABLED_TOOLS
        assertTrue("ide_edit_member should be disabled by default", ToolNames.EDIT_MEMBER in defaultDisabled)
        assertTrue("ide_insert_member should be disabled by default", ToolNames.INSERT_MEMBER in defaultDisabled)
        assertTrue("ide_replace_member should be disabled by default", ToolNames.REPLACE_MEMBER in defaultDisabled)
    }
}
