package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project

import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.intellij.openapi.Disposable
import com.intellij.openapi.externalSystem.autolink.ExternalSystemProjectLinkListener
import com.intellij.openapi.externalSystem.autolink.ExternalSystemUnlinkedProjectAware
import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.LoggedErrorProcessor
import kotlinx.coroutines.runBlocking
import java.io.File

class OpenProjectContentRootGuardTest : McpPlatformTestCase() {

    fun testNoOpWhenContentRootAlreadyExists() {
        val result = runBlocking {
            OpenProjectTool().ensureContentRoot(project, project.basePath!!)
        }
        assertNull("Should return null when content root already exists", result)
    }

    fun testBuildFileGuardDetectsBuildFiles() {
        val fakeAware = FakeBuildSystemAware("fake-build.xml")
        ExtensionTestUtil.maskExtensions(
            ExternalSystemUnlinkedProjectAware.Companion.EP_NAME,
            listOf(fakeAware),
            testRootDisposable
        )
        writeProjectFile("fake-build.xml", "<project/>")

        val hasBuild = runBlocking {
            OpenProjectTool().hasBuildFileDetected(project, project.basePath!!)
        }
        assertTrue("hasBuildFileDetected should find the build file", hasBuild)
    }
}

/**
 * Tests the actual module-creation path of [OpenProjectTool.ensureContentRoot].
 *
 * Extends [HeavyPlatformTestCase] because the light test infrastructure blocks module
 * creation with "Adding modules is not permitted in light tests".
 */
class OpenProjectContentRootRegistrationTest : HeavyPlatformTestCase() {

    fun testRegistersModuleWithSdkAndExcludesWhenNoContentRoots() = suppressErrors {
        removeAllContentRoots()
        val testDir = createTestDir("plain-project")

        val result = runBlocking {
            OpenProjectTool().ensureContentRoot(myProject, testDir.absolutePath)
        }

        assertNotNull("Should register a content root", result)
        assertTrue("Result should mention content root: $result", result!!.contains("Content root registered"))
        assertTrue("Result should mention module name: $result", result.contains("Module '${testDir.name}'"))
        assertTrue("Result should mention .idea/ path: $result", result.contains(".idea/"))
        assertTrue("Result should list excluded dirs: $result", result.contains("node_modules"))

        val updatedModules = ModuleManager.getInstance(myProject).modules
        val created = updatedModules.find { it.moduleFilePath.contains(".idea/") }
        assertNotNull("Module file should be in .idea/ directory", created)
        assertTrue("Module should inherit project SDK",
            ModuleRootManager.getInstance(created!!).isSdkInherited)

        val excludeUrls = ModuleRootManager.getInstance(created).contentEntries
            .flatMap { it.excludeFolderUrls }
        for (exclude in OpenProjectTool.DEFAULT_EXCLUDES) {
            val expectedUrl = VfsUtilCore.pathToUrl(File(testDir, exclude).absolutePath)
            assertTrue(
                "Exclude URL for '$exclude' should be present by URL: $excludeUrls",
                excludeUrls.any { it == expectedUrl }
            )
        }
    }

    fun testSecondCallIsNoOp() = suppressErrors {
        val testDir = createTestDir("plain-project-noop")
        runBlocking { OpenProjectTool().ensureContentRoot(myProject, testDir.absolutePath) }

        val result = runBlocking {
            OpenProjectTool().ensureContentRoot(myProject, testDir.absolutePath)
        }
        assertNull("Second call should be no-op when content root exists", result)
    }

    private fun createTestDir(name: String): File {
        val dir = File(requireNotNull(myProject.basePath), name)
        dir.mkdirs()
        com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByPath(dir.absolutePath)
        return dir
    }

    private fun removeAllContentRoots() {
        val modules = ModuleManager.getInstance(myProject).modules
        val hasRoots = modules.any { ModuleRootManager.getInstance(it).contentRoots.isNotEmpty() }
        if (!hasRoots) return
        com.intellij.openapi.application.WriteAction.run<Exception> {
            for (mod in modules) {
                val rootModel = ModuleRootManager.getInstance(mod).modifiableModel
                for (entry in rootModel.contentEntries) { rootModel.removeContentEntry(entry) }
                rootModel.commit()
            }
        }
    }

    private fun suppressErrors(action: () -> Unit) {
        val token = LoggedErrorProcessor.executeWith(
            object : LoggedErrorProcessor() {
                override fun processError(
                    category: String, message: String,
                    details: Array<out String>, t: Throwable?
                ): MutableSet<Action> {
                    if (t is NoSuchMethodError || message.contains("Workspace Model event handling")) {
                        return Action.NONE
                    }
                    return Action.ALL
                }
            }
        )
        try { action() } finally { token.finish() }
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
