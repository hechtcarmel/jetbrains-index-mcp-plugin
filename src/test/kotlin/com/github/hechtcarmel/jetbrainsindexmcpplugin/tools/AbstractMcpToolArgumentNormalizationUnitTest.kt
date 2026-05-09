package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ErrorMessages
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.intellij.openapi.project.Project
import junit.framework.TestCase
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.lang.reflect.Method
import java.lang.reflect.Proxy

class AbstractMcpToolArgumentNormalizationUnitTest : TestCase() {

    private val tool = ProbeTool()
    private val project = Proxy.newProxyInstance(
        Project::class.java.classLoader,
        arrayOf(Project::class.java)
    ) { _, _, _ -> null } as Project

    fun testOptionalBlankToNullNormalization() {
        val arguments = buildJsonObject { put("cursor", JsonPrimitive("   ")) }

        val normalized = invokeOptionalStringArg(arguments, "cursor")

        assertNull("Blank optional value should normalize to null", normalized)
    }

    fun testOptionalNonEmptyValueIsPreservedTrimmed() {
        val arguments = buildJsonObject { put("cursor", JsonPrimitive("  page-2  ")) }

        val normalized = invokeOptionalStringArg(arguments, "cursor")

        assertEquals("page-2", normalized)
    }

    fun testOptionalNullNormalization() {
        val arguments = buildJsonObject { put("cursor", JsonNull) }

        val normalized = invokeOptionalStringArg(arguments, "cursor")

        assertNull("Null optional value should normalize to null", normalized)
    }

    fun testOptionalBlankIntNormalization() {
        val arguments = buildJsonObject { put("line", JsonPrimitive("   ")) }

        val normalized = tool.optionalIntForTest(arguments, "line")

        assertNull("Blank optional int should normalize to null", normalized)
    }

    fun testOptionalNumericStringNormalization() {
        val arguments = buildJsonObject { put("line", JsonPrimitive(" 42 ")) }

        val normalized = tool.optionalIntForTest(arguments, "line")

        assertEquals(42, normalized)
    }

    fun testRequiredBlankRejected() {
        val arguments = buildJsonObject { put("file", JsonPrimitive("   ")) }

        val requiredResult = invokeRequiredStringArg(arguments, "file")

        assertTrue("Required blank value should be rejected", requiredResult.isFailure)
        val message = requiredResult.exceptionOrNull()?.message
        assertEquals(ErrorMessages.missingRequiredParam("file"), message)
    }

    fun testLookupInferenceIgnoresBlankFileLanguageAndSymbol() {
        val arguments = buildJsonObject {
            put("file", JsonPrimitive("   "))
            put("language", JsonPrimitive("  "))
            put("symbol", JsonPrimitive("\t"))
        }

        val result = tool.resolveElementForTest(project, arguments)

        assertTrue(result.isFailure)
        assertEquals(
            ErrorMessages.SYMBOL_OR_POSITION_REQUIRED,
            result.exceptionOrNull()?.message
        )
    }

    fun testBlankSymbolWithCompletePositionResolvesAsPosition() {
        val arguments = buildJsonObject {
            put("file", JsonPrimitive("src/Main.kt"))
            put("line", JsonPrimitive(12))
            put("column", JsonPrimitive(5))
            put("symbol", JsonPrimitive(""))
        }

        val lookupMode = tool.lookupModeForTest(arguments)

        assertEquals("POSITION", lookupMode)
    }

    fun testBlankLanguageAndSymbolWithCompletePositionResolvesAsPosition() {
        val arguments = buildJsonObject {
            put("file", JsonPrimitive("src/Main.kt"))
            put("line", JsonPrimitive(12))
            put("column", JsonPrimitive(5))
            put("language", JsonPrimitive(" C# "))
            put("symbol", JsonPrimitive("   "))
        }

        val lookupMode = tool.lookupModeForTest(arguments)

        assertEquals("POSITION", lookupMode)
    }

    fun testCompletePositionWinsOverCompleteSymbol() {
        val arguments = buildJsonObject {
            put("file", JsonPrimitive("src/Main.kt"))
            put("line", JsonPrimitive(12))
            put("column", JsonPrimitive(5))
            put("language", JsonPrimitive("kotlin"))
            put("symbol", JsonPrimitive("com.example.Main#run()"))
        }

        val lookupMode = tool.lookupModeForTest(arguments)

        assertEquals("POSITION", lookupMode)
    }

    fun testPositionLookupIgnoresBlankLanguageAndSymbol() {
        val arguments = buildJsonObject {
            put("file", JsonPrimitive("src/Main.kt"))
            put("line", JsonPrimitive(12))
            put("column", JsonPrimitive(5))
            put("language", JsonPrimitive("   "))
            put("symbol", JsonPrimitive("\t"))
        }

        val lookupMode = tool.lookupModeForTest(arguments)

        assertEquals("POSITION", lookupMode)
    }

