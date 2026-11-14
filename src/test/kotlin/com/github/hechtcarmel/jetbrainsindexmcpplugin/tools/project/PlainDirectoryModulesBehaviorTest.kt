package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project

import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.isFailure
import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.text
import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.suppressWorkspaceModelErrors
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PlainDirectoryModules
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ProjectUtils
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.MavenImportResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.cancellableEdtAction
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.externalSystem.autolink.ExternalSystemProjectLinkListener
import com.intellij.openapi.externalSystem.autolink.ExternalSystemUnlinkedProjectAware
import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.ModuleListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.HeavyPlatformTestCase
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * Carries forward Mark Proctor's #423/#447 registration cases, with the remaining safety
 * regressions. Heavy fixtures are required: light fixtures prohibit adding modules.
 * Tests do not open a real workspace window or perform a Maven import.
 */
class PlainDirectoryModulesBehaviorTest : HeavyPlatformTestCase() {
    override fun runInDispatchThread(): Boolean = false

    override fun setUp() {
        super.setUp()
        write {
            ModuleManager.getInstance(myProject).modules.forEach { module ->
                val model = ModuleRootManager.getInstance(module).modifiableModel
                model.contentEntries.forEach(model::removeContentEntry)
                model.commit()
            }
        }
    }

    private fun <T> write(action: () -> T): T = runBlocking {
        cancellableEdtAction { WriteAction.compute<T, RuntimeException> { action() } }
    }

    private fun dir(path: String): File = File(myProject.basePath!!, path).apply { check(mkdirs() || isDirectory) }
    private fun modulesDir(): File = File(myProject.basePath!!, ".idea/modules")
    private fun roots(): List<String> = ReadAction.compute<List<String>, RuntimeException> {
        ProjectUtils.getModuleContentRoots(myProject)
    }

    fun testRegistrationInheritsConfiguredSdkAndAddsFutureExcludesInMetadataDirectory() = suppressWorkspaceModelErrors {
        val root = dir("frontend")
        val originalSdk = ReadAction.compute<com.intellij.openapi.projectRoots.Sdk?, RuntimeException> { ProjectRootManager.getInstance(myProject).projectSdk }
        val sdk = write {
            val sdk = JavaSdk.getInstance().createJdk("plain-sdk-${System.nanoTime()}", System.getProperty("java.home"), false)
            ProjectJdkTable.getInstance().addJdk(sdk)
            ProjectRootManager.getInstance(myProject).projectSdk = sdk
            sdk
        }
        try {
            val registration = runBlocking { PlainDirectoryModules.register(myProject, listOf(root), modulesDir()) }.single()
            ReadAction.run<RuntimeException> {
                val module = requireNotNull(ModuleManager.getInstance(myProject).findModuleByName(registration.name))
                val model = ModuleRootManager.getInstance(module)
                assertTrue(model.isSdkInherited)
                assertSame(sdk, model.sdk)
                assertEquals(listOf(root.invariantSeparatorsPath), model.contentRoots.map { it.path })
                assertTrue(module.moduleFilePath.replace('\\', '/').startsWith(modulesDir().invariantSeparatorsPath + "/"))
                val exclusions = model.contentEntries.single().excludeFolderUrls.toList()
                PlainDirectoryModules.defaultExcludes.forEach { assertTrue(exclusions.any { url -> url.endsWith("/$it") }) }
                assertFalse(exclusions.any { it.endsWith("/build") || it.endsWith("/.gradle") })
            }
            assertFalse(File(root, registration.name + ".iml").exists())
        } finally {
            write {
                ProjectRootManager.getInstance(myProject).projectSdk = originalSdk
                ProjectJdkTable.getInstance().removeJdk(sdk)
            }
        }
    }

