package com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.ruby

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.ruby.RubySymbolReferenceHandler.Companion.RUBY_SYMBOL_PATTERN
import com.intellij.openapi.project.Project
import io.mockk.mockk
import junit.framework.TestCase

/**
 * Pure (non-fixture) unit tests for [RubySymbolReferenceHandler].
 *
 * Exercises symbol-format validation. No Ruby plugin needed — all tests run
 * against the pattern regex and the stub handler's format-checking logic.
 */
class RubySymbolReferenceHandlerUnitTest : TestCase() {

    private val project: Project = mockk(relaxed = true)

    private fun handler(): RubySymbolReferenceHandler = RubySymbolReferenceHandler()

    // ── RUBY_SYMBOL_PATTERN: valid symbols ───────────────────────────────────

    fun testPatternMatchesBareClassName() =
        assertTrue("RUBY_SYMBOL_PATTERN should match 'User'", RUBY_SYMBOL_PATTERN.matches("User"))

    fun testPatternMatchesNamespacedClass() =
        assertTrue("RUBY_SYMBOL_PATTERN should match 'Admin::User'", RUBY_SYMBOL_PATTERN.matches("Admin::User"))

    fun testPatternMatchesDeeplyNamespacedClass() =
        assertTrue("RUBY_SYMBOL_PATTERN should match 'A::B::C::User'", RUBY_SYMBOL_PATTERN.matches("A::B::C::User"))

    fun testPatternMatchesClassWithInstanceMethod() =
        assertTrue("RUBY_SYMBOL_PATTERN should match 'User#find'", RUBY_SYMBOL_PATTERN.matches("User#find"))

    fun testPatternMatchesClassWithPredicateMethod() =
        assertTrue("RUBY_SYMBOL_PATTERN should match 'User#admin?'", RUBY_SYMBOL_PATTERN.matches("User#admin?"))

    fun testPatternMatchesClassWithBangMethod() =
        assertTrue("RUBY_SYMBOL_PATTERN should match 'User#save!'", RUBY_SYMBOL_PATTERN.matches("User#save!"))

    fun testPatternMatchesNamespacedClassWithMethod() =
        assertTrue("RUBY_SYMBOL_PATTERN should match 'Admin::User#find_by_email'", RUBY_SYMBOL_PATTERN.matches("Admin::User#find_by_email"))

    fun testPatternMatchesNamespacedClassWithClassMethod() =
        assertTrue("RUBY_SYMBOL_PATTERN should match 'Admin::User.find_by_email'", RUBY_SYMBOL_PATTERN.matches("Admin::User.find_by_email"))

    fun testPatternMatchesSetterMethod() =
        assertTrue("RUBY_SYMBOL_PATTERN should match 'User#name='", RUBY_SYMBOL_PATTERN.matches("User#name="))

    fun testPatternMatchesSingleSegmentClassName() =
        assertTrue("RUBY_SYMBOL_PATTERN should match 'UserService'", RUBY_SYMBOL_PATTERN.matches("UserService"))

    fun testPatternMatchesModuleOnly() =
        assertTrue("RUBY_SYMBOL_PATTERN should match 'Authenticatable'", RUBY_SYMBOL_PATTERN.matches("Authenticatable"))

    fun testPatternMatchesUnderscoredNames() =
        assertTrue("RUBY_SYMBOL_PATTERN should match 'my_gem::MyClass'", RUBY_SYMBOL_PATTERN.matches("my_gem::MyClass"))

    fun testPatternPermissivelyAcceptsLowercaseLeadingSegment() {
        // KNOWN OVER-MATCH: PATH_SEGMENT allows a lowercase-initial segment, so
        // 'my_gem::MyClass' and even a bare lowercase 'user' are accepted by the
        // format pattern, although Ruby constants must be uppercase-initial.
        // This is intentional leniency at the format-validation layer; actual
        // resolution against the index filters out non-existent constants.
        assertTrue("pattern permissively matches lowercase leading segment",
            RUBY_SYMBOL_PATTERN.matches("user"))
        assertTrue("pattern permissively matches lowercase namespace segment",
            RUBY_SYMBOL_PATTERN.matches("my_gem::MyClass"))
    }

    // ── RUBY_SYMBOL_PATTERN: invalid symbols ─────────────────────────────────

    fun testPatternRejectsEmptyString() =
        assertFalse("RUBY_SYMBOL_PATTERN should reject ''", RUBY_SYMBOL_PATTERN.matches(""))

    fun testPatternRejectsTrailingColonColon() =
        assertFalse("RUBY_SYMBOL_PATTERN should reject 'Admin::'", RUBY_SYMBOL_PATTERN.matches("Admin::"))

    fun testPatternRejectsLeadingColonColon() =
        assertFalse("RUBY_SYMBOL_PATTERN should reject '::Admin'", RUBY_SYMBOL_PATTERN.matches("::Admin"))

    fun testPatternRejectsHashOnly() =
        assertFalse("RUBY_SYMBOL_PATTERN should reject '#method'", RUBY_SYMBOL_PATTERN.matches("#method"))

