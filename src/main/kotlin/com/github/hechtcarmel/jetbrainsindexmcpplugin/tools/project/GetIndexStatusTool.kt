package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.IndexStatusResult
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class GetIndexStatusTool : AbstractMcpTool() {

    override val name = "get_index_status"

    override val description = """
        Get the IDE indexing status. Returns whether the IDE is in dumb mode (indexing)
        or smart mode (ready for full functionality).
    """.trimIndent()

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject { })
        put("required", buildJsonArray { })
    }

    override suspend fun execute(project: Project, arguments: JsonObject): ToolCallResult {
        val dumbService = DumbService.getInstance(project)
        val isDumb = dumbService.isDumb

        return createJsonResult(IndexStatusResult(
            isDumbMode = isDumb,
            isIndexing = isDumb,
            indexingProgress = null
        ))
    }
}
