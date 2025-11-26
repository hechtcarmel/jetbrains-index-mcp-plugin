package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.JsonObject

interface McpTool {
    val name: String
    val description: String
    val inputSchema: JsonObject

    suspend fun execute(project: Project, arguments: JsonObject): ToolCallResult
}
