package com.github.hechtcarmel.jetbrainsindexmcpplugin.resources

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ResourceContent
import com.intellij.openapi.project.Project

interface McpResource {
    val uri: String
    val name: String
    val description: String
    val mimeType: String

    suspend fun read(project: Project): ResourceContent
}
