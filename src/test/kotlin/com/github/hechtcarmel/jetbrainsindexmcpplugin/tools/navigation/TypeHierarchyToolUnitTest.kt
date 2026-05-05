package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation

import junit.framework.TestCase

class TypeHierarchyToolUnitTest : TestCase() {

    fun testRiderClassNameCandidateLanguages_useOnlyExplicitFSharp() {
        assertEquals(
            listOf("F#"),
            TypeHierarchyTool.riderClassNameCandidateLanguages("F#")
        )
    }

    fun testRiderClassNameCandidateLanguages_useOnlyExplicitCSharp() {
        assertEquals(
            listOf("C#"),
            TypeHierarchyTool.riderClassNameCandidateLanguages("C#")
        )
    }

    fun testRiderClassNameCandidateLanguages_keepCompatibilityWhenLanguageMissing() {
        assertEquals(
            listOf("C#", "F#"),
            TypeHierarchyTool.riderClassNameCandidateLanguages(null)
        )
    }

    fun testRiderQualifiedNameMatchesClassName_normalizesClrNestedFSharpNames() {
        assertTrue(
            TypeHierarchyTool.riderQualifiedNameMatchesClassName(
                qualifiedName = "FSharpPlus.Lens+Lens",
                className = "FSharpPlus.Lens.Lens"
            )
        )
    }
}
