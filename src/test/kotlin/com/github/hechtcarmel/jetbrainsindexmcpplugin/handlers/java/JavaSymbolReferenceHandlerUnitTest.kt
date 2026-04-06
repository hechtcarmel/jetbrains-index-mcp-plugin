package com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.java

import junit.framework.TestCase

class JavaSymbolReferenceHandlerUnitTest : TestCase() {

    private val pattern = JavaSymbolReferenceHandler.JAVA_SYMBOL_PATTERN
    private val handler = JavaSymbolReferenceHandler()

    // ── JAVA_SYMBOL_PATTERN: valid symbols ─────────────────────────────────────

    fun testPatternMatchesClassOnly() {
        assertTrue(pattern.matches("com.example.MyClass"))
    }

    fun testPatternMatchesNestedClass() {
        assertTrue(pattern.matches("com.example.Outer.Inner"))
    }

    fun testPatternMatchesDeeplyNestedClass() {
        assertTrue(pattern.matches("com.example.Outer.Middle.Inner"))
    }

    fun testPatternMatchesFieldReference() {
        assertTrue(pattern.matches("com.example.MyClass#fieldName"))
    }

    fun testPatternMatchesMethodNoParams() {
        assertTrue(pattern.matches("com.example.MyClass#doSomething()"))
    }

    fun testPatternMatchesMethodWithPrimitiveParam() {
        assertTrue(pattern.matches("com.example.MyClass#process(int)"))
    }

    fun testPatternMatchesMethodWithMultipleParams() {
        assertTrue(pattern.matches("com.example.MyClass#process(int, String)"))
    }

    fun testPatternMatchesMethodWithArrayParam() {
        assertTrue(pattern.matches("com.example.MyClass#process(int[])"))
    }

    fun testPatternMatchesMethodWithVarargsParam() {
        assertTrue(pattern.matches("com.example.MyClass#process(String...)"))
    }

    fun testPatternMatchesMethodWithMultiDimensionalArrayParam() {
        assertTrue(pattern.matches("com.example.MyClass#process(int[][])"))
    }

    fun testPatternMatchesMethodWithFqnParam() {
        assertTrue(pattern.matches("com.example.MyClass#process(java.util.List)"))
    }

    fun testPatternMatchesMethodWithMixedParams() {
        assertTrue(pattern.matches("com.example.MyClass#process(int[], String, java.util.List)"))
    }

    fun testPatternMatchesSinglePackageSegment() {
        assertTrue(pattern.matches("a.MyClass"))
    }

    fun testPatternMatchesPackageWithUnderscores() {
        assertTrue(pattern.matches("com.my_package.MyClass"))
    }

    fun testPatternMatchesClassWithDollarSign() {
        assertTrue(pattern.matches("com.example.My\$Class"))
    }

    fun testPatternMatchesMemberWithUnderscore() {
        assertTrue(pattern.matches("com.example.MyClass#_field"))
    }

    fun testPatternMatchesMemberWithDollarSign() {
        assertTrue(pattern.matches("com.example.MyClass#field\$1"))
    }

    fun testPatternMatchesAllPrimitiveTypes() {
        val primitives = listOf("byte", "short", "int", "long", "float", "double", "boolean", "char")
        for (prim in primitives) {
            assertTrue("Should match primitive $prim",
                pattern.matches("com.example.MyClass#method($prim)"))
        }
    }

    // ── JAVA_SYMBOL_PATTERN: unconventional but legal Java identifiers ─────────
    // Java identifiers are not restricted to conventional casing.
    // These tests guard against the regex rejecting valid symbols. (review.md issue #2)

    fun testPatternMatchesLowercaseClassName() {
        assertTrue("Lowercase class names are legal Java identifiers",
            pattern.matches("com.example.myclass"))
    }


    fun testPatternMatchesCamelCaseClassSegment() {
        assertTrue("camelCase class is a legal Java identifier",
            pattern.matches("com.example.iPhone"))
    }

