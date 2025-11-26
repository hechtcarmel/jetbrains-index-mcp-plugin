package com.github.hechtcarmel.jetbrainsindexmcpplugin.resources

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ResourceContent
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ProjectStructureResource : McpResource {

    private val json = Json { prettyPrint = true }

    override val uri = "project://structure"
    override val name = "Project Structure"
    override val description = "Current project module structure with source roots"
    override val mimeType = "application/json"

    override suspend fun read(project: Project): ResourceContent {
        val structure = ReadAction.compute<String, Throwable> {
            val moduleManager = ModuleManager.getInstance(project)

            val structureJson = buildJsonObject {
                put("name", project.name)
                put("basePath", project.basePath)
                put("modules", buildJsonArray {
                    moduleManager.modules.forEach { module ->
                        add(buildJsonObject {
                            put("name", module.name)

                            val rootManager = ModuleRootManager.getInstance(module)

                            put("sourceRoots", buildJsonArray {
                                rootManager.sourceRoots.forEach { root ->
                                    add(JsonPrimitive(root.path.removePrefix(project.basePath ?: "").removePrefix("/")))
                                }
                            })

                            put("contentRoots", buildJsonArray {
                                rootManager.contentRoots.forEach { root ->
                                    add(JsonPrimitive(root.path.removePrefix(project.basePath ?: "").removePrefix("/")))
                                }
                            })
                        })
                    }
                })
            }

            json.encodeToString(structureJson)
        }

        return ResourceContent(
            uri = uri,
            mimeType = mimeType,
            text = structure
        )
    }
}
