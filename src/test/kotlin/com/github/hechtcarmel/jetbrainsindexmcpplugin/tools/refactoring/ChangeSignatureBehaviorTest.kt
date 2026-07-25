package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ChangeSignatureBehaviorTest : McpPlatformTestCase() {

    fun testChangeSignatureAddsParameter() = runBlocking {
        writeProjectFile("src/SigService.java", """
            public class SigService {
                public String process(String input) {
                    return input.trim();
                }
                public void caller() {
                    String result = process("hello");
                }
            }
        """.trimIndent())

        val result = ChangeSignatureTool().execute(project, buildJsonObject {
            put("file", "src/SigService.java")
            put("line", 2)
            put("column", 19)
            put("newParameters", buildJsonArray {
                add(buildJsonObject { put("oldIndex", 0); put("name", "input"); put("type", "String") })
                add(buildJsonObject { put("oldIndex", -1); put("name", "validate"); put("type", "boolean"); put("defaultValue", "true") })
            })
        })

        assertToolSucceeded("Change signature should succeed", result)
        val content = readProjectFileVfs("src/SigService.java")
        assertTrue("Method should have new param: $content", content.contains("boolean validate"))
    }

    fun testChangeSignatureChangesReturnType() = runBlocking {
        writeProjectFile("src/SigConverter.java", """
            public class SigConverter {
                public String convert(int value) {
                    return String.valueOf(value);
                }
            }
        """.trimIndent())

        val result = ChangeSignatureTool().execute(project, buildJsonObject {
            put("file", "src/SigConverter.java")
            put("line", 2)
            put("column", 19)
            put("newReturnType", "int")
            put("newParameters", buildJsonArray {
                add(buildJsonObject { put("oldIndex", 0); put("name", "value"); put("type", "int") })
            })
        })

        assertToolSucceeded("Change return type should succeed", result)
        val content = readProjectFileVfs("src/SigConverter.java")
        assertTrue("Return type should be int: $content", content.contains("public int convert"))
    }

    fun testChangeSignatureOnNonMethodFails() = runBlocking {
        writeProjectFile("src/SigNotAMethod.java", """
            public class SigNotAMethod {
                private int count = 0;
            }
        """.trimIndent())

        val result = ChangeSignatureTool().execute(project, buildJsonObject {
            put("file", "src/SigNotAMethod.java")
            put("line", 2)
            put("column", 17)
            put("newReturnType", "String")
        })

        assertToolFailed("Change signature on a field should fail", result)
        assertEquals(
            "No method found at line 2, column 17. Position the cursor on a method name.",
            toolText(result)
        )
    }

    fun testChangeSignatureRequiresAtLeastOneChange() = runBlocking {
        writeProjectFile("src/SigNoChange.java", """
            public class SigNoChange {
                public void doWork() {}
            }
        """.trimIndent())

        val result = ChangeSignatureTool().execute(project, buildJsonObject {
            put("file", "src/SigNoChange.java")
            put("line", 2)
            put("column", 17)
        })

        assertToolFailed("Should require at least one change", result)
        assertEquals(
            "At least one change is required: newName, newReturnType, newVisibility, or newParameters.",
            toolText(result)
        )
    }
}