    fun testPatternRejectsDotOnly() =
        assertFalse("RUBY_SYMBOL_PATTERN should reject '.method'", RUBY_SYMBOL_PATTERN.matches(".method"))

    fun testPatternRejectsHashWithTrailingDot() =
        assertFalse("RUBY_SYMBOL_PATTERN should reject 'User#a.b'", RUBY_SYMBOL_PATTERN.matches("User#a.b"))

    fun testPatternRejectsDoubleHash() =
        assertFalse("RUBY_SYMBOL_PATTERN should reject 'User#a#b'", RUBY_SYMBOL_PATTERN.matches("User#a#b"))

    fun testPatternRejectsDoubleDot() =
        assertFalse("RUBY_SYMBOL_PATTERN should reject 'User.a.b'", RUBY_SYMBOL_PATTERN.matches("User.a.b"))

    fun testPatternRejectsNumbersOnly() =
        assertFalse("RUBY_SYMBOL_PATTERN should reject '123'", RUBY_SYMBOL_PATTERN.matches("123"))

    // ── resolveSymbol: format validation ──────────────────────────────────────

    fun testResolveRejectsEmptySymbol() {
        val r = handler().resolveSymbol(project, "")
        assertTrue("resolveSymbol('') should return Failure, was: $r", r.isFailure)
        val msg = r.exceptionOrNull()!!.message!!
        assertTrue("Expected 'does not match expected Ruby symbol format' in error message for '', got: $msg", msg.contains("does not match expected Ruby symbol format"))
    }

    fun testResolveRejectsBareHash() {
        val r = handler().resolveSymbol(project, "#method")
        assertTrue("resolveSymbol('#method') should return Failure, was: $r", r.isFailure)
        val msg = r.exceptionOrNull()!!.message!!
        assertTrue("Expected 'does not match expected Ruby symbol format' in error message for '#method', got: $msg", msg.contains("does not match expected Ruby symbol format"))
    }

    fun testResolveRejectsTrailingColonColon() {
        val r = handler().resolveSymbol(project, "Admin::")
        assertTrue("resolveSymbol('Admin::') should return Failure, was: $r", r.isFailure)
        val msg = r.exceptionOrNull()!!.message!!
        assertTrue("Expected 'does not match expected Ruby symbol format' in error message for 'Admin::', got: $msg", msg.contains("does not match expected Ruby symbol format"))
    }

    // ── resolveSymbol: valid format but stub (no Ruby plugin in mock project) ────────────

    fun testResolveValidBareClassNameReturnsCouldNotResolve() {
        val r = handler().resolveSymbol(project, "User")
        assertTrue("resolveSymbol('User') should return Failure (no Ruby plugin), was: $r", r.isFailure)
        val msg = r.exceptionOrNull()!!.message!!
        assertTrue("Expected 'could not be resolved' in error message for 'User', got: $msg", msg.contains("could not be resolved"))
    }

    fun testResolveValidNamespacedClassReturnsCouldNotResolve() {
        val r = handler().resolveSymbol(project, "Admin::User")
        assertTrue("resolveSymbol('Admin::User') should return Failure (no Ruby plugin), was: $r", r.isFailure)
        val msg = r.exceptionOrNull()!!.message!!
        assertTrue("Expected 'could not be resolved' in error message for 'Admin::User', got: $msg", msg.contains("could not be resolved"))
    }

    fun testResolveValidMethodReturnsCouldNotResolve() {
        val r = handler().resolveSymbol(project, "User#admin?")
        assertTrue("resolveSymbol('User#admin?') should return Failure (no Ruby plugin), was: $r", r.isFailure)
        val msg = r.exceptionOrNull()!!.message!!
        assertTrue("Expected 'could not be resolved' in error message for 'User#admin?', got: $msg", msg.contains("could not be resolved"))
    }

    // ── resolveSymbol: trimming ───────────────────────────────────────────────

    fun testResolveTrimsWhitespaceThenFailsFormatForEmpty() {
        // Whitespace-only should fail format validation (even after trim, it's empty)
        val r = handler().resolveSymbol(project, "   ")
        assertTrue("resolveSymbol('   ') should return Failure (empty after trim), was: $r", r.isFailure)
        val msg = r.exceptionOrNull()!!.message!!
        assertTrue("Expected 'does not match expected Ruby symbol format' in error message for whitespace-only, got: $msg", msg.contains("does not match expected Ruby symbol format"))
    }

    fun testResolveValidSymbolWithLeadingWhitespacePassesFormat() {
        // Leading whitespace trimmed, valid symbol passes format -> returns not-implemented
        val r = handler().resolveSymbol(project, "  User#find")
        assertTrue("resolveSymbol('  User#find') should return Failure (no Ruby plugin), was: $r", r.isFailure)
        val msg = r.exceptionOrNull()!!.message!!
        assertTrue("Expected 'could not be resolved' in error message for '  User#find', got: $msg", msg.contains("could not be resolved"))
    }
}