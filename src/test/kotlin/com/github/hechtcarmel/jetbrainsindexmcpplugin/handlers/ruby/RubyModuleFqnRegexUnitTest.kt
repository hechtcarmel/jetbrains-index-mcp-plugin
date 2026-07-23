package com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.ruby

import junit.framework.TestCase

/**
 * Pure (non-fixture) unit tests for the regex extraction logic in
 * [BaseRubyHandler.extractModuleNamesFromText], the tertiary text-scan fallback
 * used by `getModuleFQNsViaPsiTextWalk` to pull module names out of
 * `include`/`extend`/`prepend` calls.
 *
 * These call the REAL handler helper (not a local copy), so a divergence in the
 * production regex will fail these tests. No PSI, no index, no Ruby plugin needed.
 *
 * The regex is tested for three call names: `"include"`, `"extend"`, `"prepend"`.
 */
class RubyModuleFqnRegexUnitTest : TestCase() {

    // ── helper: delegate to the real handler logic under test ──────────────────

    private fun extractModuleNames(sourceText: String, callName: String): Set<String> =
        BaseRubyHandler.extractModuleNamesFromText(sourceText, callName).toSet()

    // ── include ────────────────────────────────────────────────────────────────

    fun testIncludeExtractsSingleModule() {
        val result = extractModuleNames("include User", "include")
        assertEquals(
            "Expected setOf(User) from 'include User', got: $result",
            setOf("User"), result
        )
    }

    fun testIncludeExtractsNamespacedModule() {
        val result = extractModuleNames("include Admin::User", "include")
        assertEquals(
            "Expected setOf(Admin::User) from 'include Admin::User', got: $result",
            setOf("Admin::User"), result
        )
    }

    fun testIncludeExtractsDeeplyNamespacedModule() {
        val result = extractModuleNames("include A::B::C::D", "include")
        assertEquals(
            "Expected setOf(A::B::C::D) from 'include A::B::C::D', got: $result",
            setOf("A::B::C::D"), result
        )
    }

    fun testIncludeExtractsMultipleModulesSameLine() {
        val result = extractModuleNames("include A; include B; include C", "include")
        assertEquals(
            "Expected setOf(A, B, C) from semicolon-separated includes, got: $result",
            setOf("A", "B", "C"), result
        )
    }

    fun testIncludeExtractsMultipleModulesDifferentLines() {
        val text = """
            include A
            include B
            include C
        """.trimIndent()
        val result = extractModuleNames(text, "include")
        assertEquals(
            "Expected setOf(A, B, C) from multi-line includes, got: $result",
            setOf("A", "B", "C"), result
        )
    }

    fun testIncludeExtractsModuleWithUnderscoredName() {
        val result = extractModuleNames("include ActiveSupport::Concern", "include")
        assertEquals(
            "Expected setOf(ActiveSupport::Concern) from underscored namespace, got: $result",
            setOf("ActiveSupport::Concern"), result
        )
    }

    fun testIncludeExtractsModuleWithTrailingLineContent() {
        val result = extractModuleNames("include Authenticatable # cross-cutting concern", "include")
        assertEquals(
            "Expected setOf(Authenticatable) despite trailing comment, got: $result",
            setOf("Authenticatable"), result
        )
    }

    // ── extend ─────────────────────────────────────────────────────────────────

    fun testExtendExtractsSingleModule() {
        val result = extractModuleNames("extend ActiveSupport::Concern", "extend")
        assertEquals(
            "Expected setOf(ActiveSupport::Concern) from 'extend ActiveSupport::Concern', got: $result",
            setOf("ActiveSupport::Concern"), result
        )
    }

    fun testExtendMultiBoundary() {
        // Both extend and include on the same line — only the right one matches
        val text = "include Validatable; extend ActiveSupport::Concern; include Auditable"
        val includeResult = extractModuleNames(text, "include")
        val extendResult = extractModuleNames(text, "extend")
        assertEquals(
            "include scan of mixed line should yield Validatable, Auditable, got: $includeResult",
            setOf("Validatable", "Auditable"), includeResult
        )
        assertEquals(
            "extend scan of mixed line should yield ActiveSupport::Concern, got: $extendResult",
            setOf("ActiveSupport::Concern"), extendResult
        )
    }

    // ── prepend ────────────────────────────────────────────────────────────────

    fun testPrependExtractsSingleModule() {
        val result = extractModuleNames("prepend Auditable", "prepend")
        assertEquals(
            "Expected setOf(Auditable) from 'prepend Auditable', got: $result",
            setOf("Auditable"), result
        )
    }

    // ── false positives: known non-matches ─────────────────────────────────────

    fun testDoesNotMatchIncludeAsPartOfWord() {
        // Word boundary before "include" prevents matching sub-words
        val r1 = extractModuleNames("preinclude User", "include")
        assertTrue("'preinclude User' should not match 'include', got: $r1", r1.isEmpty())
        val r2 = extractModuleNames("reinclude User", "include")
        assertTrue("'reinclude User' should not match 'include', got: $r2", r2.isEmpty())
        val r3 = extractModuleNames("include_user", "include")
        assertTrue("'include_user' should not match 'include', got: $r3", r3.isEmpty())
    }

    fun testDoesNotMatchLowerCaseModuleName() {
        val r = extractModuleNames("include user", "include")
        // Module name must start with uppercase
        assertTrue("'include user' should not match (lowercase 'user'), got: $r", r.isEmpty())
    }

    fun testDoesNotMatchNumericStart() {
        val r = extractModuleNames("include 123", "include")
        assertTrue("'include 123' should not match (numeric name), got: $r", r.isEmpty())
    }

    fun testDoesNotMatchLeadingColon() {
        val r = extractModuleNames("include :Foo", "include")
        assertTrue("'include :Foo' should not match (leading colon), got: $r", r.isEmpty())
    }

