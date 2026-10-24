package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.PsiTestUtil
import io.mockk.every
import io.mockk.mockk
import org.junit.Assume.assumeNoException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

class ProjectUtilsPathAliasesTest : McpPlatformTestCase() {
    private lateinit var workspace: Path
    private val addedRootUrls = mutableSetOf<String>()

    override fun setUp() {
        super.setUp()
        workspace = Files.createTempDirectory("project-utils-alias-450-").toRealPath()
    }

    override fun tearDown() {
        try {
            ModuleRootModificationUtil.updateModel(module) { model ->
                model.contentEntries.filter { it.url in addedRootUrls }.forEach(model::removeContentEntry)
            }
            addedRootUrls.clear()
            Files.walk(workspace).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        } finally {
            super.tearDown()
        }
    }

    fun testBaseAliasesKeepMembershipAndRelativePathsInBothDirections() {
        val (root, alias, file) = aliasFixture()
        assertFalse("The real index must not rescue the path fallback", ProjectFileIndex.getInstance(project).isInContent(file))
        val viaAlias = projectWithIndexFallback(alias.toString())
        assertTrue(ProjectUtils.isProjectFile(viaAlias, file))
        assertEquals("src/A.txt", ProjectUtils.getRelativePath(viaAlias, file))
        assertEquals("src/A.txt", ProjectUtils.getToolFilePath(viaAlias, file))

        val aliasFile = virtualFile(alias.resolve("src/A.txt"))
        val viaLongName = projectWithIndexFallback(root.toString())
        assertTrue(ProjectUtils.isProjectFile(viaLongName, aliasFile))
        assertEquals("src/A.txt", ProjectUtils.getRelativePath(viaLongName, aliasFile))
        val outside = virtualFile(Files.writeString(workspace.resolve("Outside.txt"), "outside"))
        assertFalse(ProjectUtils.isProjectFile(viaAlias, outside))
        assertEquals(outside.path, ProjectUtils.getRelativePath(viaAlias, outside))
    }

    fun testUnavailableIndexStillUsesAliasAwareFallback() {
        val (_, alias, file) = aliasFixture()
        val view = projectWithIndexFallback(alias.toString(), unavailable = true)
        assertTrue(ProjectUtils.isProjectFile(view, file))
        assertEquals("src/A.txt", ProjectUtils.getRelativePath(view, file.path))
    }

    fun testExternalContentRootAliasPreservesRegisteredRootAndAbsoluteOutput() {
        val (_, alias, file) = aliasFixture()
        val aliasRoot = virtualFile(alias)
        PsiTestUtil.addContentRoot(module, aliasRoot)
        addedRootUrls += aliasRoot.url
        val view = projectWithIndexFallback(project.basePath)
        assertEquals(aliasRoot.path, ProjectUtils.findMatchingContentRoot(view, file.path))
        assertTrue(ProjectUtils.isProjectFile(view, file))
        assertEquals("External workspace files must not lose their root", file.path, ProjectUtils.getRelativePath(view, file))
        assertEquals(file.path, ProjectUtils.getToolFilePath(view, file))
        val noBase = projectWithIndexFallback(null)
        assertTrue("A registered module root does not require a project base", ProjectUtils.isProjectFile(noBase, file))
        assertEquals(file.path, ProjectUtils.getRelativePath(noBase, file))
    }

    fun testPositiveIndexMembershipStillWinsWithoutReadingThePath() {
        val view = mockk<Project>()
        val index = mockk<ProjectFileIndex>()
        val file = mockk<VirtualFile>()
        every { view.getService(ProjectFileIndex::class.java) } returns index
        every { index.isInContent(file) } returns true
        assertTrue(ProjectUtils.isProjectFile(view, file))
    }

    private fun projectWithIndexFallback(basePath: String?, unavailable: Boolean = false): Project {
        val view = mockk<Project>()
        val index = mockk<ProjectFileIndex>()
        every { view.basePath } returns basePath
        every { view.getService(ModuleManager::class.java) } returns ModuleManager.getInstance(project)
        every { index.isInContent(any()) } returns false
        if (unavailable) {
            every { view.getService(ProjectFileIndex::class.java) } throws IllegalStateException("Index unavailable in fixture")
        } else {
            every { view.getService(ProjectFileIndex::class.java) } returns index
        }
        return view
    }

    private fun aliasFixture(): Triple<Path, Path, VirtualFile> {
        val root = Files.createDirectory(workspace.resolve("Long project name"))
        val alias = workspace.resolve("SHORT~1")
        try {
            Files.createSymbolicLink(alias, root)
        } catch (e: IOException) {
            assumeNoException("Symbolic links unavailable", e)
        } catch (e: UnsupportedOperationException) {
            assumeNoException("Symbolic links unsupported", e)
        } catch (e: SecurityException) {
            assumeNoException("Symbolic links not permitted", e)
        }
        val file = Files.writeString(Files.createDirectory(root.resolve("src")).resolve("A.txt"), "fixture")
        assertTrue(Files.isSameFile(file, alias.resolve("src/A.txt")))
        return Triple(root, alias, virtualFile(file))
    }

    private fun virtualFile(path: Path): VirtualFile =
        requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path))
}