    fun testPatternMatchesPackageStartingWithUnderscore() {
        assertTrue("Package starting with underscore is a legal Java identifier",
            pattern.matches("_internal.MyClass"))
    }

    fun testPatternMatchesMixedCasePackageAndClass() {
        assertTrue("Mixed-case package and class are legal Java identifiers",
            pattern.matches("COM.Example.myClass#field"))
    }

    fun testPatternMatchesSingleCharIdentifiers() {
        assertTrue("Single-char identifiers are legal",
            pattern.matches("a.B"))
    }

    fun testPatternMatchesClassStartingWithDollarSign() {
        assertTrue("Dollar-prefixed class names are legal Java identifiers",
            pattern.matches("com.example.\$Generated"))
    }

    fun testPatternMatchesClassStartingWithUnderscore() {
        assertTrue("Underscore-prefixed class names are legal Java identifiers",
            pattern.matches("com.example._PrivateHelper"))
    }

    fun testPatternMatchesNumericSuffixInPackage() {
        assertTrue("Package with numeric suffix after first char is legal",
            pattern.matches("com.v2.MyClass"))
    }

    // ── JAVA_SYMBOL_PATTERN: invalid symbols ───────────────────────────────────

    fun testPatternRejectsNoPackage() {
        assertFalse(pattern.matches("MyClass"))
    }

    fun testPatternRejectsEmptyString() {
        assertFalse(pattern.matches(""))
    }

    fun testPatternRejectsTrailingDot() {
        assertFalse(pattern.matches("com.example."))
    }

    fun testPatternRejectsLeadingDot() {
        assertFalse(pattern.matches(".com.example.MyClass"))
    }

    fun testPatternRejectsHashOnly() {
        assertFalse(pattern.matches("com.example.MyClass#"))
    }

    fun testPatternRejectsDoubleHash() {
        assertFalse(pattern.matches("com.example.MyClass##field"))
    }

    fun testPatternRejectsUnclosedParens() {
        assertFalse(pattern.matches("com.example.MyClass#method(int"))
    }

    fun testPatternRejectsSpecialCharsInPackage() {
        assertFalse(pattern.matches("com.exam!ple.MyClass"))
    }

    fun testPatternRejectsSpaceInPackage() {
        assertFalse(pattern.matches("com. example.MyClass"))
    }

    // ── stripGenerics ──────────────────────────────────────────────────────────

    fun testStripGenericsNoOp() {
        assertEquals("String", handler.stripGenerics("String"))
    }

    fun testStripGenericsSimple() {
        assertEquals("List", handler.stripGenerics("List<String>"))
    }

    fun testStripGenericsNested() {
        assertEquals("Map", handler.stripGenerics("Map<String, List<Integer>>"))
    }

    fun testStripGenericsArray() {
        assertEquals("List[]", handler.stripGenerics("List<String>[]"))
    }

    fun testStripGenericsDeeplyNested() {
        assertEquals("Map", handler.stripGenerics("Map<String, Map<Integer, List<Double>>>"))
    }

    fun testStripGenericsMultipleTypeParams() {
        assertEquals("Triple", handler.stripGenerics("Triple<A, B, C>"))
    }

    fun testStripGenericsEmptyString() {
        assertEquals("", handler.stripGenerics(""))
    }

    fun testStripGenericsNoAngleBrackets() {
        assertEquals("int[]", handler.stripGenerics("int[]"))
    }

    fun testStripGenericsPreservesNonGenericText() {
        assertEquals("java.util.Map", handler.stripGenerics("java.util.Map<K, V>"))
    }

    fun testStripGenericsUnbalancedClosingReturnsOriginal() {
        val input = "List>String>"
        assertEquals(input, handler.stripGenerics(input))
    }

    fun testStripGenericsUnclosedOpeningReturnsOriginal() {
        val input = "List<String"
        assertEquals(input, handler.stripGenerics(input))
    }

    fun testStripGenericsUnclosedNestedReturnsOriginal() {
        val input = "Map<String, List<Integer>"
        assertEquals(input, handler.stripGenerics(input))
    }
}
