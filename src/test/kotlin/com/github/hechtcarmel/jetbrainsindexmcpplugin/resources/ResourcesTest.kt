package com.github.hechtcarmel.jetbrainsindexmcpplugin.resources

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * Platform-dependent tests that require IntelliJ Platform indexing.
 * For registry and metadata tests that don't need the platform, see ResourcesUnitTest.
 */
class ResourcesTest : BasePlatformTestCase() {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun testIndexStatusResourceRead() = runBlocking {
        val resource = IndexStatusResource()

        val content = resource.read(project)

        assertEquals("index://status", content.uri)
        assertNotNull(content.text)

        val resultJson = json.parseToJsonElement(content.text!!).jsonObject
        assertNotNull("Result should have isDumbMode", resultJson["isDumbMode"])
        assertNotNull("Result should have isSmartMode", resultJson["isSmartMode"])
    }

    fun testProjectStructureResourceRead() = runBlocking {
        val resource = ProjectStructureResource()

        val content = resource.read(project)

        assertEquals("project://structure", content.uri)
        assertNotNull(content.text)

        val resultJson = json.parseToJsonElement(content.text!!).jsonObject
        assertNotNull("Result should have projectName", resultJson["projectName"])
        assertNotNull("Result should have basePath", resultJson["basePath"])
        assertNotNull("Result should have modules", resultJson["modules"])
    }
}
