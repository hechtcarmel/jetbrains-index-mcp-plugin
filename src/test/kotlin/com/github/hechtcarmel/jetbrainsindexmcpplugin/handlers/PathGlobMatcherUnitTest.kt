package com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers

import junit.framework.TestCase

/**
 * Glob semantics of the `paths` parameter (issue #328).
 *
 * The dialect: patterns are project-relative, `*` stays within one path segment, `?` is a
 * single non-separator character, a double star crosses directories (zero or more whole
 * segments), a pattern also matches everything beneath a directory it names, and a leading
 * `!` turns the entry into an exclude. Includes are unioned, then excludes subtracted;
 * with only excludes, everything else matches.
 */
class PathGlobMatcherUnitTest : TestCase() {

    private fun matcherOf(vararg entries: String): PathGlobMatcher =
        PathGlobMatcher.parse(entries.toList()).getOrThrow()

    fun testDirectoryPatternMatchesEverythingBeneathIt() {
        val matcher = matcherOf("src/main")
        assertTrue(matcher.matches("src/main/A.kt"))
        assertTrue(matcher.matches("src/main/kotlin/x/A.kt"))
        assertTrue(matcher.matches("src/main"))
        assertFalse("must not match a sibling that shares the name as a string prefix", matcher.matches("src/mainx/A.kt"))
        assertFalse(matcher.matches("src/test/A.kt"))
    }

    fun testSingleStarStaysWithinOnePathSegment() {
        val matcher = matcherOf("src/*.kt")
        assertTrue(matcher.matches("src/A.kt"))
        assertFalse("'*' must not cross directories", matcher.matches("src/sub/A.kt"))
        assertFalse(matcher.matches("src/A.java"))
    }

    fun testQuestionMarkMatchesExactlyOneNonSeparatorCharacter() {
        val matcher = matcherOf("src/?.kt")
        assertTrue(matcher.matches("src/A.kt"))
        assertFalse(matcher.matches("src/AB.kt"))
        assertFalse("'?' must not match '/'", matcher.matches("src/x/.kt"))
    }

    fun testDoubleStarCrossesDirectoriesIncludingZeroSegments() {
        val matcher = matcherOf("src/main/kotlin/**/handlers/**")
        assertTrue("zero intermediate segments must match", matcher.matches("src/main/kotlin/handlers/A.kt"))
        assertTrue(matcher.matches("src/main/kotlin/a/b/handlers/c/A.kt"))
        assertFalse("segment name must match exactly", matcher.matches("src/main/kotlin/handlersx/A.kt"))
        assertFalse(matcher.matches("src/main/java/handlers/A.kt"))
    }

    fun testTrailingDoubleStar() {
        val matcher = matcherOf("src/**")
        assertTrue(matcher.matches("src/A.kt"))
        assertTrue(matcher.matches("src/a/b/A.kt"))
        assertFalse(matcher.matches("A.kt"))
        assertFalse(matcher.matches("srcx/A.kt"))
    }

    fun testLeadingDoubleStarFilePattern() {
        val matcher = matcherOf("**/*Test.kt")
        assertTrue("must match at the project root", matcher.matches("FooTest.kt"))
        assertTrue(matcher.matches("a/b/FooTest.kt"))
        assertFalse(matcher.matches("a/b/FooTests.kt"))
    }

    fun testExcludesSubtractFromIncludes() {
        val matcher = matcherOf("src/**", "!**/*Test.kt")
        assertTrue(matcher.matches("src/a/Foo.kt"))
        assertFalse(matcher.matches("src/a/FooTest.kt"))
        assertFalse("files outside every include stay out", matcher.matches("lib/Foo.kt"))
    }

    fun testExcludeOnlyMatchesEverythingElse() {
        val matcher = matcherOf("!build", "!**/*Test.kt")
        assertTrue(matcher.matches("src/Foo.kt"))
        assertFalse("plain directory exclude drops everything beneath it", matcher.matches("build/gen/Foo.kt"))
        assertFalse(matcher.matches("src/FooTest.kt"))
    }

    fun testMultipleIncludesAreUnioned() {
        val matcher = matcherOf("a/**", "b/**")
        assertTrue(matcher.matches("a/x.txt"))
        assertTrue(matcher.matches("b/y.txt"))
        assertFalse(matcher.matches("c/z.txt"))
    }

    fun testLeadingAndTrailingSeparatorsAreNormalized() {
        assertTrue(matcherOf("./src/main").matches("src/main/A.kt"))
        assertTrue(matcherOf("/src/main").matches("src/main/A.kt"))
        assertTrue(matcherOf("src/main/").matches("src/main/A.kt"))
    }

    fun testMatchingIsCaseSensitive() {
        assertFalse(matcherOf("src/**").matches("SRC/A.kt"))
    }

    fun testRegexMetacharactersInPatternsAreLiteral() {
        val plus = matcherOf("a+b/**")
        assertTrue(plus.matches("a+b/x.txt"))
        assertFalse("'+' must not act as a regex quantifier", plus.matches("aab/x.txt"))

        val dot = matcherOf("v1.2/**")
        assertTrue(dot.matches("v1.2/x.txt"))
        assertFalse("'.' must not act as regex any-char", dot.matches("v1x2/x.txt"))
    }

    /** Library/jar files resolve to no project-relative path; see [PathGlobMatcher.matches]. */
    fun testNullRelativePathOnlyMatchesForExcludeOnlyFilters() {
        assertFalse(matcherOf("src/**").matches(null))
        assertTrue(matcherOf("!**/*Test.kt").matches(null))
    }

    fun testLiteralPrefixExtraction() {
        fun prefixOf(pattern: String) = PathGlob.compile(pattern, pattern).literalPrefix
        assertEquals("src/main", prefixOf("src/main"))
        assertEquals("src/main/kotlin", prefixOf("src/main/kotlin/**/handlers/**"))
        assertEquals("", prefixOf("**/*Test.kt"))
        assertEquals("a wildcard inside a segment must trim back to the last full directory", "src", prefixOf("src/ma*/foo"))
        assertEquals("", prefixOf("*.kt"))
        assertEquals("src", prefixOf("src/?.kt"))
    }

    /**
     * [PathGlobScope] hands this key to `DelegatingGlobalSearchScope`'s `equality` parameter.
     * If two different filters produced the same key, the two scopes would compare equal and
     * `GlobalSearchScope.intersectWith`/`union` would short-circuit one filter into the other.
     */
    fun testEqualityKeyDistinguishesDifferentFilters() {
        assertEquals(
            "same globs must produce the same key",
            matcherOf("src/**", "!**/*Test.kt").equalityKey,
            matcherOf("src/**", "!**/*Test.kt").equalityKey
        )
        assertFalse(
            "different includes must not collide",
            matcherOf("src/**").equalityKey == matcherOf("lib/**").equalityKey
        )
        assertFalse(
            "an include and the same pattern negated must not collide",
            matcherOf("src/**").equalityKey == matcherOf("!src/**").equalityKey
        )
        assertFalse(
            "adding an exclude must change the key",
            matcherOf("src/**").equalityKey == matcherOf("src/**", "!**/*Test.kt").equalityKey
        )
    }

    fun testParseRejectsDegenerateEntries() {
        assertTrue(PathGlobMatcher.parse(emptyList()).isFailure)
        assertTrue(PathGlobMatcher.parse(listOf("  ")).isFailure)
        assertTrue(PathGlobMatcher.parse(listOf("!")).isFailure)
        assertTrue(PathGlobMatcher.parse(listOf("src/**")).isSuccess)
    }
}
