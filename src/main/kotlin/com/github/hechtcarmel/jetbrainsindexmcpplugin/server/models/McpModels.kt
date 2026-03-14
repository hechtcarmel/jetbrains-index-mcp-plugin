package com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
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

object JsonRpcErrorCodes {
    const val PARSE_ERROR = -32700
    const val INVALID_REQUEST = -32600
    const val METHOD_NOT_FOUND = -32601
    const val INVALID_PARAMS = -32602
    const val INTERNAL_ERROR = -32603

    const val INDEX_NOT_READY = -32001
    const val FILE_NOT_FOUND = -32002
    const val SYMBOL_NOT_FOUND = -32003
    const val REFACTORING_CONFLICT = -32004
}
