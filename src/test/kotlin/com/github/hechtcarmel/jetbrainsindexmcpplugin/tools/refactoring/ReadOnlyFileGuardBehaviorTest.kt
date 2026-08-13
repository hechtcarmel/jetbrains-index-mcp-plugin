package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.intellij.openapi.vfs.LocalFileSystem
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * Verifies that write tools reject read-only files with a clear "read-only" error
 * instead of failing deep inside the refactoring engine.
 *
 * Each test follows the same pattern:
 * 1. Write a project file.
 * 2. Mark it read-only via the VFS.
 * 3. Call the tool.
 * 4. Assert the tool returned an error mentioning "read-only".
 * 5. Assert the file content is unchanged.
 * 6. Restore writability in cleanup (handled by [tearDown]).
 */
class ReadOnlyFileGuardBehaviorTest : McpPlatformTestCase() {

    /** VirtualFiles marked read-only during the test, restored in tearDown. */
    private val readOnlyFiles = mutableListOf<com.intellij.openapi.vfs.VirtualFile>()

    override fun tearDown() {
        try {
            for (vf in readOnlyFiles) {
                runCatching {
                    File(vf.path).setWritable(true)
                    vf.refresh(false, false)
                }
            }
            readOnlyFiles.clear()
        } finally {
            super.tearDown()
        }
    }

    private fun markReadOnly(relativePath: String): com.intellij.openapi.vfs.VirtualFile {
        val basePath = requireNotNull(project.basePath)
        val vf = requireNotNull(
            LocalFileSystem.getInstance().refreshAndFindFileByPath("$basePath/$relativePath")
        ) { "Cannot find VFS entry for $relativePath" }
        File(vf.path).setWritable(false)
        vf.refresh(false, false)
        readOnlyFiles.add(vf)
        return vf
    }

    // ── ReplaceTextInFileTool ──

    fun testReplaceTextRejectsReadOnlyFile() = runBlocking {
        val original = "public class Frozen { int x = 1; }"
        writeProjectFile("src/Frozen.java", original)
        markReadOnly("src/Frozen.java")

        val result = ReplaceTextInFileTool().execute(project, buildJsonObject {
            put("file", "src/Frozen.java")
            put("searchText", "x = 1")
            put("replaceText", "x = 2")
        })

        assertToolFailed("Should reject read-only file", result)
        assertTrue("Error should mention read-only", toolText(result).contains("read-only"))
        assertEquals("File must be unchanged", original, readProjectFileVfs("src/Frozen.java"))
    }

    // ── RenameSymbolTool — symbol mode ──

    fun testRenameSymbolRejectsReadOnlyFile() = runBlocking {
        val original = """
            public class Immutable {
                public void hello() {}
            }
        """.trimIndent()
        writeProjectFile("src/Immutable.java", original)
        markReadOnly("src/Immutable.java")

        val result = RenameSymbolTool().execute(project, buildJsonObject {
            put("file", "src/Immutable.java")
            put("targetType", "symbol")
            put("line", 2)
            put("column", 17)
            put("newName", "goodbye")
        })

        assertToolFailed("Should reject read-only file for symbol rename", result)
        assertTrue("Error should mention read-only", toolText(result).contains("read-only"))
        assertEquals("File must be unchanged", original, readProjectFileVfs("src/Immutable.java"))
    }

    // ── RenameSymbolTool — file mode ──

    fun testRenameFileRejectsReadOnlyFile() = runBlocking {
        val original = "public class Locked {}"
        writeProjectFile("src/Locked.java", original)
        markReadOnly("src/Locked.java")

        val result = RenameSymbolTool().execute(project, buildJsonObject {
            put("file", "src/Locked.java")
            put("targetType", "file")
            put("newName", "Unlocked.java")
        })

        assertToolFailed("Should reject read-only file for file rename", result)
        assertTrue("Error should mention read-only", toolText(result).contains("read-only"))
        assertProjectFileExists("src/Locked.java")
        assertEquals("File must be unchanged", original, readProjectFileVfs("src/Locked.java"))
    }

    // ── RenameSymbolTool — read-only file in scope, not the target (issue #310) ──

