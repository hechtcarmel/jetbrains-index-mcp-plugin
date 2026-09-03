package com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.ruby

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.LanguageHandlerRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.ruby.RubyStructureHandler.Companion.deriveMethodSignatureFromText
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.ruby.RubyStructureHandler.Companion.deriveSelfModifier
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PluginDetector
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PluginDetectors
import com.intellij.psi.PsiElement
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import junit.framework.TestCase

/**
 * Pure (non-fixture) unit tests for [RubyStructureHandler].
 *
 * These call the REAL handler helpers ([RubyStructureHandler.deriveMethodSignatureFromText],
 * [RubyStructureHandler.deriveSelfModifier]) and the REAL protected
 * [BaseRubyHandler.reconstructFqn] via a minimal probe subclass — no local
 * reimplementations, so production regressions fail these tests. No Ruby plugin,
 * no PSI, no IDE.
 *
 * **What cannot be tested here** (needs Ruby plugin):
 *   - Reflection calls to RClass.getStructureElements()
 *   - PsiTreeUtil.findChildrenOfType with Ruby PSI classes
 *   - Actual file structure extraction from parsed Ruby files
 *
 * See [RubyStructureHandlerPlatformTest] for those.
 */
class RubyStructureHandlerUnitTest : TestCase() {

    override fun setUp() {
        super.setUp()
        LanguageHandlerRegistry.clear()
    }

    override fun tearDown() {
        super.tearDown()
        LanguageHandlerRegistry.clear()
    }

    // ── Method signature derivation (real handler logic) ──────────────────────

    fun testMethodSignatureWithParameters() {
        val text = "  def add(x, y)\n    x + y\n  end"
        assertEquals("(x, y)", deriveMethodSignatureFromText(text))
    }

    fun testMethodSignatureWithNoParams() {
        val text = "  def greet()\n    \"hello\"\n  end"
        assertEquals("()", deriveMethodSignatureFromText(text))
    }

    fun testMethodSignatureWithDefaultValue() {
        val text = "  def greet(name = \"world\")\n    \"hello #{name}\"\n  end"
        assertEquals("(name = \"world\")", deriveMethodSignatureFromText(text))
    }

    fun testMethodSignatureWithSplat() {
        val text = "  def sum(*args)\n    args.sum\n  end"
        assertEquals("(*args)", deriveMethodSignatureFromText(text))
    }

    fun testMethodSignatureWithKeywordArg() {
        val text = "  def configure(env:, debug: false)\n  end"
        assertEquals("(env:, debug: false)", deriveMethodSignatureFromText(text))
    }

    fun testMethodSignatureWithBlockArg() {
        val text = "  def process(&block)\n    block.call\n  end"
        assertEquals("(&block)", deriveMethodSignatureFromText(text))
    }

    fun testMethodSignatureClassMethod() {
        val text = "  def self.square(x)\n    x * x\n  end"
        assertEquals("(x)", deriveMethodSignatureFromText(text))
    }

    fun testMethodSignatureComplexDefault() {
        val text = "  def process(name: nil, timeout: DEFAULT_TIMEOUT, retries: 3)\n  end"
        assertEquals("(name: nil, timeout: DEFAULT_TIMEOUT, retries: 3)", deriveMethodSignatureFromText(text))
    }

    // ── Paren-less fallback branch (regression targets for the missed branch) ──
    // These previously asserted `null` against a partial copy of the pattern.
    // The real handler returns "()" for paren-less definitions via the fallback.

    fun testMethodSignatureNoParensReturnsEmptyParens() {
        val text = "  def compute\n    add(1, 2)\n  end"
        assertEquals("Paren-less def should fall back to '()'", "()", deriveMethodSignatureFromText(text))
    }

    fun testMethodSignaturePredicateMethodReturnsEmptyParens() {
        val text = "  def admin?\n    true\n  end"
        assertEquals("Predicate def should fall back to '()'", "()", deriveMethodSignatureFromText(text))
    }

    fun testMethodSignatureBangMethodReturnsEmptyParens() {
        val text = "  def save!\n    true\n  end"
        assertEquals("Bang def should fall back to '()'", "()", deriveMethodSignatureFromText(text))
    }

    fun testMethodSignatureOneLineBodyReturnsNull() {
        // First line is `def method_one; end` — the name is followed by `; end`
        // on the same line, so the paren-less fallback does NOT match → null.
        val text = "  def method_one; end"
        assertNull("One-line body should yield null (no trailing EOL after name)",
            deriveMethodSignatureFromText(text))
    }

    // ── self modifier detection (real handler logic) ──────────────────────────

