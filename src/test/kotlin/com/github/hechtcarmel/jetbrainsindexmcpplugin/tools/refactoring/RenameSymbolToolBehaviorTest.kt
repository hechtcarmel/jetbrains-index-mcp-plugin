package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PluginDetectors
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class RenameSymbolToolBehaviorTest : BasePlatformTestCase() {

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

        val basePath = requireNotNull(project.basePath)
        assertTrue(Files.exists(Path.of(basePath, "src/utils/leaf-renamed.ts")))

        val appText = Files.readString(Path.of(basePath, "src/app.ts"))
        assertTrue(appText.contains("./utils/leaf-renamed"))
        assertFalse(appText.contains("./utils/leaf';"))
        IndexingTestUtil.waitUntilIndexesAreReady(project)
    }
}