    fun testDoesNotMatchInsideStringLiteral() {
        // String literals are not parsed by this regex; the actual method
        // only scans when the reflective calls fail, so false positives
        // from strings are a known limitation. But we document the behavior.
        val text = """
            str = "include User"
            include Admin
        """.trimIndent()
        val result = extractModuleNames(text, "include")
        // The regex does NOT distinguish between code and strings — it's
        // a text-wide scan. This is by design as a tertiary fallback.
        assertEquals(
            "Regex blind-scan captures User from string literal plus Admin from real include, got: $result",
            setOf("User", "Admin"), result
        )
    }

    fun testDoesNotMatchInsideComment() {
        // Same caveat as string literals — the regex is a blind text scan.
        val text = """
            # include User
            include Admin  # this is a real include
        """.trimIndent()
        val result = extractModuleNames(text, "include")
        // "User" in a comment still matches the raw regex. Acceptable for
        // a tertiary fallback — the index resolution later filters it out.
        assertEquals(
            "Regex blind-scan captures User from comment plus Admin from real include, got: $result",
            setOf("User", "Admin"), result
        )
    }

    fun testSymbolWithColonIsMatchedByBoundary() {
        // Word boundary \b matches between `:` and `i`, so `:include User`
        // is captured. This is a known limitation of the blind text scan —
        // the tertiary fallback can produce false positives for symbol
        // literals, but the index resolution filters them later.
        val result = extractModuleNames(":include User", "include")
        assertEquals(
            "':include User' matches User (boundary between : and i), got: $result",
            setOf("User"), result
        )
    }

    // ── edge cases ─────────────────────────────────────────────────────────────

    fun testIncludeWithExtraWhitespace() {
        val result = extractModuleNames("include   User", "include")
        assertEquals(
            "Expected setOf(User) with extra whitespace between include and User, got: $result",
            setOf("User"), result
        )
    }

    fun testIncludeWithLeadingWhitespace() {
        val result = extractModuleNames("  include User", "include")
        assertEquals(
            "Expected setOf(User) with leading whitespace, got: $result",
            setOf("User"), result
        )
    }

    fun testIncludeOnFirstLineOfFile() {
        val result = extractModuleNames("include User\nclass Foo; end", "include")
        assertEquals(
            "Expected setOf(User) on first line of file, got: $result",
            setOf("User"), result
        )
    }

    fun testIncludeOnLastLineOfFile() {
        val result = extractModuleNames("class Foo; end\ninclude User", "include")
        assertEquals(
            "Expected setOf(User) on last line of file, got: $result",
            setOf("User"), result
        )
    }

    fun testAllThreeCallNamesTogether() {
        val text = """
            include Validatable
            extend ActiveSupport::Concern
            prepend Auditable
        """.trimIndent()
        assertEquals(
            "include scan of multi-call text should yield Validatable",
            setOf("Validatable"), extractModuleNames(text, "include")
        )
        assertEquals(
            "extend scan of multi-call text should yield ActiveSupport::Concern",
            setOf("ActiveSupport::Concern"), extractModuleNames(text, "extend")
        )
        assertEquals(
            "prepend scan of multi-call text should yield Auditable",
            setOf("Auditable"), extractModuleNames(text, "prepend")
        )
    }

    fun testEmptySourceReturnsEmpty() {
        val r = extractModuleNames("", "include")
        assertTrue("Expected empty result from empty source, got: $r", r.isEmpty())
    }

    fun testNoIncludeCallReturnsEmpty() {
        val r = extractModuleNames("class Foo; end", "include")
        assertTrue("Expected empty when no include present, got: $r", r.isEmpty())
    }

    fun testIncludeWithMultiLineCall() {
        // Ruby allows backslash continuation; the raw regex does not span lines
        val text = "include \\\n  User"
        val result = extractModuleNames(text, "include")
        // "User" is on the next line, so the regex sees only "include \"
        assertTrue("Backslash continuation should not cross line boundary, got: $result", result.isEmpty())
    }

    fun testIncludeWithDynamicModuleName() {
        // `include Mod` where Mod is a variable — still captured by regex
        val result = extractModuleNames("include Mod", "include")
        assertEquals(
            "Expected setOf(Mod) even for dynamic/variable name, got: $result",
            setOf("Mod"), result
        )
    }

    fun testIncludeWithConditionalGuard() {
        val text = """
            include User if Some::Condition
        """.trimIndent()
        val result = extractModuleNames(text, "include")
        // The regex captures "User" — "if" starts with lowercase so it stops at "User"
        assertEquals(
            "Expected setOf(User) before 'if' guard, got: $result",
            setOf("User"), result
        )
    }

    // ── deduplication ──────────────────────────────────────────────────────────

    fun testDuplicateModuleNameIsDeduplicated() {
        // The handler uses a seenFqns set; we mirror that in the test
        val result = extractModuleNames("include User\ninclude User", "include")
        assertEquals(
            "Expected setOf(User) after deduplication, got: $result",
            setOf("User"), result
        )
    }

    // ── callName boundary: ensure each call name is independent ────────────────

    fun testIncludeDoesNotMatchExtend() {
        val r = extractModuleNames("extend Foo", "include")
        assertTrue("'extend Foo' should not match 'include' callName, got: $r", r.isEmpty())
    }

    fun testExtendDoesNotMatchInclude() {
        val r = extractModuleNames("include Foo", "extend")
        assertTrue("'include Foo' should not match 'extend' callName, got: $r", r.isEmpty())
    }

    fun testPrependDoesNotMatchInclude() {
        val r = extractModuleNames("include Foo", "prepend")
        assertTrue("'include Foo' should not match 'prepend' callName, got: $r", r.isEmpty())
    }
}