package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class RenameSymbolToolBehaviorTest : McpPlatformTestCase() {

    fun testExplicitFileRenameIgnoresMalformedCoordinatesDuringFullToolExecution() = runBlocking {
        writeProjectFile(
            "docs/readme.txt",
            "Rename me through file mode.\n"
        )

        val result = RenameSymbolTool().execute(project, buildJsonObject {
            put("file", "docs/readme.txt")
            put("targetType", "file")
            put("line", JsonPrimitive("not-a-number"))
            put("column", JsonPrimitive("still-not-a-number"))
            put("newName", "readme-renamed.txt")
        })

        assertToolSucceeded("Explicit file rename should ignore malformed line/column values", result)
        assertProjectFileAbsent("docs/readme.txt")
        assertProjectFileExists("docs/readme-renamed.txt")
        assertFileContains("docs/readme-renamed.txt", "Rename me through file mode.")
    }

    // ── Java: symbol rename ──

    fun testJavaRenameMethodUpdatesCallSitesWithinFile() = runBlocking {
        writeProjectFile(
            "src/UserService.java", """
            public class UserService {
                public String getDisplayName() {
                    return "name";
                }
                public String show() {
                    return getDisplayName();
                }
            }
        """.trimIndent()
        )

        val result = RenameSymbolTool().execute(project, buildJsonObject {
            put("file", "src/UserService.java")
            put("line", 2)
            put("column", 19)
            put("newName", "getFullName")
        })

        assertToolSucceeded("Java method rename should succeed", result)
        assertRenamedInFile("src/UserService.java", "getDisplayName", "getFullName")
        assertFileContains("src/UserService.java", "public String getFullName()")
        assertFileContains("src/UserService.java", "return getFullName();")
    }

    fun testJavaRenameFieldUpdatesReferencesWithinFile() = runBlocking {
        writeProjectFile(
            "src/FieldRenameTarget.java", """
            public class FieldRenameTarget {
                public int count = 0;
                public void increment() {
                    count = count + 1;
                }
            }
        """.trimIndent()
        )

        val result = RenameSymbolTool().execute(project, buildJsonObject {
            put("file", "src/FieldRenameTarget.java")
            put("line", 2)
            put("column", 16)
            put("newName", "total")
        })

        assertToolSucceeded("Java field rename should succeed", result)
        assertFileContains("src/FieldRenameTarget.java", "public int total = 0;")
        assertFileContains("src/FieldRenameTarget.java", "total = total + 1;")
        assertFileDoesNotContain("src/FieldRenameTarget.java", "int count")
        assertFileDoesNotContain("src/FieldRenameTarget.java", "count = count")
    }

    fun testJavaRenameClassRenamesFile() = runBlocking {
        writeProjectFile(
            "src/OldName.java", """
            public class OldName {
                public void doWork() {}
            }
        """.trimIndent()
        )

        val result = RenameSymbolTool().execute(project, buildJsonObject {
            put("file", "src/OldName.java")
            put("line", 1)
            put("column", 14)
            put("newName", "NewName")
        })

        assertToolSucceeded("Java class rename should succeed", result)
        assertProjectFileAbsent("src/OldName.java")
        assertProjectFileExists("src/NewName.java")
        assertFileContains("src/NewName.java", "public class NewName {")
        assertFileDoesNotContain("src/NewName.java", "OldName")
    }

    fun testJavaRenameParameterUpdatesUsagesInBody() = runBlocking {
        writeProjectFile(
            "src/Processor.java", """
            public class Processor {
                public String process(String input) {
                    return input.trim();
                }
            }
        """.trimIndent()
        )

        val result = RenameSymbolTool().execute(project, buildJsonObject {
            put("file", "src/Processor.java")
            put("line", 2)
            put("column", 34)
            put("newName", "rawValue")
        })

        assertToolSucceeded("Java parameter rename should succeed", result)
        assertFileContains("src/Processor.java", "public String process(String rawValue)")
        assertFileContains("src/Processor.java", "return rawValue.trim();")
        assertFileDoesNotContain("src/Processor.java", "input")
    }

    // ── Java: cross-file symbol rename ──
    //
    // Cross-file reference updating is the whole point of routing a rename through the IDE
    // index, so the referencing file — not just the declaring one — carries the assertions.
    // A source root is required for `ReferencesSearch` to see the second file at all.

    fun testJavaRenameMethodUpdatesCallSiteInAnotherFile() = runBlocking {
        registerSourceRoot("cross-method-src")
        writeProjectFile(
            "cross-method-src/crossmethod/Greeter.java", """
            package crossmethod;

            public class Greeter {
                public String getDisplayName() {
                    return "name";
                }
            }
        """.trimIndent()
        )
        writeProjectFile(
            "cross-method-src/crossmethod/GreeterClient.java", """
            package crossmethod;

            public class GreeterClient {
                public String describe(Greeter greeter) {
                    return greeter.getDisplayName();
                }
            }
        """.trimIndent()
        )

        val result = RenameSymbolTool().execute(project, buildJsonObject {
            put("file", "cross-method-src/crossmethod/Greeter.java")
            put("line", 4)
            put("column", 19)
            put("newName", "getFullName")
        })

        assertToolSucceeded("Cross-file Java method rename should succeed", result)
        assertRenamedInFile("cross-method-src/crossmethod/Greeter.java", "getDisplayName", "getFullName")
        assertRenamedInFile("cross-method-src/crossmethod/GreeterClient.java", "getDisplayName", "getFullName")
        assertFileContains("cross-method-src/crossmethod/GreeterClient.java", "return greeter.getFullName();")
    }

    fun testJavaRenameClassUpdatesReferencingFileAndRenamesSourceFile() = runBlocking {
        registerSourceRoot("cross-class-src")
        writeProjectFile(
            "cross-class-src/crossclass/LegacyReport.java", """
            package crossclass;

            public class LegacyReport {
                public String title() {
                    return "report";
                }
            }
        """.trimIndent()
        )
        writeProjectFile(
            "cross-class-src/crossclass/ReportPrinter.java", """
            package crossclass;

            public class ReportPrinter {
                public String print(LegacyReport source) {
                    return source.title();
                }
            }
        """.trimIndent()
        )

        val result = RenameSymbolTool().execute(project, buildJsonObject {
            put("file", "cross-class-src/crossclass/LegacyReport.java")
            put("line", 3)
            put("column", 14)
            put("newName", "ArchivedReport")
        })

        assertToolSucceeded("Cross-file Java class rename should succeed", result)
        assertProjectFileAbsent("cross-class-src/crossclass/LegacyReport.java")
        assertProjectFileExists("cross-class-src/crossclass/ArchivedReport.java")
        assertFileContains("cross-class-src/crossclass/ArchivedReport.java", "public class ArchivedReport {")
        assertRenamedInFile("cross-class-src/crossclass/ReportPrinter.java", "LegacyReport", "ArchivedReport")
        assertFileContains("cross-class-src/crossclass/ReportPrinter.java", "public String print(ArchivedReport source)")
    }
}
