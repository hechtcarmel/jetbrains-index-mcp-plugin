package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project

import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.BuildSystemLinker
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.LinkResult
import com.intellij.openapi.Disposable
import com.intellij.openapi.externalSystem.autolink.ExternalSystemProjectLinkListener
import com.intellij.openapi.externalSystem.autolink.ExternalSystemUnlinkedProjectAware
import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.ExtensionTestUtil
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

class LinkBuildSystemToolBehaviorTest : McpPlatformTestCase() {

    private val mavenSystemId = ProjectSystemId("MAVEN")

    fun testNoBuildFileReturnedWhenDirectoryIsEmpty() {
        val fakeAware = FakeUnlinkedProjectAware(mavenSystemId, buildFileName = "pom.xml")
        withFakeAware(fakeAware) {
            val tempDir = createTempDir("link-test-no-build")
            try {
                val result = BuildSystemLinker.linkBuildSystem(project, tempDir.absolutePath)
                assertTrue("Should be NoBuildFile, was: $result", result is LinkResult.NoBuildFile)
            } finally {
                tempDir.deleteRecursively()
            }
        }
    }

    fun testLinksWhenBuildFileExistsAndNotLinked() {
        val fakeAware = FakeUnlinkedProjectAware(mavenSystemId, buildFileName = "pom.xml")
        withFakeAware(fakeAware) {
            val tempDir = createTempDir("link-test-unlinked")
            try {
                File(tempDir, "pom.xml").writeText("<project/>")
                val result = BuildSystemLinker.linkBuildSystem(project, tempDir.absolutePath)
                assertTrue("Should be Linked, was: $result", result is LinkResult.Linked)
                assertTrue("linkAndLoadProjectAsync should have been called", fakeAware.linkCalled)
            } finally {
                tempDir.deleteRecursively()
            }
        }
    }

    fun testReportsAlreadyLinkedWithoutCallingLink() {
        val fakeAware = FakeUnlinkedProjectAware(mavenSystemId, buildFileName = "pom.xml", linked = true)
        withFakeAware(fakeAware) {
            val tempDir = createTempDir("link-test-already")
            try {
                File(tempDir, "pom.xml").writeText("<project/>")
                val result = BuildSystemLinker.linkBuildSystem(project, tempDir.absolutePath)
                assertTrue("Should be AlreadyLinked, was: $result", result is LinkResult.AlreadyLinked)
                assertFalse("linkAndLoadProjectAsync should NOT have been called", fakeAware.linkCalled)
            } finally {
                tempDir.deleteRecursively()
            }
        }
    }

    fun testReportsErrorForNonExistentPath() = runBlocking {
        val result = LinkBuildSystemTool().execute(project, buildJsonObject {
            put("path", "/tmp/does-not-exist-link-test-${System.nanoTime()}")
        })
        assertToolFailed("Should fail for non-existent path", result)
        assertTrue("Should mention directory", toolText(result).contains("not a directory"))
    }

    fun testReportsPluginUnavailableWhenNoEpRegistered() = runBlocking {
        val tempDir = createTempDir("link-test-no-ep")
        try {
            val result = BuildSystemLinker.linkBuildSystem(project, tempDir.absolutePath)
            assertTrue("Should be PluginUnavailable, was: $result", result is LinkResult.PluginUnavailable)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun withFakeAware(aware: ExternalSystemUnlinkedProjectAware, body: suspend () -> Unit) = runBlocking {
        ExtensionTestUtil.maskExtensions(
            ExternalSystemUnlinkedProjectAware.Companion.EP_NAME,
            listOf(aware),
            testRootDisposable
        )
        body()
    }

    private fun createTempDir(prefix: String): File =
        java.nio.file.Files.createTempDirectory(prefix).toFile()

    private class FakeUnlinkedProjectAware(
        override val systemId: ProjectSystemId,
        private val buildFileName: String,
        private var linked: Boolean = false
    ) : ExternalSystemUnlinkedProjectAware {

        var linkCalled = false
            private set

        override fun isBuildFile(project: Project, file: VirtualFile): Boolean =
            file.name == buildFileName

        override fun isLinkedProject(project: Project, externalProjectPath: String): Boolean = linked

        override suspend fun linkAndLoadProjectAsync(project: Project, externalProjectPath: String) {
            linkCalled = true
            linked = true
        }

        override suspend fun unlinkProject(project: Project, externalProjectPath: String) {
            linked = false
        }

        override fun subscribe(
            project: Project,
            listener: ExternalSystemProjectLinkListener,
            parentDisposable: Disposable
        ) {}
    }
}
