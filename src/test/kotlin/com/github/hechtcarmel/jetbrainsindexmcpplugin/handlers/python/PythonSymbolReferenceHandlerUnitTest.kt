package com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.python

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.LanguageHandlerRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.python.PythonSymbolReferenceHandler.Companion.PYTHON_SYMBOL_PATTERN
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PluginDetector
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PluginDetectors
import junit.framework.TestCase

/**
 * Pure (non-fixture) unit tests for [PythonSymbolReferenceHandler].
 *
 * Exercises symbol-format validation and the resolution dispatch logic by injecting fake index
 * lookups, so it runs without a real Python stub index.
 *
 * The fakes are duck-typed against [QualifiedNamedElement], declared here in the test's own
 * package, because [BasePythonHandler.getQualifiedName] resolves the qualified name reflectively
 * (`element.javaClass.getMethod("getQualifiedName")`) — a bare [PsiNamedElement] mock has no such
 * method. Production imports nothing from `com.jetbrains.python`; it is reflection all the way
 * down, so nothing here needs PyCharm's real types.
 *
 * Deliberately NOT declared under `com.jetbrains.python.psi`: [PluginDetectors] probes for
 * `com.jetbrains.python.psi.PyClass` via `Class.forName`, so a test-tree class at that FQN makes
 * `PluginDetectors.python.isAvailable` return true in every test and silently registers Python
 * handlers into a half-stubbed world no shipped IDE can reach.
 * [PluginDetectorLeakUnitTest] guards against that regression.
 */
class PythonSymbolReferenceHandlerUnitTest : TestCase() {

    /** Minimal shape [BasePythonHandler]'s reflective qualified-name lookup requires. */
    private interface QualifiedNamedElement : PsiNamedElement {
        fun getQualifiedName(): String?
    }

    private val project: Project = mockk(relaxed = true)

    private fun mockPyFunction(qName: String, offset: Int = 0): PsiNamedElement =
        mockQualifiedElement(qName, offset)

    private fun mockPyClass(qName: String, offset: Int = 0): PsiNamedElement =
        mockQualifiedElement(qName, offset)

    private fun mockQualifiedElement(qName: String, offset: Int): PsiNamedElement =
        mockk<QualifiedNamedElement>(relaxed = true) {
            every { getName() } returns qName.substringAfterLast('.')
            every { getQualifiedName() } returns qName
            every { textOffset } returns offset
            every { containingFile } returns null
            every { parent } returns null
        }

    private fun handler(
        classes: Map<String, PsiNamedElement> = emptyMap(),
        functions: Map<String, PsiNamedElement> = emptyMap(),
        attributes: Map<String, PsiNamedElement> = emptyMap()
    ): PythonSymbolReferenceHandler {
        val classLookup: (String, Project) -> List<PsiNamedElement> = { qName, _ ->
            classes[qName]?.let { listOf(it) } ?: emptyList()
        }
        val functionLookup: (String, Project) -> List<PsiNamedElement> = { qName, _ ->
            functions[qName]?.let { listOf(it) } ?: emptyList()
        }
        val attrLookup: (PsiElement, String) -> PsiNamedElement? = { _, name -> attributes[name] }
        return PythonSymbolReferenceHandler(classLookup, functionLookup, attrLookup)
    }

    // ── PYTHON_SYMBOL_PATTERN: valid symbols ───────────────────────────────────

    fun testPatternMatchesClassFqn() = assertTrue(PYTHON_SYMBOL_PATTERN.matches("lib.util.MyClass"))

    fun testPatternMatchesModuleFunction() = assertTrue(PYTHON_SYMBOL_PATTERN.matches("lib.util.to_iso"))

    fun testPatternMatchesMethodDotted() = assertTrue(PYTHON_SYMBOL_PATTERN.matches("lib.util.MyClass.method"))

    fun testPatternMatchesHashMember() = assertTrue(PYTHON_SYMBOL_PATTERN.matches("lib.util.MyClass#attr"))

    fun testPatternMatchesSinglePackageSegment() = assertTrue(PYTHON_SYMBOL_PATTERN.matches("util.to_iso"))

    fun testPatternMatchesUnderscores() = assertTrue(PYTHON_SYMBOL_PATTERN.matches("lib.util._private_fn"))

    // ── PYTHON_SYMBOL_PATTERN: invalid symbols ─────────────────────────────────

    fun testPatternRejectsBareName() = assertFalse(PYTHON_SYMBOL_PATTERN.matches("to_iso"))

    fun testPatternRejectsParameterList() = assertFalse(PYTHON_SYMBOL_PATTERN.matches("lib.util.fn(int)"))

    fun testPatternRejectsEmptyMemberAfterHash() = assertFalse(PYTHON_SYMBOL_PATTERN.matches("lib.util.MyClass#"))

