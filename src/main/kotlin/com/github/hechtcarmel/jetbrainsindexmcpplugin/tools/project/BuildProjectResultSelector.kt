package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project

import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.BuildMessage
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.BuildOutputParser

internal object BuildProjectResultSelector {

    fun selectMessages(
        buildFailed: Boolean,
        currentMessages: List<BuildMessage>,
        failureMessages: List<BuildMessage>,
        rawOutput: String,
        relativizePath: (String) -> String?
    ): List<BuildMessage> {
        if (!buildFailed || currentMessages.hasErrors()) {
            return currentMessages
        }

        val fallbackMessages = fallbackMessages(failureMessages, rawOutput, relativizePath)
        if (fallbackMessages.isEmpty()) return currentMessages

        if (currentMessages.isNotEmpty() && !fallbackMessages.hasErrors()) {
            return currentMessages
        }
        return (currentMessages + fallbackMessages).distinct()
    }

    private fun fallbackMessages(
        failureMessages: List<BuildMessage>,
        rawOutput: String,
        relativizePath: (String) -> String?
    ): List<BuildMessage> {
        val parsedMessages = if (rawOutput.isBlank()) {
            emptyList()
        } else {
            BuildOutputParser.parse(rawOutput, relativizePath)
        }
        return (parsedMessages + failureMessages).distinct()
    }

    private fun List<BuildMessage>.hasErrors(): Boolean =
        any { it.category == "ERROR" }
}
