package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ContentBlock
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.RefactoringResult
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class MoveClassToolTest : BasePlatformTestCase() {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun testMoveClassToolMovesJavaClassAndUpdatesImports() = runBlocking {
        val basePath = project.basePath ?: error("Project base path should exist")
        val sourceRootIoDir = File(basePath, "src/main/java").apply {
            mkdirs()
        }
        val sourceRootVf = LocalFileSystem.getInstance().refreshAndFindFileByPath(sourceRootIoDir.absolutePath)
        assertNotNull("Source root should be visible in LocalFileSystem", sourceRootVf)
        PsiTestUtil.addSourceRoot(module, sourceRootVf!!)

        val serviceIoFile = File(sourceRootIoDir, "com/oldpkg/Service.java").apply {
            parentFile.mkdirs()
            writeText(
                """
            package com.oldpkg;

            public class Service {
                public String name() {
                    return "service";
                }
            }
                """.trimIndent()
            )
        }
        val usageIoFile = File(sourceRootIoDir, "com/app/UseService.java").apply {
            parentFile.mkdirs()
            writeText(
                """
            package com.app;

            import com.oldpkg.Service;

            public class UseService {
                private final Service service = new Service();
            }
                """.trimIndent()
            )
        }

        VfsUtil.markDirtyAndRefresh(false, true, true, sourceRootVf)
        val serviceVf = LocalFileSystem.getInstance().refreshAndFindFileByPath(serviceIoFile.absolutePath)
        val usageVf = LocalFileSystem.getInstance().refreshAndFindFileByPath(usageIoFile.absolutePath)
        assertNotNull("Service.java should be visible in LocalFileSystem", serviceVf)
        assertNotNull("UseService.java should be visible in LocalFileSystem", usageVf)
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val servicePsi = PsiManager.getInstance(project).findFile(serviceVf!!)
        assertNotNull("Service.java should resolve to PSI", servicePsi)
        val document = PsiDocumentManager.getInstance(project).getDocument(servicePsi!!)
        assertNotNull("Service.java should have a document", document)
        val offset = document!!.text.indexOf("Service")
        assertTrue("Should find Service class name", offset >= 0)
        val line = document.getLineNumber(offset) + 1
        val column = offset - document.getLineStartOffset(line - 1) + 1

        val tool = MoveClassTool()
        val result = tool.execute(project, buildJsonObject {
            put("file", "src/main/java/com/oldpkg/Service.java")
            put("line", line)
            put("column", column)
            put("targetPackage", "com.newpkg")
        })

        val rawText = (result.content.first() as ContentBlock.Text).text
        assertFalse("Move class should succeed, got: $rawText", result.isError)

        val payload = json.decodeFromString<RefactoringResult>(rawText)
        assertTrue("Result should report success", payload.success)
        assertTrue("Affected files should mention the new class path",
            payload.affectedFiles.any { it.endsWith("src/main/java/com/newpkg/Service.java") })

        val movedFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(
            File(sourceRootIoDir, "com/newpkg/Service.java").absolutePath
        )
        assertNotNull("Moved class file should exist at new package path", movedFile)
        val movedText = runReadAction { movedFile!!.inputStream.reader().readText() }
        assertTrue("Moved class should have updated package statement", movedText.contains("package com.newpkg;"))

        usageVf!!.refresh(false, false)
        val usageText = runReadAction { usageVf!!.inputStream.reader().readText() }
        assertTrue("Usage file should import the moved class", usageText.contains("import com.newpkg.Service;"))
        assertFalse("Usage file should not keep the old import", usageText.contains("import com.oldpkg.Service;"))
    }
}
