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
}
