package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class RenameSymbolToolPlatformTest : BasePlatformTestCase() {

    fun testRenameFieldAlsoRenamesMatchingConstructorParameterWithoutPrompt() = runBlocking {
        DumbService.getInstance(project).waitForSmartMode()

        val basePath = project.basePath ?: error("Project base path should be available")
        val file = File(basePath, "UserProfile.java")
        Files.createDirectories(file.parentFile.toPath())
        Files.writeString(
            file.toPath(),
            """
            public class UserProfile {
                private final String name;

                public UserProfile(String name) {
                    this.name = name;
                }

                public String getName() {
                    return name;
                }
            }
            """.trimIndent()
        )

        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(file.absolutePath)
        assertNotNull("UserProfile.java should resolve in the VFS", virtualFile)

        val psiFile = PsiManager.getInstance(project).findFile(virtualFile!!)
        assertNotNull("UserProfile.java should have PSI", psiFile)

        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile!!)
        assertNotNull("UserProfile.java should have a document", document)

        val fieldOffset = document!!.text.indexOf("name;")
        assertTrue("Should find the field declaration", fieldOffset >= 0)

        val line = document.getLineNumber(fieldOffset) + 1
        val column = fieldOffset - document.getLineStartOffset(line - 1) + 1

        val result = RenameSymbolTool().execute(
            project,
            buildJsonObject {
                put("file", "UserProfile.java")
                put("line", line)
                put("column", column)
                put("newName", "customerName")
            }
        )

        assertFalse("Rename should succeed without prompting", result.isError)

        val updatedText = Files.readString(file.toPath())
        assertTrue(
            "Field declaration should be renamed",
            updatedText.contains("private final String customerName;")
        )
        assertTrue(
            "Matching constructor parameter should be renamed in the same refactoring",
            updatedText.contains("public UserProfile(String customerName)")
        )
        assertTrue(
            "Field references should be updated",
            updatedText.contains("this.customerName = customerName;")
        )
        assertTrue(
            "Getter should also be renamed through automatic related renames",
            updatedText.contains("public String getCustomerName()")
        )
    }
}
