package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project

import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.suppressWorkspaceModelErrors
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.externalSystem.autolink.ExternalSystemProjectLinkListener
import com.intellij.openapi.externalSystem.autolink.ExternalSystemUnlinkedProjectAware
import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.ExtensionTestUtil
import kotlinx.coroutines.runBlocking
import java.io.File

class WorkspacePlainModuleBehaviorTest : McpPlatformTestCase() {

    private val createdModuleNames = mutableListOf<String>()

    override fun tearDown() {
        try {
            if (createdModuleNames.isNotEmpty()) {
                WriteAction.run<Exception> {
                    val manager = ModuleManager.getInstance(project)
                    for (name in createdModuleNames) {
                        manager.findModuleByName(name)?.let { manager.disposeModule(it) }
                    }
                }
                createdModuleNames.clear()
            }
        } finally {
            super.tearDown()
        }
    }

    fun testRegistersPlainDirectoryAsContentRoot() = suppressWorkspaceModelErrors {
        val dir = plainDir("ws-plain-reg")
        val workspaceDir = tempWorkspaceDir()
        createdModuleNames.add(dir.name)

        runBlocking {
            OpenWorkspaceTool().registerPlainDirectories(project, listOf(dir), workspaceDir)
        }

        val modules = ModuleManager.getInstance(project).modules
        val created = modules.find { it.name == dir.name }
        assertNotNull("Module should be created for plain directory", created)
        assertTrue("Module file should be in workspace .idea/modules/",
            created!!.moduleFilePath.contains(".idea/modules/"))

        val contentRoots = ModuleRootManager.getInstance(created).contentRoots
        assertTrue("Module should have a content root", contentRoots.isNotEmpty())

        val excludeUrls = ModuleRootManager.getInstance(created).contentEntries
            .flatMap { it.excludeFolderUrls }
        for (exclude in OpenWorkspaceTool.DEFAULT_PLAIN_EXCLUDES) {
            val expectedUrl = VfsUtilCore.pathToUrl(File(dir, exclude).absolutePath)
            assertTrue(
                "Exclude '$exclude' should be present by URL even if dir doesn't exist: $excludeUrls",
                excludeUrls.any { it == expectedUrl }
            )
        }
    }

    fun testSkipsAlreadyRegisteredRoot() = suppressWorkspaceModelErrors {
        val dir = plainDir("ws-plain-skip")
        val workspaceDir = tempWorkspaceDir()
        createdModuleNames.add(dir.name)

        runBlocking {
            OpenWorkspaceTool().registerPlainDirectories(project, listOf(dir), workspaceDir)
        }
        val modulesBefore = ModuleManager.getInstance(project).modules.size

        runBlocking {
            OpenWorkspaceTool().registerPlainDirectories(project, listOf(dir), workspaceDir)
        }
        val modulesAfter = ModuleManager.getInstance(project).modules.size

        assertEquals("Second call should not create duplicate module", modulesBefore, modulesAfter)
    }

    fun testHandlesModuleNameClash() = suppressWorkspaceModelErrors {
        val dir1 = plainDir("parent-a/packages")
        val dir2 = plainDir("parent-b/packages")
        val workspaceDir = tempWorkspaceDir()

        runBlocking {
            OpenWorkspaceTool().registerPlainDirectories(project, listOf(dir1), workspaceDir)
        }
        val firstName = dir1.name
        createdModuleNames.add(firstName)

        runBlocking {
            OpenWorkspaceTool().registerPlainDirectories(project, listOf(dir2), workspaceDir)
        }

        val modules = ModuleManager.getInstance(project).modules
        val packageModules = modules.filter { it.name.contains("packages") }
        assertTrue("Should have two modules for the two packages dirs", packageModules.size >= 2)

        val secondName = packageModules.find { it.name != firstName }?.name
        assertNotNull("Second module should have a unique name", secondName)
        createdModuleNames.add(secondName!!)
        assertTrue("Second module name should include parent dir prefix: $secondName",
            secondName.contains("parent-b"))
    }

    fun testSkipsDirectoriesWithBuildFiles() = suppressWorkspaceModelErrors {
        val gradleDir = plainDir("ws-gradle-proj")
        writeProjectFile("ws-gradle-proj/build.gradle", "apply plugin: 'java'")
        val workspaceDir = tempWorkspaceDir()

        val fakeAware = FakeBuildSystemAware("build.gradle")
        ExtensionTestUtil.maskExtensions(
            ExternalSystemUnlinkedProjectAware.Companion.EP_NAME,
            listOf(fakeAware),
            testRootDisposable
        )

        runBlocking {
            OpenWorkspaceTool().registerPlainDirectories(project, listOf(gradleDir), workspaceDir)
        }

        val modules = ModuleManager.getInstance(project).modules
        val created = modules.find { it.name == gradleDir.name }
        assertNull("Should not create a module for a directory with a build file", created)
    }

    private fun plainDir(relativePath: String): File {
        writeProjectFile("$relativePath/index.ts", "export default {};")
        return writeProjectFile("$relativePath/package.json", """{"name": "test"}""").parent.toFile()
    }

    private fun tempWorkspaceDir(): File {
        val dir = File(project.basePath!!, ".test-workspace-${System.nanoTime()}")
        dir.mkdirs()
        return dir
    }

    private class FakeBuildSystemAware(
        private val buildFileName: String
    ) : ExternalSystemUnlinkedProjectAware {
        override val systemId = ProjectSystemId("FAKE_BUILD")
        override fun isBuildFile(project: Project, file: VirtualFile): Boolean =
            file.name == buildFileName
        override fun isLinkedProject(project: Project, externalProjectPath: String): Boolean = false
        override suspend fun linkAndLoadProjectAsync(project: Project, externalProjectPath: String) {}
        override suspend fun unlinkProject(project: Project, externalProjectPath: String) {}
        override fun subscribe(project: Project, listener: ExternalSystemProjectLinkListener, parentDisposable: Disposable) {}
    }
}