    fun testSecondaryNameCollisionsAndRepeatedRegistrationPreserveExistingModules() = suppressWorkspaceModelErrors {
        val first = dir("existing/packages")
        val second = dir("a/common/packages")
        val third = dir("b/common/packages")
        val registered = runBlocking { PlainDirectoryModules.register(myProject, listOf(first, second, third), modulesDir()) }
        assertEquals(listOf("packages", "common-packages", "common-packages-2"), registered.map { it.name })
        val count = ModuleManager.getInstance(myProject).modules.size
        assertTrue(runBlocking { PlainDirectoryModules.register(myProject, listOf(first, second, third), modulesDir()) }.isEmpty())
        assertEquals(count, ModuleManager.getInstance(myProject).modules.size)
    }

    fun testUnsupportedBuildIsRefusedBeforeAnyPartialRegistration() {
        val plain = dir("frontend")
        val build = dir("gradle")
        File(build, "build.gradle.kts").writeText("plugins {}")
        val before = ModuleManager.getInstance(myProject).modules.size
        val result = runCatching { runBlocking { PlainDirectoryModules.register(myProject, listOf(plain, build), modulesDir()) } }
        assertTrue(result.isFailure)
        assertEquals(before, ModuleManager.getInstance(myProject).modules.size)
        assertTrue(roots().isEmpty())
        assertFalse(modulesDir().exists())
    }

