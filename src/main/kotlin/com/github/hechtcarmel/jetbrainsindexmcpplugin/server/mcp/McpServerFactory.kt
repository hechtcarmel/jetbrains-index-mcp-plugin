package com.github.hechtcarmel.jetbrainsindexmcpplugin.server.mcp

import com.github.hechtcarmel.jetbrainsindexmcpplugin.McpConstants
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.ToolRegistry
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.EmptyJsonObject
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities

/**
 * Builds the MCP [Server] that the transports hand connections to.
 *
 * A fresh [Server] is created per connection (per POST, for the stateless Streamable HTTP
 * endpoint) for two reasons:
 *
 *  - **Settings take effect immediately.** [ToolRegistry.getToolDefinitions] filters by the
 *    enabled/disabled setting, so a tool toggled in Settings appears or disappears from the very
 *    next `tools/list` without restarting the server. A long-lived [Server] would snapshot the
 *    tool list once at startup.
 *  - **No session accumulation.** `Server` keeps a map of live sessions; in stateless mode
 *    nothing ever closes them. Callers must close the server when the call completes — see
 *    `KtorMcpServer`.
 *
 * Construction is cheap: it fills two maps from an already-built tool list.
 *
 * `open` so `StatelessServerLifecycleTest` can observe the servers handed to the transport and
 * prove they are closed again — that leak is otherwise invisible from outside.
 */
open class McpServerFactory(
    private val toolRegistry: ToolRegistry,
    private val dispatcher: McpToolDispatcher
) {

    open fun newServer(): Server {
        val server = Server(
            serverInfo = Implementation(
                name = McpConstants.getServerName(),
                version = McpConstants.getServerVersion(),
                title = McpConstants.PLUGIN_NAME
            ),
            options = ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = false)
                )
            ),
            // `Implementation` has no `description` field — MCP puts this kind of "how to use
            // this server" text in `instructions`, which clients surface to the model.
            instructions = McpConstants.SERVER_DESCRIPTION
        )
        server.addTools(registeredTools())
        return server
    }

    private fun registeredTools(): List<RegisteredTool> =
        toolRegistry.getToolDefinitions().map { tool ->
            RegisteredTool(tool) { request ->
                dispatcher.call(request.params.name, request.params.arguments ?: EmptyJsonObject)
            }
        }
}
