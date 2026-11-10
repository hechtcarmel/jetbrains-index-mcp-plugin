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
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.ExtensionTestUtil
import kotlinx.coroutines.runBlocking
import java.io.File

class OpenProjectContentRootBehaviorTest : McpPlatformTestCase() {

    private var savedContentRootUrls: List<String> = emptyList()
    private val createdModuleNames = mutableListOf<String>()

    override fun setUp() {
        super.setUp()
        savedContentRootUrls = ModuleRootManager.getInstance(module).contentEntries
            .mapNotNull { it.url }
    }

    override fun tearDown() {
        try {
            WriteAction.run<Exception> {
                val manager = ModuleManager.getInstance(project)
                for (name in createdModuleNames) {
                    manager.findModuleByName(name)?.let { manager.disposeModule(it) }
                }
                val rootModel = ModuleRootManager.getInstance(module).modifiableModel
                val currentUrls = rootModel.contentEntries.map { it.url }.toSet()
                for (url in savedContentRootUrls) {
                    if (url !in currentUrls) {
                        rootModel.addContentEntry(url)
                    }
                }
                rootModel.commit()
            }
            createdModuleNames.clear()
            File(project.basePath!!, ".idea").let { ideaDir ->
                if (ideaDir.exists()) ideaDir.deleteRecursively()
            }
        } finally {
            super.tearDown()
        }
    }

    fun testRegistersContentRootWhenNoBuildSystemDetected() = suppressWorkspaceModelErrors {
        removeAllContentRoots()
        val projectName = File(project.basePath!!).name
        createdModuleNames.add(projectName)

        val result = runBlocking {
            OpenProjectTool().ensureContentRoot(project, project.basePath!!)
        }

        assertNotNull("Should return a message when content root is registered", result)
        assertTrue("Result should mention content root: $result", result!!.contains("Content root registered"))
        assertTrue("Result should mention module name: $result", result.contains("Module '"))
        assertTrue("Result should mention excludes: $result", result.contains("Excluded:"))

        val modules = ModuleManager.getInstance(project).modules
        assertTrue("Should have at least one module", modules.isNotEmpty())
        val contentRoots = modules.flatMap { ModuleRootManager.getInstance(it).contentRoots.toList() }
        assertTrue("Should have a content root at project base", contentRoots.any { it.path == project.basePath })

        val createdModule = modules.find { it.moduleFilePath.contains(".idea/") }
        assertNotNull("Module file should be in .idea/ directory", createdModule)

        val excludeUrls = modules.flatMap { module ->
            ModuleRootManager.getInstance(module).contentEntries.flatMap { it.excludeFolderUrls }
        }
        for (exclude in OpenProjectTool.DEFAULT_EXCLUDES) {
            val expectedUrl = VfsUtilCore.pathToUrl(File(project.basePath!!, exclude).absolutePath)
            assertTrue(
                "Exclude URL for '$exclude' should be present even if directory doesn't exist: $excludeUrls",
                excludeUrls.any { it == expectedUrl }
            )
        }
    }

    fun testNoOpWhenContentRootAlreadyExists() = suppressWorkspaceModelErrors {
        ensureModuleHasContentRoot()

        val result = runBlocking {
            OpenProjectTool().ensureContentRoot(project, project.basePath!!)
        }

        assertNull("Should return null when content root already exists", result)
    }

    fun testSkipsWhenBuildFilePresent() = suppressWorkspaceModelErrors {
        removeAllContentRoots()

        val fakeAware = FakeBuildSystemAware("fake-build.xml")
        ExtensionTestUtil.maskExtensions(
            ExternalSystemUnlinkedProjectAware.Companion.EP_NAME,
            listOf(fakeAware),
            testRootDisposable
        )

        writeProjectFile("fake-build.xml", "<project/>")

        val result = runBlocking {
            OpenProjectTool().ensureContentRoot(project, project.basePath!!)
        }

        assertNull("Should return null when build file is detected", result)
    }

    fun testResultTextDescribesWhatWasCreated() = suppressWorkspaceModelErrors {
        removeAllContentRoots()
        val projectName = File(project.basePath!!).name
        createdModuleNames.add(projectName)

        val result = runBlocking {
            OpenProjectTool().ensureContentRoot(project, project.basePath!!)
        }

        assertNotNull("Should return result text", result)
        assertTrue("Result should mention module name '$projectName': $result",
            result!!.contains("Module '$projectName'"))
        assertTrue("Result should mention .idea/ in iml path: $result",
            result.contains(".idea/"))
        assertTrue("Result should list excluded directories: $result",
            result.contains("node_modules"))
    }

    private fun ensureModuleHasContentRoot() {
        val hasRoots = ModuleManager.getInstance(project).modules.any {
            ModuleRootManager.getInstance(it).contentRoots.isNotEmpty()
        }
        if (!hasRoots) {
            val baseVf = LocalFileSystem.getInstance().refreshAndFindFileByPath(project.basePath!!) ?: return
            val rootModel = ModuleRootManager.getInstance(module).modifiableModel
            rootModel.addContentEntry(baseVf)
            WriteAction.run<Exception> { rootModel.commit() }
        }
    }

    private fun removeAllContentRoots() {
        val modules = ModuleManager.getInstance(project).modules
        for (mod in modules) {
            val rootModel = ModuleRootManager.getInstance(mod).modifiableModel
            val entries = rootModel.contentEntries
            for (entry in entries) {
                rootModel.removeContentEntry(entry)
            }
            WriteAction.run<Exception> { rootModel.commit() }
        }
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
