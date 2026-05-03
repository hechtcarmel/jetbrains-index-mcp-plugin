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

    fun testMixedNonEmptyLookupModesRemainMutuallyExclusive() {
        val arguments = buildJsonObject {
            put("file", JsonPrimitive("src/Main.kt"))
            put("line", JsonPrimitive(12))
            put("column", JsonPrimitive(5))
            put("language", JsonPrimitive("kotlin"))
            put("symbol", JsonPrimitive("com.example.Main#run()"))
        }

        val result = tool.resolveElementForTest(project, arguments)

        assertTrue(result.isFailure)
        assertEquals(
            ErrorMessages.SYMBOL_AND_POSITION_EXCLUSIVE,
            result.exceptionOrNull()?.message
        )
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
    }
}
