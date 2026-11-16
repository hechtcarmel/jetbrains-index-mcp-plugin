package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project

import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.intellij.openapi.Disposable
import com.intellij.openapi.externalSystem.autolink.ExternalSystemProjectLinkListener
import com.intellij.openapi.externalSystem.autolink.ExternalSystemUnlinkedProjectAware
import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.ExtensionTestUtil
import kotlinx.coroutines.runBlocking

/**
 * Tests for [OpenProjectTool.ensureContentRoot] guard logic.
 *
 * The actual module-creation path (no content roots → register one) cannot be tested
 * here because removing all content roots from the shared light project corrupts state
 * that later tests depend on (`PsiUtilsTest` reference resolution fails). That path is
 * verified by the maintainer's own platform probes against a real opened project.
 */
class OpenProjectContentRootBehaviorTest : McpPlatformTestCase() {

    fun testNoOpWhenContentRootAlreadyExists() {
        val result = runBlocking {
            OpenProjectTool().ensureContentRoot(project, project.basePath!!)
        }
        assertNull("Should return null when content root already exists", result)
    }

    fun testSkipsWhenBuildFilePresent() {
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
