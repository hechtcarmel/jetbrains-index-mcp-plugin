package com.github.hechtcarmel.jetbrainsindexmcpplugin.resources

import junit.framework.TestCase

class ResourcesUnitTest : TestCase() {

    fun testResourceRegistryRegistersBuiltInResources() {
        val registry = ResourceRegistry()
        registry.registerBuiltInResources()

        val resources = registry.getAllResources()
        assertTrue("Should have at least one resource", resources.isNotEmpty())

        val indexStatusResource = registry.getResource("index://status")
        assertNotNull("index://status resource should exist", indexStatusResource)

        val projectStructureResource = registry.getResource("project://structure")
        assertNotNull("project://structure resource should exist", projectStructureResource)
    }

    fun testResourceDefinitionsHaveRequiredFields() {
        val registry = ResourceRegistry()
        registry.registerBuiltInResources()

        val definitions = registry.getResourceDefinitions()

        for (definition in definitions) {
            assertNotNull("Definition should have uri", definition.uri)
            assertTrue("URI should not be empty", definition.uri.isNotEmpty())

            assertNotNull("Definition should have name", definition.name)
            assertTrue("Name should not be empty", definition.name.isNotEmpty())
        }
    }

    fun testIndexStatusResourceMetadata() {
        val resource = IndexStatusResource()

        assertEquals("index://status", resource.uri)
        assertNotNull(resource.name)
        assertNotNull(resource.description)
        assertEquals("application/json", resource.mimeType)
    }

    fun testProjectStructureResourceMetadata() {
        val resource = ProjectStructureResource()

        assertEquals("project://structure", resource.uri)
        assertNotNull(resource.name)
        assertNotNull(resource.description)
        assertEquals("application/json", resource.mimeType)
    }

    fun testResourceRegistryGetNonexistentResource() {
        val registry = ResourceRegistry()
        registry.registerBuiltInResources()

        val resource = registry.getResource("nonexistent://resource")
        assertNull("Should return null for nonexistent resource", resource)
    }

    fun testResourceRegistryRegisterCustomResource() {
        val registry = ResourceRegistry()

        val customResource = object : McpResource {
            override val uri = "custom://test"
            override val name = "Custom Test"
            override val description = "A custom test resource"
            override val mimeType = "text/plain"

            override suspend fun read(project: com.intellij.openapi.project.Project): com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ResourceContent {
                return com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ResourceContent(
                    uri = uri,
                    mimeType = mimeType,
                    text = "custom content"
                )
            }
        }

        registry.register(customResource)

        val retrieved = registry.getResource("custom://test")
        assertNotNull("Custom resource should be retrievable", retrieved)
        assertEquals("custom://test", retrieved?.uri)
    }
}
