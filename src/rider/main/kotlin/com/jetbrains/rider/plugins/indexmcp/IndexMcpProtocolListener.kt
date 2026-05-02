package com.jetbrains.rider.plugins.indexmcp

import com.intellij.openapi.client.ClientProjectSession
import com.intellij.openapi.diagnostic.Logger
import com.jetbrains.rd.protocol.SolutionExtListener
import com.jetbrains.rd.util.lifetime.Lifetime
import com.jetbrains.rider.plugins.indexmcp.model.IndexMcpModel

class IndexMcpProtocolListener : SolutionExtListener<IndexMcpModel> {
    override fun extensionCreated(lifetime: Lifetime, session: ClientProjectSession, model: IndexMcpModel) {
        LOG.info("Index MCP Rider protocol extension created for project: ${session.project.name}")
    }

    private companion object {
        val LOG: Logger = Logger.getInstance(IndexMcpProtocolListener::class.java)
    }
}
