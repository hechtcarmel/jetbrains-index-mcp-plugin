package com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonObject

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ToolDefinition(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val outputSchema: JsonObject? = null
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ToolCallResult(
    val content: List<ContentBlock>,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val structuredContent: JsonObject? = null,
    val isError: Boolean = false
)

@Serializable
@OptIn(ExperimentalSerializationApi::class)
@JsonClassDiscriminator("type")
sealed class ContentBlock {
    @Serializable
    @SerialName("text")
    data class Text(
        val text: String
    ) : ContentBlock()

    @Serializable
    @SerialName("image")
    data class Image(
        val data: String,
        val mimeType: String
    ) : ContentBlock()
}

@Serializable
data class ServerInfo(
    val name: String,
    val version: String,
    val description: String? = null
)

@Serializable
data class ServerCapabilities(
    val tools: ToolCapability? = ToolCapability()
)

@Serializable
data class ToolCapability(
    val listChanged: Boolean = false
)

@Serializable
data class InitializeResult(
    val protocolVersion: String = "2025-03-26",
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
