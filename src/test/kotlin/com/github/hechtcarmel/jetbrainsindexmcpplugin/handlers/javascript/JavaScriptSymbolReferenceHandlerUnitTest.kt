package com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.javascript

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ErrorMessages
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiNamedElement
import junit.framework.TestCase
import java.lang.reflect.Proxy

class JavaScriptSymbolReferenceHandlerUnitTest : TestCase() {

    private fun fakeNamedElement(): PsiNamedElement {
        return Proxy.newProxyInstance(
            PsiNamedElement::class.java.classLoader,
            arrayOf(PsiNamedElement::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "getName" -> "format"
                "toString" -> "FakePsiNamedElement(format)"
                "isValid" -> true
                "isWritable" -> false
                else -> when (method.returnType) {
                    Boolean::class.javaPrimitiveType -> false
                    Int::class.javaPrimitiveType -> 0
                    Long::class.javaPrimitiveType -> 0L
                    Float::class.javaPrimitiveType -> 0f
                    Double::class.javaPrimitiveType -> 0.0
                    Char::class.javaPrimitiveType -> '\u0000'
                    Byte::class.javaPrimitiveType -> 0.toByte()
                    Short::class.javaPrimitiveType -> 0.toShort()
                    else -> null
                }
            }
        } as PsiNamedElement
    }

    private fun fakeProject(): Project {
        return Proxy.newProxyInstance(
            Project::class.java.classLoader,
            arrayOf(Project::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "getName" -> "test"
                "isDisposed" -> false
                else -> when (method.returnType) {
                    Boolean::class.javaPrimitiveType -> false
                    Int::class.javaPrimitiveType -> 0
                    Long::class.javaPrimitiveType -> 0L
                    else -> null
                }
            }
        } as Project
    }

    fun testParserAcceptsNamedExport() {
        val parsed = parseJsTsSymbolTarget("src/utils#formatDate").getOrThrow()
        assertEquals(JsTsSymbolTarget.NamedExport("src/utils", "formatDate"), parsed)
    }

    fun testParserAcceptsDefaultExport() {
        val parsed = parseJsTsSymbolTarget("./App#default").getOrThrow()
        assertEquals(JsTsSymbolTarget.DefaultExport("./App"), parsed)
    }

    fun testParserAcceptsClassMember() {
        val parsed = parseJsTsSymbolTarget("src/domain/User#User.fullName").getOrThrow()
        assertEquals(JsTsSymbolTarget.ClassMember("src/domain/User", "User", "fullName"), parsed)
    }

    fun testParserRejectsUnsupportedGrammarDeterministically() {
        val symbol = "src/utils.formatDate"
        val error = parseJsTsSymbolTarget(symbol).exceptionOrNull()?.message

        assertEquals(ErrorMessages.jsTsUnsupportedGrammar(symbol), error)
        assertTrue(error?.startsWith("unsupported_grammar:") == true)
        assertTrue(error?.contains("modulePath#exportName") == true)
        assertTrue(error?.contains("modulePath#default") == true)
        assertTrue(error?.contains("modulePath#ClassName.memberName") == true)
    }

    fun testParserRejectsDoubleHashDeterministically() {
        val symbol = "src/utils#format#Date"
        val error = parseJsTsSymbolTarget(symbol).exceptionOrNull()?.message
        assertEquals(ErrorMessages.jsTsUnsupportedGrammar(symbol), error)
    }

    fun testParserRejectsInvalidMemberGrammarDeterministically() {
        val symbol = "src/utils#MyClass.member.nested"
        val error = parseJsTsSymbolTarget(symbol).exceptionOrNull()?.message
        assertEquals(ErrorMessages.jsTsUnsupportedGrammar(symbol), error)
    }

    fun testParserRejectsWhitespaceStrictly() {
        val symbol = " src/utils#formatDate "
        val error = parseJsTsSymbolTarget(symbol).exceptionOrNull()?.message
        assertEquals(ErrorMessages.jsTsUnsupportedGrammar(symbol), error)
    }

    fun testDeterministicNotFoundMessage() {
        val symbol = "src/missing#missingExport"
        val error = ErrorMessages.jsTsNotFound(symbol)
        assertEquals("not_found: No declaration found for symbol '$symbol'", error)
    }

    fun testDeterministicAmbiguousMessage() {
        val symbol = "src/utils#format"
        val candidates = listOf("src/utils.ts#format", "src/utils/index.ts#format")
        val error = ErrorMessages.jsTsAmbiguousMatch(symbol, candidates)
        assertEquals(
            "ambiguous_match: Multiple declarations match symbol '$symbol'. Candidates: src/utils.ts#format, src/utils/index.ts#format",
            error
        )
    }

    fun testDeterministicUnsupportedCapabilityMessage() {
        val reason = "JavaScript plugin not installed"
        val error = ErrorMessages.jsTsUnsupportedLanguageCapability(reason)
        assertEquals(
            "unsupported_language_capability: JavaScript/TypeScript symbol resolution is not available in this IDE session. Reason: $reason",
            error
        )
    }

    fun testModuleCandidateExpansionWithoutExtensionIsDeterministic() {
        val candidates = expandJsTsModuleCandidates("src/utils/date").map { it.relativePath }
        assertEquals(
            listOf(
                "src/utils/date.ts",
                "src/utils/date.tsx",
                "src/utils/date.js",
                "src/utils/date.jsx",
                "src/utils/date.mjs",
                "src/utils/date.cjs",
                "src/utils/date/index.ts",
                "src/utils/date/index.tsx",
                "src/utils/date/index.js",
                "src/utils/date/index.jsx",
                "src/utils/date/index.mjs",
                "src/utils/date/index.cjs"
            ),
            candidates
        )
    }

    fun testModuleCandidateExpansionWithExplicitExtensionReturnsSingleCandidate() {
        val candidates = expandJsTsModuleCandidates("src/utils/date.ts").map { it.relativePath }
        assertEquals(listOf("src/utils/date.ts"), candidates)
    }

    fun testModuleCandidateExpansionExcludesDts() {
        val candidates = expandJsTsModuleCandidates("src/types").map { it.relativePath }
        assertFalse(candidates.any { it.endsWith(".d.ts") })
    }

    fun testDeterministicSingleMatchReturnsNotFoundForZero() {
        val symbol = "src/missing#foo"
        val error = deterministicSingleMatchOrFailure(symbol, emptyList()).exceptionOrNull()?.message
        assertEquals(ErrorMessages.jsTsNotFound(symbol), error)
    }

    fun testDeterministicSingleMatchReturnsAmbiguousForMultiple() {
        val symbol = "src/utils#format"
        val one = fakeNamedElement()
        val two = fakeNamedElement()
        val error = deterministicSingleMatchOrFailure(
            symbol,
            listOf("src/utils.ts" to one, "src/utils/index.ts" to two)
        ).exceptionOrNull()?.message
        assertEquals(
            ErrorMessages.jsTsAmbiguousMatch(symbol, listOf("src/utils.ts", "src/utils/index.ts")),
            error
        )
    }

    fun testUnsupportedCapabilitySeamIsDeterministicWithoutPlatformDependency() {
        val handler = JavaScriptSymbolReferenceHandler(classLookup = { null })
        val error = handler.resolveSymbol(fakeProject(), "src/utils#formatDate").exceptionOrNull()?.message
        assertEquals(
            ErrorMessages.jsTsUnsupportedLanguageCapability("JavaScript PSI classes are unavailable"),
            error
        )
    }
}
