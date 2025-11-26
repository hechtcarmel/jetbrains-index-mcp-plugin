package com.github.hechtcarmel.jetbrainsindexmcpplugin.resources

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ResourceDefinition
import com.intellij.openapi.diagnostic.logger
import java.util.concurrent.ConcurrentHashMap

class ResourceRegistry {

    companion object {
        private val LOG = logger<ResourceRegistry>()
    }

    private val resources = ConcurrentHashMap<String, McpResource>()

    fun register(resource: McpResource) {
        resources[resource.uri] = resource
        LOG.info("Registered MCP resource: ${resource.uri}")
    }

    fun unregister(uri: String) {
        resources.remove(uri)
        LOG.info("Unregistered MCP resource: $uri")
    }

    fun getResource(uri: String): McpResource? {
        // First try exact match
        resources[uri]?.let { return it }

        // Then try pattern matching for parameterized URIs
        return resources.values.find { resource ->
            matchesPattern(resource.uri, uri)
        }
    }

    fun getAllResources(): List<McpResource> {
        return resources.values.toList()
    }

    fun getResourceDefinitions(): List<ResourceDefinition> {
        return resources.values.map { resource ->
            ResourceDefinition(
                uri = resource.uri,
                name = resource.name,
                description = resource.description,
                mimeType = resource.mimeType
            )
        }
    }

    fun registerBuiltInResources() {
        register(IndexStatusResource())
        register(ProjectStructureResource())
        LOG.info("Registered ${resources.size} built-in MCP resources")
    }

    private fun matchesPattern(pattern: String, uri: String): Boolean {
        // Simple pattern matching for URIs like "file://content/{path}"
        if (!pattern.contains("{")) {
            return pattern == uri
        }

        val patternParts = pattern.split("/")
        val uriParts = uri.split("/")

        if (patternParts.size > uriParts.size) return false

        for (i in patternParts.indices) {
            val patternPart = patternParts[i]
            val uriPart = if (i < uriParts.size) uriParts[i] else return false

            if (patternPart.startsWith("{") && patternPart.endsWith("}")) {
                // This is a parameter placeholder, any value matches
                continue
            }

            if (patternPart != uriPart) {
                return false
            }
        }

        return true
    }
}
