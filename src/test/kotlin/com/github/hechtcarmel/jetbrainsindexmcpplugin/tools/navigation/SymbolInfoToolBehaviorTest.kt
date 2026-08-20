package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation

import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.SymbolInfoResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PluginDetectors
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.SignatureSources
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assume

/**
 * Behavior coverage for `ide_symbol_info`.
 *
 * The fixture is built so that source text and resolved types differ: `Request` and `Result` live
 * in `svc.model` and are reached through imports, so a signature echoing what the author wrote
 * says `Request`, while a *resolved* one says `svc.model.Request`. Every type assertion below is
 * on the qualified form, which is what makes these tests fail rather than pass if the tool ever
 * regresses to reading source text — the exact gap the tool exists to close.
 */
class SymbolInfoToolBehaviorTest : McpPlatformTestCase() {

    private val json = Json { ignoreUnknownKeys = true }

    private fun writeServiceFixture() {
        registerSourceRoot("src")
        writeProjectFile(
            "src/svc/model/Request.java",
            """
                package svc.model;

                public class Request {
                }
            """.trimIndent()
        )
        writeProjectFile(
            "src/svc/model/Result.java",
            """
                package svc.model;

                public class Result {
                }
            """.trimIndent()
        )
        writeProjectFile(
            "src/svc/Service.java",
            """
                package svc;

                import svc.model.Request;
                import svc.model.Result;

                public class Service {
                    /**
                     * Handles one request and reports the outcome.
                     */
                    public Result handle(Request request) {
                        return null;
                    }

                    public Result handle(Request request, boolean retry) {
                        return null;
                    }
                }
            """.trimIndent()
        )
    }

    private fun symbolInfoAt(line: Int, column: Int, extra: Map<String, Boolean> = emptyMap()): SymbolInfoResult =
        runBlocking {
            val result = SymbolInfoTool().execute(project, buildJsonObject {
                put("file", "src/svc/Service.java")
                put("line", line)
                put("column", column)
                extra.forEach { (key, value) -> put(key, value) }
            })
            assertToolSucceeded("ide_symbol_info should succeed at $line:$column", result)
            json.decodeFromString<SymbolInfoResult>(toolText(result))
        }

    fun testMethodParameterAndReturnTypesAreQualified() {
        Assume.assumeTrue("Java plugin required for this fixture", PluginDetectors.java.isAvailable)
        writeServiceFixture()

        // Line 10 is `public Result handle(Request request) {`; column 19 is inside `handle`.
        val info = symbolInfoAt(line = 10, column = 19)

        assertEquals("handle", info.name)
        assertEquals(SignatureSources.JAVA_PSI, info.signatureSource)
        assertEquals("svc.Service", info.containingDeclaration)
        assertEquals("svc.Service#handle", info.qualifiedName)
        assertEquals("public", info.visibility)

        // The declaration says `Result`; only a resolved signature says `svc.model.Result`.
        assertEquals("svc.model.Result", info.returnType)
        assertEquals(
            "Parameter types must be expanded to qualified names, not echoed as written",
            listOf("svc.model.Request"),
            info.parameters?.map { it.type }
        )
        assertEquals(listOf("request"), info.parameters?.map { it.name })

        assertTrue(
            "Signature should carry the qualified types too; got '${info.signature}'",
            info.signature.contains("svc.model.Result") && info.signature.contains("svc.model.Request")
        )
    }

    fun testOverloadsAreAddressedByPosition() {
        Assume.assumeTrue("Java plugin required for this fixture", PluginDetectors.java.isAvailable)
        writeServiceFixture()

        val single = symbolInfoAt(line = 10, column = 19)
        // Line 14 is the two-argument overload.
        val overload = symbolInfoAt(line = 14, column = 19)

        assertEquals("single-argument overload", 1, single.parameters!!.size)
        assertEquals("two-argument overload", 2, overload.parameters!!.size)
        assertEquals(
            "The second parameter's resolved type distinguishes the overloads",
            listOf("svc.model.Request", "boolean"),
            overload.parameters?.map { it.type }
        )
    }

    fun testDocCommentIsReturnedAsPlainText() {
        Assume.assumeTrue("Java plugin required for this fixture", PluginDetectors.java.isAvailable)
        writeServiceFixture()

        val info = symbolInfoAt(line = 10, column = 19)

        val documentation = info.documentation
        assertNotNull("The Javadoc on handle() should be rendered", documentation)
        assertTrue(
            "Documentation should carry the comment's prose; got '$documentation'",
            documentation!!.contains("Handles one request")
        )
        assertFalse(
            "Documentation should be plain text, not HTML; got '$documentation'",
            documentation.contains("<html") || documentation.contains("<div")
        )
        assertFalse("A short Javadoc is not truncated", info.documentationTruncated)
    }

    fun testIncludeDocFalseSuppressesDocumentation() {
        Assume.assumeTrue("Java plugin required for this fixture", PluginDetectors.java.isAvailable)
        writeServiceFixture()

        val info = symbolInfoAt(line = 10, column = 19, extra = mapOf("includeDoc" to false))

        assertNull("includeDoc=false must suppress the doc comment", info.documentation)
        // The signature is unaffected — suppressing docs must not degrade the rest of the result.
        assertEquals("svc.model.Result", info.returnType)
    }

    fun testClassDeclarationReportsQualifiedNameAndSignature() {
        Assume.assumeTrue("Java plugin required for this fixture", PluginDetectors.java.isAvailable)
        writeServiceFixture()

        // Line 6 is `public class Service {`; column 16 is inside `Service`.
        val info = symbolInfoAt(line = 6, column = 16)

        assertEquals("Service", info.name)
        assertEquals("svc.Service", info.qualifiedName)
        assertEquals(SignatureSources.JAVA_PSI, info.signatureSource)
        assertTrue(
            "A class signature should name the class; got '${info.signature}'",
            info.signature.contains("class svc.Service")
        )
        assertEquals(listOf("public"), info.modifiers)
    }

    fun testFieldTypeIsQualifiedAndDeclarationLocationIsReported() {
        Assume.assumeTrue("Java plugin required for this fixture", PluginDetectors.java.isAvailable)
        registerSourceRoot("src")
        writeProjectFile(
            "src/svc/model/Request.java",
            """
                package svc.model;

                public class Request {
                }
            """.trimIndent()
        )
        writeProjectFile(
            "src/svc/Holder.java",
            """
                package svc;

                import svc.model.Request;

                public class Holder {
                    private static final Request PENDING = null;
                }
            """.trimIndent()
        )

        val result = runBlocking {
            SymbolInfoTool().execute(project, buildJsonObject {
                put("file", "src/svc/Holder.java")
                put("line", 6)
                put("column", 34)
            })
        }
        assertToolSucceeded("ide_symbol_info should succeed on a field", result)
        val info = json.decodeFromString<SymbolInfoResult>(toolText(result))

        assertEquals("PENDING", info.name)
        assertEquals("svc.model.Request", info.returnType)
        assertEquals("private", info.visibility)
        assertEquals(listOf("private", "static", "final"), info.modifiers)
        assertEquals("src/svc/Holder.java", info.file)
        assertEquals("The reported line must be the declaration's own line", 6, info.line!!.toInt())
    }

    fun testMissingTargetArgumentsAreRejected() = runBlocking {
        val result = SymbolInfoTool().execute(project, buildJsonObject { })

        assertToolFailed("Neither position nor symbol was given", result)
    }
}
