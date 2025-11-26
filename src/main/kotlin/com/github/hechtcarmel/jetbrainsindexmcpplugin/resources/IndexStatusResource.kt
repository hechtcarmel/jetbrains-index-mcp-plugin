package com.github.hechtcarmel.jetbrainsindexmcpplugin.resources

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ResourceContent
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class IndexStatusResource : McpResource {

    private val json = Json { prettyPrint = true }

    override val uri = "index://status"
    override val name = "Index Status"
    override val description = "IDE indexing status (dumb/smart mode)"
    override val mimeType = "application/json"

    override suspend fun read(project: Project): ResourceContent {
        val dumbService = DumbService.getInstance(project)
        val isDumb = dumbService.isDumb

        val status = buildJsonObject {
            put("isDumbMode", isDumb)
            put("isIndexing", isDumb)
            put("isSmartMode", !isDumb)
            put("projectName", project.name)
        }

        return ResourceContent(
            uri = uri,
            mimeType = mimeType,
            text = json.encodeToString(status)
        )
    }
}