    fun testLookupModeTreatsBlankNumericStringsAsAbsent() {
        val arguments = buildJsonObject {
            put("file", JsonPrimitive("   "))
            put("line", JsonPrimitive("   "))
            put("column", JsonPrimitive(""))
            put("language", JsonPrimitive("   "))
            put("symbol", JsonPrimitive("   "))
        }

        val lookupMode = tool.lookupModeForTest(arguments)

        assertEquals("MISSING", lookupMode)
    }

    fun testCompleteSymbolModeStillWorksWhenPositionIsAbsent() {
        val arguments = buildJsonObject {
            put("language", JsonPrimitive("C#"))
            put("symbol", JsonPrimitive("Namespace.Type#Method"))
            put("file", JsonPrimitive("   "))
            put("line", JsonPrimitive(""))
            put("column", JsonPrimitive("\t"))
        }

        val lookupMode = tool.lookupModeForTest(arguments)

        assertEquals("SYMBOL", lookupMode)
    }

    fun testIncompletePositionDoesNotOverrideCompleteSymbol() {
        val arguments = buildJsonObject {
            put("file", JsonPrimitive("src/Main.kt"))
            put("line", JsonPrimitive("   "))
            put("column", JsonPrimitive(5))
            put("language", JsonPrimitive("C#"))
            put("symbol", JsonPrimitive("Namespace.Type#Method"))
        }

        val lookupMode = tool.lookupModeForTest(arguments)

        assertEquals("SYMBOL", lookupMode)
    }

    fun testIncompleteModesStillFailAsMissingTarget() {
        val arguments = buildJsonObject {
            put("file", JsonPrimitive("src/Main.kt"))
            put("line", JsonPrimitive("   "))
            put("language", JsonPrimitive("C#"))
            put("symbol", JsonPrimitive("   "))
        }

        val result = tool.resolveElementForTest(project, arguments)

        assertTrue(result.isFailure)
        assertEquals(
            ErrorMessages.SYMBOL_OR_POSITION_REQUIRED,
            result.exceptionOrNull()?.message
        )
    }

    fun testBlankExplicitPageSizeUsesCursorEmbeddedFallback() {
        val arguments = buildJsonObject { put("pageSize", JsonPrimitive("   ")) }

        val pageSize = tool.explicitPageSizeForTest(arguments)

        assertNull("Blank explicit page size should be treated as absent", pageSize)
    }

    fun testBlankPageSizeAliasUsesDefaultPageSize() {
        val arguments = buildJsonObject { put("limit", JsonPrimitive("   ")) }

        val pageSize = tool.pageSizeForTest(arguments, defaultPageSize = 25, alias = "limit")

        assertEquals(25, pageSize)
    }

    private fun invokeOptionalStringArg(arguments: JsonObject, name: String): String? {
        val method = findMethod("optionalStringArg")
        return method.invoke(tool, arguments, name) as String?
    }

    private fun invokeRequiredStringArg(arguments: JsonObject, name: String): Result<*> {
        return tool.requiredStringForTest(arguments, name)
    }

    private fun findMethod(name: String): Method {
        return try {
            AbstractMcpTool::class.java.getDeclaredMethod(name, JsonObject::class.java, String::class.java).apply {
                isAccessible = true
            }
        } catch (noSuchMethod: NoSuchMethodException) {
            fail("Expected AbstractMcpTool.$name(JsonObject, String) to exist for argument normalization contract")
            throw noSuchMethod
        }
    }

    private class ProbeTool : AbstractMcpTool() {
        override val name: String = "probe"
        override val description: String = "probe"
        override val inputSchema: JsonObject = buildJsonObject {}

        override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
            error("Not used in unit tests")
        }

        fun resolveElementForTest(project: Project, arguments: JsonObject): Result<com.intellij.psi.PsiElement> {
            return resolveElementFromArguments(project, arguments)
        }

        fun requiredStringForTest(arguments: JsonObject, name: String): Result<String> {
            return requiredStringArg(arguments, name)
        }

        fun optionalIntForTest(arguments: JsonObject, name: String): Int? {
            return optionalIntArg(arguments, name)
        }

        fun lookupModeForTest(arguments: JsonObject): String {
            return resolveLookupMode(arguments).name
        }

        fun explicitPageSizeForTest(arguments: JsonObject): Int? {
            return resolveExplicitPageSize(arguments)
        }

        fun pageSizeForTest(arguments: JsonObject, defaultPageSize: Int, alias: String): Int {
            return resolvePageSize(arguments, defaultPageSize, aliases = arrayOf(alias))
        }
    }
}