    fun testFailureAfterFirstRegistrationRollsBackOnlyNewModules() = suppressWorkspaceModelErrors {
        val first = dir("first")
        val second = dir("second")
        val secondVf = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByPath(second.path))
        var failureControlRan = false
        myProject.messageBus.connect(testRootDisposable).subscribe(ModuleListener.TOPIC, object : ModuleListener {
            override fun modulesAdded(project: Project, modules: List<Module>) {
                if (modules.any { it.name == "first" }) {
                    // Persist owned metadata to exercise filesystem cleanup as well as model rollback.
                    File(modules.single { it.name == "first" }.moduleFilePath).writeText("<module/>")
                    secondVf.delete(this)
                    failureControlRan = true
                }
            }
        })
        val before = ModuleManager.getInstance(myProject).modules.toSet()
        val failure = runCatching { runBlocking { PlainDirectoryModules.register(myProject, listOf(first, second), modulesDir()) } }
        assertTrue("failure control must invalidate the second root after module creation", failureControlRan)
        assertTrue(failure.exceptionOrNull()?.message.orEmpty().contains("disappeared"))
        assertEquals(before, ModuleManager.getInstance(myProject).modules.toSet())
        assertTrue(roots().isEmpty())
        assertFalse(File(modulesDir(), "first.iml").exists())
    }

    fun testCancellationDuringModuleCreationRollsBackAndStopsTheSecondModule() = suppressWorkspaceModelErrors {
        val first = dir("cancel-first")
        val second = dir("cancel-second")
        val job = Job()
        var controlRan = false
        myProject.messageBus.connect(testRootDisposable).subscribe(ModuleListener.TOPIC, object : ModuleListener {
            override fun modulesAdded(project: Project, modules: List<Module>) {
                if (modules.any { it.name == "cancel-first" }) {
                    controlRan = true
                    job.cancel()
                }
            }
        })
        val before = ModuleManager.getInstance(myProject).modules.toSet()
        val result = runCatching { runBlocking { withContext(job) {
            PlainDirectoryModules.register(myProject, listOf(first, second), modulesDir())
        } } }
        // Cancellation releases the waiting caller before the detached EDT action finishes cleanup.
        write { Unit }
        assertTrue("cancellation must happen inside the production registration", controlRan)
        assertTrue(result.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        assertEquals(before, ModuleManager.getInstance(myProject).modules.toSet())
        assertTrue(roots().isEmpty())
    }

    fun testOpenProjectRegistersBeforeExplicitExclusionsAndLeavesIndexReady() = suppressWorkspaceModelErrors {
        val root = dir("plain-project")
        File(dir("plain-project/docs"), "plan.md").writeText("fixture")
        val actions = mutableListOf<String>()
        assertTrue(runBlocking { OpenProjectTool().configureOpenedProject(myProject, root.path, false, listOf("docs"), actions) })
        assertTrue(actions.any { it.contains("Plain module") })
        assertTrue(actions.any { it.contains("Excluded: docs") })
        assertFalse(com.intellij.openapi.project.DumbService.isDumb(myProject))
        val before = ModuleManager.getInstance(myProject).modules.size
        runBlocking { OpenProjectTool().configureOpenedProject(myProject, root.path, false, emptyList(), mutableListOf()) }
        assertEquals(before, ModuleManager.getInstance(myProject).modules.size)
    }

    fun testBuildExtensionPreventsAutomaticPlainRoot() {
        val root = dir("recognized")
        File(root, "custom.build").writeText("fixture")
        ExtensionTestUtil.maskExtensions(ExternalSystemUnlinkedProjectAware.EP_NAME, listOf(FakeBuild()), testRootDisposable)
        val message = runBlocking { OpenProjectTool().ensureContentRoot(myProject, root.path) }
        assertTrue(message.orEmpty().contains("ide_link_build_system"))
        assertTrue(roots().isEmpty())
    }

    fun testMixedPlanAcceptsOneMavenAndPlainAndRejectsOtherBuildsBeforeMutation() {
        val maven = dir("backend").also { File(it, "pom.xml").writeText("<project/>") }
        val plain = dir("frontend")
        val gradle = dir("other-build").also { File(it, "settings.gradle.kts").writeText("") }
        val tool = OpenWorkspaceTool()
        val plan = runBlocking { tool.classifyModules(myProject, listOf(plain.path, maven.path, plain.path)) }.getOrThrow()
        assertEquals(1, plan.maven.size)
        assertEquals(1, plan.plain.size)
        assertEquals(2, plan.all.size)
        assertTrue(runBlocking { tool.classifyModules(myProject, listOf(maven.path, plain.path, gradle.path)) }.isFailure)
        assertTrue(runBlocking { tool.classifyModules(myProject, listOf(plain.path)) }.isFailure)
        assertTrue(roots().isEmpty())
    }

    fun testFreshMixedRequestReachesOpenPathWithoutSingleMavenRefusal() {
        val maven = dir("backend").also { File(it, "pom.xml").writeText("<project/>") }
        val plain = dir("frontend")
        val cache = File(PathManager.getSystemPath(), "ide-workspaces")
        val beforeCache = cache.listFiles()?.toSet().orEmpty()
        mockkObject(ProjectUtils)
        try {
            every { ProjectUtils.findOpenProjectByPath(any()) } returns null
            every { ProjectUtils.findOpenProjectContainingRoots(any()) } returns null
            every { ProjectUtils.openTask() } throws IllegalStateException("open-path-reached")
            val result = runBlocking { OpenWorkspaceTool().execute(myProject, buildJsonObject {
                put("modules", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(maven.path)); add(kotlinx.serialization.json.JsonPrimitive(plain.path)) })
            }) }
            assertTrue(result.text, result.text.contains("open-path-reached"))
            assertTrue(result.isFailure)
        } finally {
            unmockkObject(ProjectUtils)
            cache.listFiles()?.filter { it !in beforeCache }?.forEach { it.deleteRecursively() }
        }
    }

    fun testFailedMavenImportDoesNotAddAnyPlainModules() {
        val maven = dir("bad-maven").also { File(it, "pom.xml").writeText("<project/>") }
        val plain = dir("frontend")
        val before = ModuleManager.getInstance(myProject).modules.toSet()
        mockkObject(ProjectUtils)
        try {
            every { ProjectUtils.importMavenModule(myProject, any()) } returns MavenImportResult.Failed("controlled import failure")
            val notes = mutableListOf<String>()
            val state = runBlocking { OpenWorkspaceTool().configureWorkspace(myProject, listOf(maven), listOf(plain),
                File(myProject.basePath!!), setOf(maven.path, plain.path), notes) }
            assertEquals(OpenWorkspaceTool.OpenOutcome.IMPORT_INCOMPLETE, state)
            assertEquals(before, ModuleManager.getInstance(myProject).modules.toSet())
            assertTrue(notes.isEmpty())
            assertFalse(modulesDir().exists())
        } finally { unmockkObject(ProjectUtils) }
    }

    fun testAlreadyOpenProjectIsRepairedByTheActualToolWithoutAnotherWindow() = suppressWorkspaceModelErrors {
        val path = myProject.basePath!!
        mockkObject(ProjectUtils)
        try {
            every { ProjectUtils.findOpenProjectByPath(path) } returns myProject
            val tool = OpenProjectTool()
            val result = runBlocking { tool.execute(myProject, buildJsonObject { put("path", path) }) }
            assertFalse(result.text, result.isFailure)
            assertTrue(result.text, result.text.contains("already open and ready") && result.text.contains("Plain module"))
            assertTrue(roots().contains(path))
            val count = ModuleManager.getInstance(myProject).modules.size
            val again = runBlocking { tool.execute(myProject, buildJsonObject { put("path", path) }) }
            assertFalse(again.text, again.isFailure)
            assertEquals(count, ModuleManager.getInstance(myProject).modules.size)
        } finally { unmockkObject(ProjectUtils) }
    }

    fun testMixedWorkspaceReusesMavenWindowAddsPlainRootAndListsBothOnRetry() = suppressWorkspaceModelErrors {
        val maven = dir("backend").also { File(it, "pom.xml").writeText("<project/>") }
        val plain = dir("frontend")
        write {
            val manager = ModuleManager.getInstance(myProject)
            val module = manager.modules.firstOrNull()
                ?: manager.newModule(File(myProject.basePath!!, "fixture.iml").path,
                    com.intellij.openapi.module.ModuleTypeManager.getInstance().defaultModuleType.id)
            val model = ModuleRootManager.getInstance(module).modifiableModel
            model.addContentEntry(requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByPath(maven.path)))
            model.commit()
        }
        mockkObject(ProjectUtils)
        try {
            every { ProjectUtils.findOpenProjectByPath(any()) } returns null
            every { ProjectUtils.findOpenProjectContainingRoots(setOf(ProjectUtils.canonicalNormalizedPath(maven.path))) } returns myProject
            val args = buildJsonObject { put("modules", buildJsonArray {
                add(kotlinx.serialization.json.JsonPrimitive(maven.path)); add(kotlinx.serialization.json.JsonPrimitive(plain.path))
            }) }
            val tool = OpenWorkspaceTool()
            val first = runBlocking { tool.execute(myProject, args) }
            assertFalse(first.text, first.isFailure)
            assertTrue(first.text, first.text.contains("Reused the open project"))
            assertTrue(first.text, first.text.contains(maven.path) && first.text.contains(plain.path))
            assertTrue(first.text, first.text.contains("[Maven]") && first.text.contains("[plain]"))
            assertTrue(roots().contains(plain.invariantSeparatorsPath))
            val count = ModuleManager.getInstance(myProject).modules.size
            val repeated = runBlocking { tool.execute(myProject, args) }
            assertFalse(repeated.text, repeated.isFailure)
            assertEquals(count, ModuleManager.getInstance(myProject).modules.size)
        } finally { unmockkObject(ProjectUtils) }
    }

    private class FakeBuild : ExternalSystemUnlinkedProjectAware {
        override val systemId = ProjectSystemId("CUSTOM_BUILD")
        override fun isBuildFile(project: Project, file: VirtualFile): Boolean = file.name == "custom.build"
        override fun isLinkedProject(project: Project, externalProjectPath: String): Boolean = false
        override suspend fun linkAndLoadProjectAsync(project: Project, externalProjectPath: String) {}
        override suspend fun unlinkProject(project: Project, externalProjectPath: String) {}
        override fun subscribe(project: Project, listener: ExternalSystemProjectLinkListener, parentDisposable: Disposable) {}
    }
}
