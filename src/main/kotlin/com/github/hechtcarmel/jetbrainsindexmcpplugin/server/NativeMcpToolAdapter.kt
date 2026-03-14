package com.github.hechtcarmel.jetbrainsindexmcpplugin.server

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.history.CommandEntry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.history.CommandHistoryService
import com.github.hechtcarmel.jetbrainsindexmcpplugin.history.CommandStatus
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ContentBlock
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.McpTool as PluginMcpTool
import com.intellij.mcpserver.McpTool
import com.intellij.mcpserver.McpToolCallResult
import com.intellij.mcpserver.McpToolCallResultContent
import com.intellij.mcpserver.McpToolDescriptor
import com.intellij.mcpserver.McpToolSchema
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Adapts the plugin's existing tool contract to JetBrains' native MCP tool API.
 */
internal class NativeMcpToolAdapter(
    private val delegate: PluginMcpTool,
    private val historyRecorderFactory: (Project) -> HistoryRecorder? = { project ->
        runCatching { CommandHistoryService.getInstance(project) }
            .getOrNull()
            ?.let(::CommandHistoryRecorder)
    }
) : McpTool {

    override val descriptor = McpToolDescriptor(
        delegate.name,
        delegate.description,
        delegate.inputSchema.toNativeSchema(),
        null
    )

    companion object {
        private val LOG = logger<NativeMcpToolAdapter>()
        private val EMPTY_STRUCTURED_CONTENT = buildJsonObject {}
    }

    override suspend fun call(arguments: JsonObject): McpToolCallResult {
        val fallbackProject = currentProjectFromNativeContext()
        return call(arguments, fallbackProject)
    }

    internal suspend fun call(arguments: JsonObject, fallbackProject: Project?): McpToolCallResult {
        val projectResolution = resolveProject(arguments, fallbackProject)
        if (projectResolution.isError) {
            return projectResolution.errorResult!!.toNativeResult()
        }

        val project = projectResolution.project!!
        val historyRecorder = historyRecorderFactory(project)
        val commandEntry = CommandEntry(toolName = delegate.name, parameters = arguments)
        historyRecorder?.recordCommand(commandEntry)

        val startTime = System.currentTimeMillis()
        return try {
            val result = delegate.execute(project, arguments)
            historyRecorder?.updateCommandStatus(
                commandEntry.id,
                if (result.isError) CommandStatus.ERROR else CommandStatus.SUCCESS,
                result.content.firstOrNull().toHistoryText(),
                System.currentTimeMillis() - startTime
            )
            result.toNativeResult()
        } catch (t: Throwable) {
            LOG.warn("Native MCP tool execution failed for ${delegate.name}", t)
            historyRecorder?.updateCommandStatus(
                commandEntry.id,
                CommandStatus.ERROR,
                t.message ?: "Unknown error",
                System.currentTimeMillis() - startTime
            )
            McpToolCallResult.Companion.error(t.message ?: "Unknown error", EMPTY_STRUCTURED_CONTENT)
        }
    }

    private fun resolveProject(arguments: JsonObject, fallbackProject: Project?): ProjectResolver.Result {
        val explicitProjectPath = arguments[ParamNames.PROJECT_PATH]?.jsonPrimitive?.contentOrNull
        if (!explicitProjectPath.isNullOrBlank()) {
            return ProjectResolver.resolve(explicitProjectPath)
        }

        if (fallbackProject != null && !fallbackProject.isDisposed) {
            return ProjectResolver.Result(project = fallbackProject)
        }

        return ProjectResolver.resolve(null)
    }

    private fun ToolCallResult.toNativeResult(): McpToolCallResult {
        val nativeContent: Array<McpToolCallResultContent> = content.map { block ->
            when (block) {
                is ContentBlock.Text -> McpToolCallResultContent.Text(block.text)
                is ContentBlock.Image -> McpToolCallResultContent.Text("[Image: ${block.mimeType}]")
            }
        }.toTypedArray()

        return McpToolCallResult(nativeContent, EMPTY_STRUCTURED_CONTENT, isError)
    }

    private fun ContentBlock?.toHistoryText(): String? {
        return when (this) {
            null -> null
            is ContentBlock.Text -> text
            is ContentBlock.Image -> "[Image: $mimeType]"
        }
    }

    private suspend fun currentProjectFromNativeContext(): Project? {
        val coroutineContext = currentCoroutineContext()
        return runCatching {
            val bridgeClass = Class.forName("com.intellij.mcpserver.McpCallInfoKt")
            val method = bridgeClass.getMethod("getProjectOrNull", kotlin.coroutines.CoroutineContext::class.java)
            method.invoke(null, coroutineContext) as? Project
        }.getOrNull()
    }
}

private fun JsonObject.toNativeSchema(): McpToolSchema {
    val properties = this["properties"]?.jsonObject ?: buildJsonObject {}
    val required = this["required"]?.asStringSet().orEmpty()
    val definitionsKey = when {
        containsKey("definitions") -> "definitions"
        containsKey("\$defs") -> "\$defs"
        else -> "definitions"
    }
    val definitions = this[definitionsKey]?.jsonObject?.toMap().orEmpty()
    return McpToolSchema.Companion.ofPropertiesSchema(properties, required, definitions, definitionsKey)
}

private fun JsonElement.asStringSet(): Set<String> {
    val array = this as? JsonArray ?: return emptySet()
    return array.mapNotNull { it.jsonPrimitive.contentOrNull }.toSet()
}

internal interface HistoryRecorder {
    fun recordCommand(entry: CommandEntry)
    fun updateCommandStatus(id: String, status: CommandStatus, result: String?, durationMs: Long?)
}

private class CommandHistoryRecorder(
    private val service: CommandHistoryService
) : HistoryRecorder {
    override fun recordCommand(entry: CommandEntry) {
        service.recordCommand(entry)
    }

    override fun updateCommandStatus(id: String, status: CommandStatus, result: String?, durationMs: Long?) {
        service.updateCommandStatus(id, status, result, durationMs)
    }
}
