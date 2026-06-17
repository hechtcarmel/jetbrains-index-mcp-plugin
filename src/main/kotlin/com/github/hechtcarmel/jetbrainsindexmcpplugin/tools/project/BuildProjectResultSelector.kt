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
        if (!buildFailed || currentMessages.isNotEmpty()) {
            return currentMessages
        }

        if (failureMessages.isNotEmpty()) {
            return failureMessages
        }

        if (rawOutput.isBlank()) {
            return emptyList()
        }

        return BuildOutputParser.parse(rawOutput, relativizePath)
    }
}