    fun testClassMethodHasSelfModifier() {
        assertEquals(listOf("self"), deriveSelfModifier("def self.square(x)"))
    }

    fun testInstanceMethodHasNoSelfModifier() {
        assertTrue(deriveSelfModifier("def add(x, y)").isEmpty())
    }

    fun testClassMethodWithPredicate() {
        assertEquals(listOf("self"), deriveSelfModifier("def self.admin?"))
    }

    // ── FQN reconstruction (real protected BaseRubyHandler method) ─────────────

    /** Minimal probe exposing the protected [BaseRubyHandler.reconstructFqn]. */
    private class ReconstructProbe : BaseRubyHandler<Unit>() {
        override val languageId = "Ruby"
        override fun canHandle(element: PsiElement): Boolean = false
        override fun isAvailable(): Boolean = false
        fun reconstruct(name: String, ancestors: List<String>): String = reconstructFqn(name, ancestors)
    }

    private val probe = ReconstructProbe()

    fun testReconstructFqnReturnsNameWhenNoAncestors() {
        assertEquals("User", probe.reconstruct("User", emptyList()))
    }

    fun testReconstructFqnForNestedModule() {
        // innermost-first ancestor list [Inner, Outer] → Outer::Inner::Nested
        assertEquals("Outer::Inner::Nested", probe.reconstruct("Nested", listOf("Inner", "Outer")))
    }

    fun testReconstructFqnWithEmptyName() {
        assertEquals("Outer", probe.reconstruct("", listOf("Outer")))
    }

    fun testReconstructFqnBothEmpty() {
        assertEquals("", probe.reconstruct("", emptyList()))
    }

    // ── Metadata ──────────────────────────────────────────────────────────────

    fun testLanguageIdIsRuby() {
        assertEquals("languageId must be 'Ruby'", "Ruby", RubyStructureHandler().languageId)
    }

    fun testIsAvailableWithoutPlugin() {
        mockkObject(PluginDetectors)
        val rubyDetector = mockk<PluginDetector>()
        every { rubyDetector.isAvailable } returns false
        every { PluginDetectors.ruby } returns rubyDetector
        try {
            assertFalse("isAvailable should be false without Ruby plugin", RubyStructureHandler().isAvailable())
        } finally {
            unmockkObject(PluginDetectors)
        }
    }

    fun testCanHandleReturnsFalseWithoutRubyPlugin() {
        mockkObject(PluginDetectors)
        val rubyDetector = mockk<PluginDetector>()
        every { rubyDetector.isAvailable } returns false
        every { PluginDetectors.ruby } returns rubyDetector
        try {
            assertFalse("canHandle must be false without Ruby plugin",
                RubyStructureHandler().canHandle(mockk()))
        } finally {
            unmockkObject(PluginDetectors)
        }
    }

    fun testCanHandleRejectsNonRubyLanguage() {
        mockkObject(PluginDetectors)
        val rubyDetector = mockk<PluginDetector>()
        every { rubyDetector.isAvailable } returns true
        every { PluginDetectors.ruby } returns rubyDetector
        try {
            val element = mockk<PsiElement> {
                every { language } returns mockk { every { id } returns "kotlin" }
            }
            assertFalse("canHandle must return false for non-Ruby element",
                RubyStructureHandler().canHandle(element))
        } finally {
            unmockkObject(PluginDetectors)
        }
    }

    fun testCanHandleReturnsTrueForRubyLanguageElement() {
        mockkObject(PluginDetectors)
        val rubyDetector = mockk<PluginDetector>()
        every { rubyDetector.isAvailable } returns true
        every { PluginDetectors.ruby } returns rubyDetector
        try {
            val element = mockk<PsiElement> {
                every { language } returns mockk { every { id } returns "ruby" }
            }
            assertTrue("canHandle must return true for Ruby element with plugin available",
                RubyStructureHandler().canHandle(element))
        } finally {
            unmockkObject(PluginDetectors)
        }
    }

    // ── Registry wiring ───────────────────────────────────────────────────────

    fun testRegistryExposesRubyStructureWhenForcedAvailable() {
        mockkObject(PluginDetectors)
        val rubyDetector = mockk<PluginDetector>()
        every { rubyDetector.isAvailable } returns true
        every { PluginDetectors.ruby } returns rubyDetector
        LanguageHandlerRegistry.clear()
        try {
            LanguageHandlerRegistry.registerStructureHandler(RubyStructureHandler())
            assertTrue(
                "'Ruby' must be advertised as a supported structure language",
                LanguageHandlerRegistry.getSupportedLanguagesForStructure().contains("Ruby")
            )
        } finally {
            LanguageHandlerRegistry.clear()
            unmockkObject(PluginDetectors)
        }
    }
}
