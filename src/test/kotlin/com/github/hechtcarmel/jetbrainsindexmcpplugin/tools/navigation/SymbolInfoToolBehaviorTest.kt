package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.LanguageHandlerRegistry
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

    override fun setUp() {
        super.setUp()
        LanguageHandlerRegistry.registerHandlers()
    }

    override fun tearDown() {
        try {
            LanguageHandlerRegistry.clear()
        } finally {
            super.tearDown()
        }
    }

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

    /** Reads `Verbose.documented`, optionally capping the documentation budget. */
    private suspend fun verboseDocInfo(maxDocLength: Int?): SymbolInfoResult {
        val result = SymbolInfoTool().execute(project, buildJsonObject {
            put("file", "src/svc/Verbose.java")
            put("line", 7)
            put("column", 17)
            if (maxDocLength != null) put("maxDocLength", maxDocLength)
        })
        assertToolSucceeded("ide_symbol_info should succeed on Verbose.documented", result)
        return json.decodeFromString<SymbolInfoResult>(toolText(result))
    }

    fun testMethodParameterAndReturnTypesAreQualified() {
        Assume.assumeTrue("Java plugin required for this fixture", PluginDetectors.java.isAvailable)
        writeServiceFixture()

        // Line 10 is `public Result handle(Request request) {`; column 19 is inside `handle`.
        val info = symbolInfoAt(line = 10, column = 19)

        assertEquals("handle", info.name)
        assertEquals(SignatureSources.JAVA_PSI, info.signatureSource)
        assertEquals("svc.Service", info.containingDeclaration)
        assertEquals("svc.Service#handle(svc.model.Request)", info.qualifiedName)
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

    fun testEmittedQualifiedNameResolvesBackToTheSameOverload() = runBlocking {
        Assume.assumeTrue("Java plugin required for this fixture", PluginDetectors.java.isAvailable)
        writeServiceFixture()

        // The two-argument overload, addressed by position.
        val byPosition = symbolInfoAt(line = 14, column = 19)
        val emitted = byPosition.qualifiedName
        assertNotNull("A method must report a qualified name to chain from", emitted)

        // The documented contract: feed that value straight back as `symbol`. The bare
        // `svc.Service#handle` form fails here with "Multiple methods match", so this assertion
        // is what keeps the parameter list in the emitted value.
        val result = SymbolInfoTool().execute(project, buildJsonObject {
            put("language", "Java")
            put("symbol", emitted!!)
        })
        assertToolSucceeded("The emitted qualifiedName must resolve as a symbol; got '$emitted'", result)

        val bySymbol = json.decodeFromString<SymbolInfoResult>(toolText(result))
        assertEquals("Round-trip must land on the same overload", 2, bySymbol.parameters!!.size)
        assertEquals(
            listOf("svc.model.Request", "boolean"),
            bySymbol.parameters?.map { it.type }
        )
        assertEquals(byPosition.signature, bySymbol.signature)
    }

    fun testLongDocumentationIsTruncatedAtMaxDocLength() = runBlocking {
        Assume.assumeTrue("Java plugin required for this fixture", PluginDetectors.java.isAvailable)
        registerSourceRoot("src")
        writeProjectFile(
            "src/svc/Verbose.java",
            """
                package svc;

                public class Verbose {
                    /**
                     * ${"Sentence one describing the behaviour in detail. ".repeat(20)}
                     */
                    public void documented() {
                    }
                }
            """.trimIndent()
        )

        val truncated = verboseDocInfo(60)
        assertTrue("A doc longer than maxDocLength must set documentationTruncated", truncated.documentationTruncated)
        val text = truncated.documentation
        assertNotNull("Truncated documentation is still returned, not dropped", text)
        assertTrue(
            "Truncated documentation should be marked as such; got '$text'",
            text!!.endsWith("(documentation truncated)")
        )
        assertTrue(
            "Truncation must respect the requested budget; got ${text.length} chars",
            text.length <= 60 + "\n… (documentation truncated)".length
        )

        // Control: the same symbol under the default budget is not truncated, so an
        // always-truncate regression cannot pass.
        val untruncated = verboseDocInfo(null)
        assertFalse("The default budget must not truncate this doc", untruncated.documentationTruncated)
        assertTrue(
            "The untruncated doc must be longer than the capped one",
            (untruncated.documentation?.length ?: 0) > text.length
        )
    }

    fun testMissingTargetArgumentsAreRejected() = runBlocking {
        val result = SymbolInfoTool().execute(project, buildJsonObject { })

        assertToolFailed("Neither position nor symbol was given", result)
    }
}
