package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools

import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring.ReplaceTextInFileTool
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class AmbiguousFileResolutionBehaviorTest : McpPlatformTestCase() {

    override fun setUp() {
        super.setUp()
        registerSourceRoot("module-a/src")
        registerSourceRoot("module-b/src")
    }

    fun testAmbiguousRelativePathReportsErrorWithBothMatches() = runBlocking {
        writeProjectFile("module-a/src/com/example/Helper.java", """
            package com.example;
            public class Helper { int x = 1; }
        """.trimIndent())

        writeProjectFile("module-b/src/com/example/Helper.java", """
            package com.example;
            public class Helper { int x = 2; }
        """.trimIndent())

        val result = ReplaceTextInFileTool().execute(project, buildJsonObject {
            put("file", "com/example/Helper.java")
            put("searchText", "int x")
            put("replaceText", "int y")
        })

        assertToolFailed("Should fail when file path is ambiguous", result)
        val text = toolText(result)
        assertTrue("Error should mention 'Ambiguous', was: $text", text.contains("Ambiguous"))
        assertTrue("Error should list module-a path, was: $text", text.contains("module-a"))
        assertTrue("Error should list module-b path, was: $text", text.contains("module-b"))
        assertFileContains("module-a/src/com/example/Helper.java", "int x = 1")
        assertFileContains("module-b/src/com/example/Helper.java", "int x = 2")
    }

    fun testFullPathFromProjectRootResolvesUnambiguously() = runBlocking {
        writeProjectFile("module-a/src/com/example/Helper.java", """
            package com.example;
            public class Helper { int x = 1; }
        """.trimIndent())

        writeProjectFile("module-b/src/com/example/Helper.java", """
            package com.example;
            public class Helper { int x = 2; }
        """.trimIndent())

        val result = ReplaceTextInFileTool().execute(project, buildJsonObject {
            put("file", "module-b/src/com/example/Helper.java")
            put("searchText", "int x = 2")
            put("replaceText", "int y = 2")
        })

        assertToolSucceeded("Full path should resolve unambiguously", result)
        assertFileContains("module-b/src/com/example/Helper.java", "int y = 2")
        assertFileContains("module-a/src/com/example/Helper.java", "int x = 1")
    }

    fun testRootFileResolvesToBasePathEvenWhenSubmoduleHasSameName() = runBlocking {
        writeProjectFile("README.md", "root readme")
        writeProjectFile("module-a/src/README.md", "module-a readme")

        val result = ReplaceTextInFileTool().execute(project, buildJsonObject {
            put("file", "README.md")
            put("searchText", "root readme")
            put("replaceText", "updated root readme")
        })

        assertToolSucceeded("Root file should resolve via basePath", result)
        assertFileContains("README.md", "updated root readme")
        assertFileContains("module-a/src/README.md", "module-a readme")
    }
}
