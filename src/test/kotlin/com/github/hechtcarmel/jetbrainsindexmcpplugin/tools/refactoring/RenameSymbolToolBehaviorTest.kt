package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PluginDetectors
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ContentBlock
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.RefactoringResult
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class RenameSymbolToolBehaviorTest : BasePlatformTestCase() {

    private val json = Json {
        ignoreUnknownKeys = true
    }

    private fun writeProjectFile(relativePath: String, content: String): Path {
        val basePath = requireNotNull(project.basePath)
        val path = Path.of(basePath, relativePath)
        Files.createDirectories(path.parent)
        Files.writeString(path, content)
        requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByPath(path.toString())) {
            "Failed to refresh VFS for test file ${path}"
        }
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        return path
    }

    private fun requireJsTsToolRoutingCapability(testName: String): Boolean {
        if (!PluginDetectors.javaScript.isAvailable) {
            System.err.println("$testName: skipped - JavaScript plugin not available")
            return false
        }

        return try {
            Class.forName("com.intellij.lang.javascript.psi.JSNamedElement")
            true
        } catch (_: ClassNotFoundException) {
            System.err.println("$testName: skipped - JavaScript PSI classes unavailable")
            false
        }
    }

    fun testJsTsFileRenameRetargetsImportsThroughSemanticHooks() = runBlocking {
        if (!requireJsTsToolRoutingCapability("testJsTsFileRenameRetargetsImportsThroughSemanticHooks")) return@runBlocking

        writeProjectFile(
            "src/utils/leaf.ts",
            "export const leafThing = 1;\n"
        )
        writeProjectFile(
            "src/app.ts",
            """
            import { leafThing } from './utils/leaf';
            import './utils/leaf';
            export { leafThing } from './utils/leaf';
            const lazy = import('./utils/leaf');
            const nested = {
              leaf: leafThing,
            };
            """.trimIndent()
        )

        val result = RenameSymbolTool().execute(project, buildJsonObject {
            put("file", "src/utils/leaf.ts")
            put("targetType", "file")
            put("line", 0)
            put("column", 0)
            put("newName", "leaf-renamed.ts")
        })

        assertFalse("JS/TS file rename should succeed", result.isError)
        val payload = json.decodeFromString<RefactoringResult>((result.content.single() as ContentBlock.Text).text)

        val basePath = requireNotNull(project.basePath)
        assertTrue(Files.exists(Path.of(basePath, "src/utils/leaf-renamed.ts")))
        assertFalse(Files.exists(Path.of(basePath, "src/utils/leaf.ts")))

        val appText = Files.readString(Path.of(basePath, "src/app.ts"))
        assertTrue(appText.contains("import { leafThing } from './utils/leaf-renamed';"))
        assertTrue(appText.contains("import './utils/leaf-renamed';"))
        assertTrue(appText.contains("export { leafThing } from './utils/leaf-renamed';"))
        assertTrue(appText.contains("import('./utils/leaf-renamed')"))
        assertFalse(appText.contains("./utils/leaf'"))
        assertFalse(appText.contains("./utils/leaf\""))
        assertTrue(payload.affectedFiles.contains("src/utils/leaf-renamed.ts"))
        assertTrue(payload.affectedFiles.contains("src/app.ts"))
        assertEquals(payload.affectedFiles.size, payload.changesCount)
        assertNull(payload.unretargetedImporters)
        IndexingTestUtil.waitUntilIndexesAreReady(project)
    }

    fun testJsTsSameDirectoryFileRenameDoesNotRetargetDirectorySegment() = runBlocking {
        if (!requireJsTsToolRoutingCapability("testJsTsSameDirectoryFileRenameDoesNotRetargetDirectorySegment")) return@runBlocking

        writeProjectFile(
            "src/jobs/generate-recurring-gastos.logic.ts",
            "export const calculateOverduePeriods = () => 0;\nexport const MAX_OVERDUE_ITERATIONS = 24;\n"
        )
        writeProjectFile(
            "src/jobs/generate-recurring-gastos.ts",
            """
            import { calculateOverduePeriods, MAX_OVERDUE_ITERATIONS } from './generate-recurring-gastos.logic';
            export { calculateOverduePeriods, MAX_OVERDUE_ITERATIONS } from './generate-recurring-gastos.logic';
            """.trimIndent()
        )
        writeProjectFile(
            "tests/generate-recurring-gastos.test.ts",
            """
            import { calculateOverduePeriods, MAX_OVERDUE_ITERATIONS } from '../src/jobs/generate-recurring-gastos.logic.ts';
            void calculateOverduePeriods;
            void MAX_OVERDUE_ITERATIONS;
            """.trimIndent()
        )

        val result = RenameSymbolTool().execute(project, buildJsonObject {
            put("file", "src/jobs/generate-recurring-gastos.logic.ts")
            put("targetType", "file")
            put("newName", "generate-recurring-gastos.logic_smoke.ts")
        })

        assertFalse("JS/TS same-directory file rename should succeed", result.isError)
        val payload = json.decodeFromString<RefactoringResult>((result.content.single() as ContentBlock.Text).text)

        val basePath = requireNotNull(project.basePath)
        val jobText = Files.readString(Path.of(basePath, "src/jobs/generate-recurring-gastos.ts"))
        val testText = Files.readString(Path.of(basePath, "tests/generate-recurring-gastos.test.ts"))

        assertTrue(jobText.contains("./generate-recurring-gastos.logic_smoke"))
        assertTrue(testText.contains("../src/jobs/generate-recurring-gastos.logic_smoke.ts"))
        assertFalse(jobText.contains("././generate-recurring-gastos.logic_smoke"))
        assertFalse(testText.contains("../src/jobs/./generate-recurring-gastos.logic_smoke.ts"))
        assertTrue(payload.affectedFiles.contains("src/jobs/generate-recurring-gastos.ts"))
        assertTrue(payload.affectedFiles.contains("tests/generate-recurring-gastos.test.ts"))
        assertNull(payload.unretargetedImporters)
        IndexingTestUtil.waitUntilIndexesAreReady(project)
    }

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

        assertFalse("Explicit file rename should ignore malformed line/column values: ${result.content}", result.isError)

        val basePath = requireNotNull(project.basePath)
        assertFalse(Files.exists(Path.of(basePath, "docs/readme.txt")))
        assertTrue(Files.exists(Path.of(basePath, "docs/readme-renamed.txt")))
    }
}