    fun testPatternRejectsDottedMemberAfterHash() = assertFalse(PYTHON_SYMBOL_PATTERN.matches("lib.util.MyClass#a.b"))

    fun testPatternRejectsTrailingDot() = assertFalse(PYTHON_SYMBOL_PATTERN.matches("lib.util."))

    // ── resolveSymbol: format validation ───────────────────────────────────────

    fun testResolveRejectsEmptySymbol() {
        val r = handler().resolveSymbol(project, "")
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull()!!.message!!.contains("does not match expected format"))
    }

    fun testResolveRejectsBareName() {
        val r = handler().resolveSymbol(project, "to_iso")
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull()!!.message!!.contains("does not match expected format"))
    }

    fun testResolveRejectsParameterList() {
        val r = handler().resolveSymbol(project, "lib.util.fn(int, str)")
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull()!!.message!!.contains("does not match expected format"))
    }

    // ── resolveSymbol: dotted qualified-name path ─────────────────────────────

    fun testResolveFunctionByQualifiedName() {
        val fn = mockPyFunction("lib.util.to_iso")
        val r = handler(functions = mapOf("lib.util.to_iso" to fn)).resolveSymbol(project, "lib.util.to_iso")
        assertTrue(r.isSuccess)
        assertSame(fn, r.getOrThrow())
    }

    fun testResolveFunctionDoesNotMatchSameShortNameDifferentModule() {
        // The function lookup is by fully-qualified name; a function with the same short name in a different
        // module must not satisfy a request for a different qualified path. (The reflective default filters by
        // qName inside the index call; this test asserts the dispatch contract with the injected fake.)
        val other = mockPyFunction("lib.data.influx.to_iso")
        val r = handler(functions = mapOf("lib.data.influx.to_iso" to other))
            .resolveSymbol(project, "lib.util.to_iso")
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull()!!.message!!.contains("not found"))
    }

    fun testResolveFunctionPicksProjectHitOverDependencyWithSameQName() {
        // If a project function and a dependency function share the SAME qualified name, the project-scope-first
        // ordering must surface the project one. The injected fake models the project hit.
        val projectFn = mockPyFunction("lib.util.parse")
        val r = handler(functions = mapOf("lib.util.parse" to projectFn)).resolveSymbol(project, "lib.util.parse")
        assertTrue(r.isSuccess)
        assertSame(projectFn, r.getOrThrow())
    }

    fun testResolveClassByQualifiedName() {
        val cls = mockPyClass("lib.util.MyClass")
        val r = handler(classes = mapOf("lib.util.MyClass" to cls)).resolveSymbol(project, "lib.util.MyClass")
        assertTrue(r.isSuccess)
        assertSame(cls, r.getOrThrow())
    }

    fun testResolveMethodByDottedQualifiedName() {
        val method = mockPyFunction("lib.util.MyClass.method")
        val r = handler(functions = mapOf("lib.util.MyClass.method" to method))
            .resolveSymbol(project, "lib.util.MyClass.method")
        assertTrue(r.isSuccess)
        assertSame(method, r.getOrThrow())
    }

    fun testResolveQualifiedNameNotFound() {
        val r = handler().resolveSymbol(project, "lib.util.no_such_thing")
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull()!!.message!!.contains("not found"))
    }

    fun testResolveAmbiguousClassAndFunctionShareQualifiedName() {
        // A module-level rebinding could make a class and a function collide on the same qName.
        val cls = mockPyClass("lib.util.dup", offset = 10)
        val fn = mockPyFunction("lib.util.dup", offset = 20)
        val r = handler(classes = mapOf("lib.util.dup" to cls), functions = mapOf("lib.util.dup" to fn))
            .resolveSymbol(project, "lib.util.dup")
        assertTrue(r.isFailure)
        val msg = r.exceptionOrNull()!!.message!!
        assertTrue("ambiguity message should list multiple matches: $msg", msg.contains("Multiple"))
    }

    // ── resolveSymbol: hash member path ────────────────────────────────────────

    fun testResolveHashMemberMethodNotFoundOnMockClass() {
        // Method resolution now goes through findMethodInClassInherited (inherited=true) which on a mock
        // throws NoSuchMethodException and falls back to multiFindMethodByName (also failing), then attribute /
        // property / nested-class lookups all yield null with nothing injected, so the handler reports
        // member-not-found rather than blowing up. Guards the failure contract.
        val cls = mockPyClass("lib.util.MyClass")
        val r = handler(classes = mapOf("lib.util.MyClass" to cls))
            .resolveSymbol(project, "lib.util.MyClass#method")
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull()!!.message!!.contains("No member 'method' found in type 'lib.util.MyClass'"))
    }

    fun testResolveHashMemberAttribute() {
        val cls = mockPyClass("lib.util.MyClass")
        val attr = mockPyFunction("lib.util.MyClass.constant")
        val r = handler(
            classes = mapOf("lib.util.MyClass" to cls),
            attributes = mapOf("constant" to attr)
        ).resolveSymbol(project, "lib.util.MyClass#constant")
        assertTrue(r.isSuccess)
        assertSame(attr, r.getOrThrow())
    }

    fun testResolveHashMemberClassNotFound() {
        val r = handler().resolveSymbol(project, "lib.util.NoClass#member")
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull()!!.message!!.contains("Type 'lib.util.NoClass' not found"))
    }

    fun testResolveHashMemberReportsContainerAmbiguity() {
        // Two classes share the container qName; the #member path must not silently pick one.
        val a = mockPyClass("lib.util.MyClass", offset = 10)
        val b = mockPyClass("lib.util.MyClass", offset = 20)
        val h = PythonSymbolReferenceHandler(
            findClassByQName = { _, _ -> listOf(a, b) },
            findFunctionsByQualifiedName = { _, _ -> emptyList() },
            findAttributeInClass = { _, _ -> null }
        )
        val r = h.resolveSymbol(project, "lib.util.MyClass#member")
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull()!!.message!!.contains("Multiple"))
    }

    // ── resolveSymbol: trimming ────────────────────────────────────────────────

    fun testResolveTrimsWhitespace() {
        val fn = mockPyFunction("lib.util.to_iso")
        val r = handler(functions = mapOf("lib.util.to_iso" to fn))
            .resolveSymbol(project, "  lib.util.to_iso  ")
        assertTrue(r.isSuccess)
        assertSame(fn, r.getOrThrow())
    }

    // ── reflective defaults: no-throw when Python index classes are absent ─────
    // CI has no Python plugin on the classpath, so the reflective defaults cannot
    // resolve real symbols. They MUST degrade to null/empty without throwing —
    // this guards that contract and exercises the default code paths.

    fun testDefaultLookupsReturnNullWithoutThrowingWhenPythonIndexUnavailable() {
        val h = PythonSymbolReferenceHandler() // uses reflective defaults
        val cls = h.resolveSymbol(project, "lib.util.SomeClass")
        // No Python plugin -> class lookup returns null -> typeNotFound failure (not an exception).
        assertTrue(cls.isFailure)
        assertNull(cls.getOrNull())
    }

    // ── registration wiring ────────────────────────────────────────────────────

    /**
     * The Python handler cannot be used to test this: `isAvailable()` is
     * `PluginDetectors.python.isAvailable && pyClassClass != null`, and `pyClassClass` resolves
     * `com.jetbrains.python.psi.PyClass` reflectively. Making it non-null requires a test class
     * at that FQN — which is exactly the [PluginDetectorLeakUnitTest] regression.
     *
     * The behavior under test is the registry's availability filter, which is language-agnostic,
     * so fake handlers exercise it directly and cover both branches instead of one.
     */
    fun testRegistryAdvertisesOnlyAvailableSymbolReferenceHandlers() {
        LanguageHandlerRegistry.clear()
        try {
            LanguageHandlerRegistry.registerSymbolReferenceHandler(FakeSymbolReferenceHandler("Available", available = true))
            LanguageHandlerRegistry.registerSymbolReferenceHandler(FakeSymbolReferenceHandler("Unavailable", available = false))

            assertEquals(
                "only handlers reporting isAvailable() should be advertised",
                listOf("Available"),
                LanguageHandlerRegistry.getSupportedLanguageNamesForSymbolReference()
            )
            assertNotNull(
                "an available handler must be retrievable by language name",
                LanguageHandlerRegistry.getSymbolReferenceHandlerByLanguageName("Available")
            )
            assertNull(
                "an unavailable handler must not be retrievable, even though it is registered",
                LanguageHandlerRegistry.getSymbolReferenceHandlerByLanguageName("Unavailable")
            )
            assertNotNull(
                "language name matching is case-insensitive",
                LanguageHandlerRegistry.getSymbolReferenceHandlerByLanguageName("available")
            )
        } finally {
            LanguageHandlerRegistry.clear()
        }
    }

    private class FakeSymbolReferenceHandler(
        override val languageName: String,
        private val available: Boolean
    ) : com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.SymbolReferenceHandler {
        override val languageId: String = languageName
        override fun isAvailable(): Boolean = available
        override fun canHandle(element: PsiElement): Boolean = false
        override fun resolveSymbol(project: Project, symbol: String): Result<PsiNamedElement> =
            Result.failure(UnsupportedOperationException("fake"))
    }
}
