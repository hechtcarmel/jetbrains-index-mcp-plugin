package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.BuildMessage

object BuildOutputParser {

    private val msvcPattern = Regex(
        """^(.+?)\((\d+)(?:,(\d+))?\):\s*(fatal error|error|warning)(?:\s+([^:]+):)?\s*(.*)$""",
        RegexOption.IGNORE_CASE
    )
    private val clangPattern = Regex(
        """^(.+?):(\d+)(?::(\d+))?:\s*(fatal error|error|warning):\s*(.+)$""",
        RegexOption.IGNORE_CASE
    )
    private val cmakePattern = Regex(
        """^CMake\s+(Error|Warning)(?:\s+\([^)]*\))?\s+at\s+(.+?):(\d+)(?:\s+\(([^)]*)\))?:\s*(.*)$""",
        RegexOption.IGNORE_CASE
    )

    fun parse(
        output: String,
        relativizePath: (String) -> String? = { it }
    ): List<BuildMessage> {
        val messages = linkedSetOf<BuildMessage>()
        val lines = output.lines()
        var index = 0
        while (index < lines.size) {
            val trimmed = lines[index].trim()
            if (trimmed.isEmpty()) {
                index++
                continue
            }

            val message = parseMsvc(trimmed, relativizePath)
                ?: parseClang(trimmed, relativizePath)
            if (message != null) {
                messages.add(message)
                index++
                continue
            }

            val cmakeMessage = parseCMake(lines, index, relativizePath)
            if (cmakeMessage != null) {
                messages.add(cmakeMessage.message)
                index = cmakeMessage.nextIndex
                continue
            }

            index++
        }
        return messages.toList()
    }

    private fun parseMsvc(
        line: String,
        relativizePath: (String) -> String?
    ): BuildMessage? {
        val match = msvcPattern.matchEntire(line) ?: return null
        val category = category(match.groupValues[4])
        val code = match.groupValues[5].trim()
        val message = match.groupValues[6].trim()
        val fullMessage = when {
            code.isNotBlank() && message.isNotBlank() -> "$code: $message"
            message.isNotBlank() -> message
            code.isNotBlank() -> code
            else -> return null
        }
        return BuildMessage(
            category = category,
            message = fullMessage,
            file = relativize(match.groupValues[1], relativizePath),
            line = match.groupValues[2].toIntOrNull(),
            column = match.groupValues[3].toIntOrNull()
        )
    }

    private fun parseClang(
        line: String,
        relativizePath: (String) -> String?
    ): BuildMessage? {
        val match = clangPattern.matchEntire(line) ?: return null
        val message = match.groupValues[5].trim().takeIf { it.isNotBlank() } ?: return null
        return BuildMessage(
            category = category(match.groupValues[4]),
            message = message,
            file = relativize(match.groupValues[1], relativizePath),
            line = match.groupValues[2].toIntOrNull(),
            column = match.groupValues[3].toIntOrNull()
        )
    }

    private fun parseCMake(
        lines: List<String>,
        index: Int,
        relativizePath: (String) -> String?
    ): ParsedMessage? {
        val line = lines[index].trim()
        val match = cmakePattern.matchEntire(line) ?: return null
        val command = match.groupValues[4].trim()
        val details = match.groupValues[5].trim()
        val continuation = collectCMakeContinuation(lines, index + 1)
        val message = when {
            continuation.lines.isNotEmpty() -> (listOf(details).filter { it.isNotBlank() } + continuation.lines)
                .joinToString("\n")
            details.isNotBlank() -> details
            command.isNotBlank() -> command
            else -> "CMake ${match.groupValues[1]}"
        }
        return ParsedMessage(
            message = BuildMessage(
                category = category(match.groupValues[1]),
                message = message,
                file = relativize(match.groupValues[2], relativizePath),
                line = match.groupValues[3].toIntOrNull(),
                column = null
            ),
            nextIndex = continuation.nextIndex
        )
    }

    private fun collectCMakeContinuation(lines: List<String>, startIndex: Int): CMakeContinuation {
        val continuationLines = mutableListOf<String>()
        var index = startIndex
        while (index < lines.size) {
            val line = lines[index]
            if (line.isBlank() || line.firstOrNull()?.isWhitespace() != true) break

            val trimmed = line.trim()
            if (isDiagnosticLine(trimmed)) break

            continuationLines.add(trimmed)
            index++
        }
        return CMakeContinuation(continuationLines, index)
    }

    private fun isDiagnosticLine(line: String): Boolean =
        msvcPattern.matchEntire(line) != null ||
            clangPattern.matchEntire(line) != null ||
            cmakePattern.matchEntire(line) != null

    private fun category(value: String): String =
        if (value.contains("warning", ignoreCase = true)) "WARNING" else "ERROR"

    private fun relativize(path: String, relativizePath: (String) -> String?): String =
        path.trim().replace('\\', '/').let { normalizedPath ->
            relativizePath(normalizedPath) ?: normalizedPath
        }

    private data class ParsedMessage(
        val message: BuildMessage,
        val nextIndex: Int
    )

    private data class CMakeContinuation(
        val lines: List<String>,
        val nextIndex: Int
    )
}