    fun testRenameSymbolRejectsReadOnlyReferencingFile() = runBlocking {
        registerSourceRoot("ro-scope-rename")
        writeProjectFile(
            "ro-scope-rename/roscope/Target.java", """
            package roscope;

            public class Target {
                public String getDisplayName() {
                    return "name";
                }
            }
            """.trimIndent()
        )
        val callerOriginal = """
            package roscope;

            public class Caller {
                public String describe(Target target) {
                    return target.getDisplayName();
                }
            }
        """.trimIndent()
        writeProjectFile("ro-scope-rename/roscope/Caller.java", callerOriginal)
        markReadOnly("ro-scope-rename/roscope/Caller.java")

        val result = RenameSymbolTool().execute(project, buildJsonObject {
            put("file", "ro-scope-rename/roscope/Target.java")
            put("line", 4)
            put("column", 19)
            put("newName", "getFullName")
        })

        assertToolFailed("Should reject read-only referencing file", result)
        assertTrue(
            "Error should name the read-only scope, was: ${toolText(result)}",
            toolText(result).contains("read-only files in the refactoring scope")
        )
        assertTrue(
            "Error should list the read-only file, was: ${toolText(result)}",
            toolText(result).contains("Caller.java")
        )
        assertEquals("Caller must be unchanged", callerOriginal, readProjectFileVfs("ro-scope-rename/roscope/Caller.java"))
        assertFalse(
            "Target must be unchanged",
            readProjectFileVfs("ro-scope-rename/roscope/Target.java").contains("getFullName")
        )
    }

    // ── ChangeSignatureTool — read-only file in scope, not the target (issue #310) ──

    fun testChangeSignatureRejectsReadOnlyReferencingFile() = runBlocking {
        registerSourceRoot("ro-scope-sig")
        writeProjectFile(
            "ro-scope-sig/rosig/Api.java", """
            package rosig;

            public class Api {
                public int compute(int value) {
                    return value;
                }
            }
            """.trimIndent()
        )
        val clientOriginal = """
            package rosig;

            public class ApiClient {
                public int use(Api api) {
                    return api.compute(1);
                }
            }
        """.trimIndent()
        writeProjectFile("ro-scope-sig/rosig/ApiClient.java", clientOriginal)
        markReadOnly("ro-scope-sig/rosig/ApiClient.java")

        val result = ChangeSignatureTool().execute(project, buildJsonObject {
            put("file", "ro-scope-sig/rosig/Api.java")
            put("line", 4)
            put("column", 16)
            put("newName", "calculate")
        })

        assertToolFailed("Should reject read-only referencing file", result)
        assertTrue(
            "Error should name the read-only scope, was: ${toolText(result)}",
            toolText(result).contains("read-only files in the refactoring scope")
        )
        assertTrue(
            "Error should list the read-only file, was: ${toolText(result)}",
            toolText(result).contains("ApiClient.java")
        )
        assertEquals("Client must be unchanged", clientOriginal, readProjectFileVfs("ro-scope-sig/rosig/ApiClient.java"))
        assertFalse(
            "Api must be unchanged",
            readProjectFileVfs("ro-scope-sig/rosig/Api.java").contains("calculate")
        )
    }

    // ── SafeDeleteTool — symbol mode ──

    fun testSafeDeleteSymbolRejectsReadOnlyFile() = runBlocking {
        val original = """
            public class Protected {
                public void doNotDelete() {}
            }
        """.trimIndent()
        writeProjectFile("src/Protected.java", original)
        markReadOnly("src/Protected.java")

        val result = SafeDeleteTool().execute(project, buildJsonObject {
            put("file", "src/Protected.java")
            put("target_type", "symbol")
            put("line", 2)
            put("column", 17)
        })

        assertToolFailed("Should reject read-only file for safe delete symbol", result)
        assertTrue("Error should mention read-only", toolText(result).contains("read-only"))
        assertEquals("File must be unchanged", original, readProjectFileVfs("src/Protected.java"))
    }

    // ── SafeDeleteTool — file mode ──

    fun testSafeDeleteFileRejectsReadOnlyFile() = runBlocking {
        val original = "public class Guarded {}"
        writeProjectFile("src/Guarded.java", original)
        markReadOnly("src/Guarded.java")

        val result = SafeDeleteTool().execute(project, buildJsonObject {
            put("file", "src/Guarded.java")
            put("target_type", "file")
        })

        assertToolFailed("Should reject read-only file for safe delete file", result)
        assertTrue("Error should mention read-only", toolText(result).contains("read-only"))
        assertProjectFileExists("src/Guarded.java")
        assertEquals("File must be unchanged", original, readProjectFileVfs("src/Guarded.java"))
    }
}
