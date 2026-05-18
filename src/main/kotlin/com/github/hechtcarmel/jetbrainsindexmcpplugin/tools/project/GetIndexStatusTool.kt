package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.IndexStatusResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.JsonObject

class GetIndexStatusTool : AbstractMcpTool() {

    // This tool only checks status, no PSI operations needed
    override val requiresPsiSync: Boolean = false

    override val name = "ide_index_status"

    override val description = """
        Check if the IDE is ready for code intelligence operations. Use when other tools fail with indexing errors, or to verify IDE readiness before batch operations.

        Returns: isDumbMode (true = indexing in progress, limited functionality), isIndexing flag. When isDumbMode is true, wait and retry—most tools require indexing to complete.

        Parameters: project_path (optional, only needed with multiple projects open).

        Example: {}
    """.trimIndent()

    override val inputSchema: JsonObject = SchemaBuilder.tool()
        .projectPath()
        .build()

    override val outputSchema: JsonObject = SchemaBuilder.tool()
        .booleanProperty("isDumbMode", "Whether the IDE is currently in dumb mode.", required = true)
        .booleanProperty("isIndexing", "Whether indexing is currently active.", required = true)
        .numberProperty(
            "indexingProgress",
            "Indexing progress from 0.0 to 1.0, when available.",
            required = true,
            nullable = true
        )
        .build()

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val dumbService = DumbService.getInstance(project)
        val isDumb = dumbService.isDumb

        return createJsonResult(IndexStatusResult(
            isDumbMode = isDumb,
            isIndexing = isDumb,
            indexingProgress = null
        ))
    }
}
