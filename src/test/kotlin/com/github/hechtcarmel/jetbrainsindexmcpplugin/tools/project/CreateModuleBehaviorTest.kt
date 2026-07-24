package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project

import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.isFailure
import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.text
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PluginDetector
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PluginDetectors
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.LoggedErrorProcessor
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * Behavior coverage for `ide_create_module`.
 *
 * Extends [HeavyPlatformTestCase] instead of `McpPlatformTestCase` because module creation is
 * explicitly blocked by the light test infrastructure (`LightPlatformTestCase` fails with
 * "Adding modules is not permitted in light tests").
 */
class CreateModuleBehaviorTest : HeavyPlatformTestCase() {

    private val tool = CreateModuleTool()

    private val createdDirs = mutableListOf<File>()

    override fun tearDown() {
        try {
            createdDirs.asReversed().forEach { it.deleteRecursively() }
            createdDirs.clear()
        } finally {
            super.tearDown()
        }
    }

    private fun createTestDir(name: String): File {
        val basePath = requireNotNull(myProject.basePath)
        val dir = File(basePath, name)
        dir.mkdirs()
        createdDirs.add(dir)
        return dir
    }

    /**
     * Suppresses a `NoSuchMethodError` in `WorkspaceModelImpl.logErrorOnEventHandling` that fires
     * when a module is added in the test environment due to a Kotlin version mismatch between the
     * test classpath and the bundled platform. The error is harmless — the module is still created.
     */
    private fun suppressWorkspaceModelErrors(action: () -> Unit) {
        val token = LoggedErrorProcessor.executeWith(
            object : LoggedErrorProcessor() {
                override fun processError(
                    category: String,
                    message: String,
                    details: Array<out String>,
                    t: Throwable?
                ): MutableSet<Action> {
                    if (t is NoSuchMethodError || message.contains("Workspace Model event handling")) {
                        return Action.NONE
                    }
                    return Action.ALL
                }
            }
        )
        try {
            action()
        } finally {
            token.finish()
        }
    }

    fun testHappyPath_createsModuleWithContentRootAndExcludes() = suppressWorkspaceModelErrors {
        val dir = createTestDir("test-module-happy")
        val result = runBlocking {
            tool.execute(myProject, buildJsonObject {
                put("path", dir.absolutePath)
                put("name", "happy-module")
                put("excludes", buildJsonArray {
                    add(JsonPrimitive("node_modules"))
                    add(JsonPrimitive("dist"))
                })
            })
        }

        assertFalse("Module creation should succeed: ${result.text}", result.isFailure)
        val text = result.text
        assertTrue("Response should include module name: $text", text.contains("happy-module"))
        assertTrue("Response should include content root path: $text", text.contains(dir.absolutePath))
        assertTrue("Response should include module file path: $text", text.contains(".iml"))
        assertTrue("Response should include excluded count: $text", text.contains("Excluded 2"))
        assertTrue("Response should include async note: $text", text.contains("indexing is async"))

        val module = ModuleManager.getInstance(myProject).findModuleByName("happy-module")
        assertNotNull("Module should be registered in ModuleManager", module)
        val roots = ModuleRootManager.getInstance(module!!).contentRoots
        assertTrue(
            "Content root should include the target directory",
            roots.any { it.path == dir.absolutePath }
        )

        val contentEntries = ModuleRootManager.getInstance(module).contentEntries
        val excludeUrls = contentEntries.flatMap { it.excludeFolderUrls }
        assertTrue(
            "node_modules should be excluded: $excludeUrls",
            excludeUrls.any { it.contains("node_modules") }
        )
        assertTrue(
            "dist should be excluded: $excludeUrls",
            excludeUrls.any { it.contains("dist") }
        )
    }

