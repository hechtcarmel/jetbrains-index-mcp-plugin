package com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class ToolDefinition(
    val name: String,
    val description: String,
    val inputSchema: JsonObject
)

@Serializable
data class ToolCallResult(
    val content: List<ContentBlock>,
    val isError: Boolean = false
)

@Serializable
sealed class ContentBlock {
    @Serializable
    @SerialName("text")
    data class Text(
        val type: String = "text",
        val text: String
    ) : ContentBlock()

    @Serializable
    @SerialName("image")
    data class Image(
        val type: String = "image",
        val data: String,
        val mimeType: String
    ) : ContentBlock()
}

@Serializable
data class ResourceDefinition(
    val uri: String,
    val name: String,
    val description: String,
    val mimeType: String
)

@Serializable
data class ResourceContent(
    val uri: String,
    val mimeType: String,
    val text: String? = null,
    val blob: String? = null
)

@Serializable
data class ServerInfo(
    val name: String,
    val version: String,
    val protocolVersion: String = "2024-11-05"
)

@Serializable
data class ServerCapabilities(
    val tools: ToolCapability? = ToolCapability(),
    val resources: ResourceCapability? = ResourceCapability()
)

@Serializable
data class ToolCapability(
    val listChanged: Boolean = false
)

@Serializable
data class ResourceCapability(
    val subscribe: Boolean = false,
    val listChanged: Boolean = false
)

@Serializable
data class InitializeResult(
    val protocolVersion: String = "2024-11-05",
    val capabilities: ServerCapabilities = ServerCapabilities(),
    val serverInfo: ServerInfo
)

@Serializable
data class ToolsListResult(
    val tools: List<ToolDefinition>
)

@Serializable
data class ToolCallParams(
    val name: String,
    val arguments: JsonObject? = null
)

@Serializable
data class ResourcesListResult(
    val resources: List<ResourceDefinition>
)

@Serializable
data class ResourceReadParams(
    val uri: String
)

@Serializable
data class ResourceReadResult(
    val contents: List<ResourceContent>
)
