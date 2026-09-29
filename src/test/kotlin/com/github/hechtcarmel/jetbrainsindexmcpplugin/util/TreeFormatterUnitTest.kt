package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.StructureKind
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.StructureNode
import junit.framework.TestCase

class TreeFormatterUnitTest : TestCase() {

    fun testFormatsPhpSpecificStructureKinds() {
        val output = TreeFormatter.format(
            nodes = listOf(
                StructureNode(
                    name = "TABLE",
                    kind = StructureKind.CONSTANT,
                    modifiers = listOf("public"),
                    signature = null,
                    line = 8
                ),
                StructureNode(
                    name = "Published",
                    kind = StructureKind.ENUM_CASE,
                    modifiers = emptyList(),
                    signature = null,
                    line = 14
                ),
                StructureNode(
                    name = "vendor/autoload.php",
                    kind = StructureKind.INCLUDE,
                    modifiers = emptyList(),
                    signature = null,
                    line = 3
                )
            ),
            fileName = "Example.php",
            language = "PHP"
        )

        assertTrue(output.contains("constant public TABLE (line 8)"))
        assertTrue(output.contains("enum case Published (line 14)"))
        assertTrue(output.contains("include vendor/autoload.php (line 3)"))
    }

    fun testFoldsScalaKeywordsFromModifiersIntoTheDeclaration() {
        val output = TreeFormatter.format(
            nodes = listOf(
                StructureNode(
                    name = "Employee",
                    kind = StructureKind.CLASS,
                    modifiers = listOf("case"),
                    signature = null,
                    line = 18,
                    endLine = 21,
                    children = listOf(
                        StructureNode("work", StructureKind.METHOD, listOf("override"), "(task)", 19),
                        StructureNode("retries", StructureKind.PROPERTY, listOf("val"), null, 20),
                        StructureNode("count", StructureKind.PROPERTY, listOf("private", "var"), null, 21)
                    )
                ),
                StructureNode("fixture", StructureKind.OBJECT, listOf("package"), null, 3),
                StructureNode("Plain", StructureKind.CLASS, listOf("abstract"), null, 30)
            ),
            fileName = "Models.scala",
            language = "Scala"
        )

        assertTrue(output, output.contains("case class Employee (lines 18-21)"))
        assertTrue(output, output.contains("def override work (task) (line 19)"))
        assertTrue(output, output.contains("val retries (line 20)"))
        assertTrue(output, output.contains("var private count (line 21)"))
        assertTrue(output, output.contains("package object fixture (line 3)"))
        assertTrue(output, output.contains("class abstract Plain (line 30)"))
    }

    fun testLeavesOtherLanguagesModifiersUntouched() {
        val output = TreeFormatter.format(
            nodes = listOf(StructureNode("Data", StructureKind.CLASS, listOf("case"), null, 1)),
            fileName = "Data.kt",
            language = "kotlin"
        )

        assertTrue(output, output.contains("class case Data (line 1)"))
    }
}
