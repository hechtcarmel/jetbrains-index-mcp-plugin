package com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.javascript

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ErrorMessages
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiNamedElement
import junit.framework.TestCase
import java.lang.reflect.Proxy

class JavaScriptSymbolReferenceHandlerUnitTest : TestCase() {

    private companion object {
        const val FIXTURE_PROJECT_ROOT = "src/webstormIntegration"
        const val OVERLOADED_EXPORT_SYMBOL = "$FIXTURE_PROJECT_ROOT/overloads/overloaded-export#getProjectId"
        const val NAMED_BARREL_SYMBOL = "$FIXTURE_PROJECT_ROOT/barrels/named-barrel#loadPluginConfig"
        const val EXPORT_STAR_BARREL_SYMBOL = "$FIXTURE_PROJECT_ROOT/barrels/export-star-barrel#loadPluginConfig"
        const val TYPE_ALIAS_SYMBOL = "$FIXTURE_PROJECT_ROOT/types/type-alias-vs-interface#FileStructureAlias"
        const val DERIVED_TYPE_SYMBOL = "$FIXTURE_PROJECT_ROOT/derived/const-derived-types#formatThothStatus"
    }

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

    fun testParserAcceptsOverloadFixtureSymbol() {
        val parsed = parseJsTsSymbolTarget(OVERLOADED_EXPORT_SYMBOL).getOrThrow()
        assertEquals(
            JsTsSymbolTarget.NamedExport("$FIXTURE_PROJECT_ROOT/overloads/overloaded-export", "getProjectId"),
            parsed
        )
    }

    fun testParserAcceptsNamedBarrelFixtureSymbol() {
        val parsed = parseJsTsSymbolTarget(NAMED_BARREL_SYMBOL).getOrThrow()
        assertEquals(
            JsTsSymbolTarget.NamedExport("$FIXTURE_PROJECT_ROOT/barrels/named-barrel", "loadPluginConfig"),
            parsed
        )
    }

    fun testParserAcceptsExportStarBarrelFixtureSymbol() {
        val parsed = parseJsTsSymbolTarget(EXPORT_STAR_BARREL_SYMBOL).getOrThrow()
        assertEquals(
            JsTsSymbolTarget.NamedExport("$FIXTURE_PROJECT_ROOT/barrels/export-star-barrel", "loadPluginConfig"),
            parsed
        )
    }

    fun testParserAcceptsTypeAliasFixtureSymbol() {
        val parsed = parseJsTsSymbolTarget(TYPE_ALIAS_SYMBOL).getOrThrow()
        assertEquals(
            JsTsSymbolTarget.NamedExport("$FIXTURE_PROJECT_ROOT/types/type-alias-vs-interface", "FileStructureAlias"),
            parsed
        )
    }

    fun testParserAcceptsDerivedTypeFixtureSymbol() {
        val parsed = parseJsTsSymbolTarget(DERIVED_TYPE_SYMBOL).getOrThrow()
        assertEquals(
            JsTsSymbolTarget.NamedExport("$FIXTURE_PROJECT_ROOT/derived/const-derived-types", "formatThothStatus"),
            parsed
        )
    }

    fun testUnsupportedGrammarCoverageHookForOverloadStyleDisambiguation() {
        val symbol = "$FIXTURE_PROJECT_ROOT/overloads/overloaded-export#getProjectId(string)"
        val error = parseJsTsSymbolTarget(symbol).exceptionOrNull()?.message

        assertEquals(ErrorMessages.jsTsUnsupportedGrammar(symbol), error)
        assertTrue(error?.startsWith("unsupported_grammar:") == true)
        assertTrue(error?.contains("modulePath#exportName") == true)
        // Coverage hook for Phase 5.1: unsupported overload-disambiguation grammar must keep pointing
        // callers toward supported forms until file+line+column fallback guidance is tightened.
    }

    fun testDeterministicNotFoundMessage() {
        val symbol = "src/missing#missingExport"
        val error = ErrorMessages.jsTsNotFound(symbol)
        assertTrue(error.startsWith("not_found: No declaration found for symbol '$symbol'"))
        assertTrue(error.contains("use file+line+column instead"))
    }

    fun testDeterministicAmbiguousMessage() {
        val symbol = "src/utils#format"
        val candidates = listOf("src/utils.ts#format", "src/utils/index.ts#format")
        val error = ErrorMessages.jsTsAmbiguousMatch(symbol, candidates)
        assertTrue(error.startsWith("ambiguous_match: Multiple declarations match symbol '$symbol'. Candidates: "))
        assertTrue(error.contains(candidates.joinToString(", ")))
        assertTrue(error.contains("use file+line+column instead"))
    }

    fun testDeterministicUnsupportedCapabilityMessage() {
        val reason = "JavaScript plugin not installed"
        val error = ErrorMessages.jsTsUnsupportedLanguageCapability(reason)
        assertTrue(
            error.startsWith(
                "unsupported_language_capability: JavaScript/TypeScript symbol resolution is not available in this IDE session. Reason: $reason"
            )
        )
        assertTrue(error.contains("Use file+line+column instead"))
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

    fun testImplementationBodyHeuristicRejectsObjectLiteralReturnTypeOverloadSignature() {
        val source = "export function getProjectId(input: string): { id: string };"

        assertFalse(hasImplementationBodyByText(source))
    }

    fun testImplementationBodyHeuristicAcceptsObjectLiteralReturnTypeImplementation() {
        val source = """
            export function getProjectId(input: string): { id: string } {
              return { id: input };
            }
        """.trimIndent()

        assertTrue(hasImplementationBodyByText(source))
    }
}