    fun testIdempotent_secondCallOnSamePathReturnsSuccess() = suppressWorkspaceModelErrors {
        val dir = createTestDir("test-module-idempotent")

        val first = runBlocking {
            tool.execute(myProject, buildJsonObject {
                put("path", dir.absolutePath)
                put("name", "idempotent-module")
            })
        }
        assertFalse("First call should succeed: ${first.text}", first.isFailure)

        val second = runBlocking {
            tool.execute(myProject, buildJsonObject {
                put("path", dir.absolutePath)
                put("name", "idempotent-module-2")
            })
        }
        assertFalse("Second call on same path should succeed (idempotent): ${second.text}", second.isFailure)
        assertTrue(
            "Should indicate already registered: ${second.text}",
            second.text.contains("already registered")
        )
    }

    fun testNameCollision_secondModuleWithSameNameReturnsError() = suppressWorkspaceModelErrors {
        val dir1 = createTestDir("test-module-collision-a")
        val dir2 = createTestDir("test-module-collision-b")

        val first = runBlocking {
            tool.execute(myProject, buildJsonObject {
                put("path", dir1.absolutePath)
                put("name", "collision-module")
            })
        }
        assertFalse("First module creation should succeed: ${first.text}", first.isFailure)

        val second = runBlocking {
            tool.execute(myProject, buildJsonObject {
                put("path", dir2.absolutePath)
                put("name", "collision-module")
            })
        }
        assertTrue("Second module with same name should fail: ${second.text}", second.isFailure)
        assertTrue(
            "Error should mention existing module name: ${second.text}",
            second.text.contains("already exists")
        )
    }

    fun testInvalidName_dotDotInNameReturnsError() = runBlocking {
        val dir = createTestDir("test-module-invalid-name")

        val result = tool.execute(myProject, buildJsonObject {
            put("path", dir.absolutePath)
            put("name", "bad..name")
        })

        assertTrue("Name with '..' should be rejected: ${result.text}", result.isFailure)
        assertTrue(
            "Error should mention invalid name: ${result.text}",
            result.text.contains("Invalid module name")
        )
    }

    fun testInvalidExclude_dotDotInExcludeReturnsError() = runBlocking {
        val dir = createTestDir("test-module-invalid-exclude")

        val result = tool.execute(myProject, buildJsonObject {
            put("path", dir.absolutePath)
            put("name", "valid-module")
            put("excludes", buildJsonArray {
                add(JsonPrimitive("../escape"))
            })
        })

        assertTrue("Exclude with '..' should be rejected: ${result.text}", result.isFailure)
        assertTrue(
            "Error should mention '..': ${result.text}",
            result.text.contains("..")
        )
    }

    fun testInvalidExclude_blankExcludeReturnsError() = runBlocking {
        val dir = createTestDir("test-module-blank-exclude")

        val result = tool.execute(myProject, buildJsonObject {
            put("path", dir.absolutePath)
            put("name", "valid-module-2")
            put("excludes", buildJsonArray {
                add(JsonPrimitive("  "))
            })
        })

        assertTrue("Blank exclude should be rejected: ${result.text}", result.isFailure)
        assertTrue(
            "Error should mention blank: ${result.text}",
            result.text.contains("blank")
        )
    }

    fun testPomXmlGuard_directoryWithPomAndMavenAvailableReturnsError() = runBlocking {
        val dir = createTestDir("test-module-pom")
        File(dir, "pom.xml").writeText("<project/>")

        val mockMaven = mockk<PluginDetector>()
        every { mockMaven.isAvailable } returns true
        mockkObject(PluginDetectors)
        every { PluginDetectors.maven } returns mockMaven
        try {
            val result = tool.execute(myProject, buildJsonObject {
                put("path", dir.absolutePath)
            })

            assertTrue("pom.xml + Maven available should block creation: ${result.text}", result.isFailure)
            assertTrue(
                "Error should suggest ide_import_modules: ${result.text}",
                result.text.contains("ide_import_modules")
            )
        } finally {
            unmockkObject(PluginDetectors)
        }
    }
}
