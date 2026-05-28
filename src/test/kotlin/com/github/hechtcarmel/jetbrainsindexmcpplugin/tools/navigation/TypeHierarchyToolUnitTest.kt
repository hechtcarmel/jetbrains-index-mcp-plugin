package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation

import junit.framework.TestCase

class TypeHierarchyToolUnitTest : TestCase() {

    fun testRiderClassNameCandidateLanguages_useOnlyExplicitCSharp() {
        assertEquals(
            listOf("C#"),
            TypeHierarchyTool.riderClassNameCandidateLanguages("C#")
        )
    }

    fun testRiderClassNameCandidateLanguages_keepCompatibilityWhenLanguageMissing() {
        assertEquals(
            listOf("C#"),
            TypeHierarchyTool.riderClassNameCandidateLanguages(null)
        )
    }

    fun testRiderQualifiedNameMatchesClassName_normalizesClrNestedNames() {
        assertTrue(
            TypeHierarchyTool.riderQualifiedNameMatchesClassName(
                qualifiedName = "MyNamespace.Outer+Inner",
                className = "MyNamespace.Outer.Inner"
            )
        )
    }
}
