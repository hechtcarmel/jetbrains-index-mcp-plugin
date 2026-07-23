package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ProjectUtils
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class ReplaceTextInFileTool : AbstractMcpTool() {

    override val name = ToolNames.REPLACE_TEXT_IN_FILE

    override val description = """
        Find and replace text in a file using IntelliJ's Document API.

        Performs plain text or regex replacement through IntelliJ's document model,
        so changes are immediately visible to the index, PSI, and all other IDE tools
        without needing ide_sync_files.

        Use this for mechanical text substitutions across a file — e.g., replacing a
        method call wrapper, updating import paths, or renaming a local pattern. For
        structural refactoring (renaming symbols across the project), use
        ide_refactor_rename instead.

        Returns the number of replacements made and affected line numbers.

        Examples:
        - {"file": "src/Service.java", "searchText": "OldHelper.wrap(", "replaceText": "("}
        - {"file": "src/config.ts", "searchText": "localhost:3000", "replaceText": "localhost:8080"}
        - {"file": "src/Utils.java", "searchText": "LOG\\.debug\\((.*)\\)", "replaceText": "LOG.trace($1)", "regex": true}
    """.trimIndent()

    override val inputSchema: JsonObject = SchemaBuilder.tool()
        .projectPath()
        .file()
        .stringProperty("searchText", "Text to find. Treated as literal unless regex is true.", required = true)
        .stringProperty("replaceText", "Replacement text. Supports regex group references ($1, $2) when regex is true.", required = true)
        .booleanProperty(ParamNames.REGEX, "Treat searchText as a regular expression. Default: false.")
        .booleanProperty(ParamNames.CASE_SENSITIVE, "Case-sensitive matching. Default: true.")
        .build()

    @Serializable
    data class ReplaceTextResult(
        val success: Boolean,
        val file: String,
        val replacements: Int,
        val message: String
    )

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val filePath = arguments[ParamNames.FILE]?.jsonPrimitive?.content
            ?: return createErrorResult("Missing required parameter: file")
        val searchText = arguments["searchText"]?.jsonPrimitive?.content
            ?: return createErrorResult("Missing required parameter: searchText")
        val replaceText = arguments["replaceText"]?.jsonPrimitive?.content
            ?: return createErrorResult("Missing required parameter: replaceText")
        val isRegex = arguments[ParamNames.REGEX]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
        val caseSensitive = arguments[ParamNames.CASE_SENSITIVE]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true

        if (searchText.isEmpty()) {
            return createErrorResult("searchText must not be empty.")
        }
        if (searchText == replaceText) {
            return createErrorResult("searchText and replaceText are identical — nothing to replace.")
        }

        val effectiveReplaceText = unescapeText(replaceText)

        val virtualFile = resolveFile(project, filePath)
            ?: return createErrorResult("File not found: $filePath")

        ensureWritable(virtualFile, filePath)?.let { return it }

        val regex = if (isRegex) {
            try {
                val flags = if (caseSensitive) setOf<RegexOption>() else setOf(RegexOption.IGNORE_CASE)
                Regex(searchText, flags)
            } catch (e: Exception) {
                return createErrorResult("Invalid regex: ${e.message}")
            }
        } else null

        var replacements = 0
        var relativePath = filePath

        suspendingWriteAction(project, "Replace text in $filePath") {
            val document = FileDocumentManager.getInstance().getDocument(virtualFile)
                ?: throw Exception("Cannot get document for $filePath")

            val text = document.text

            val newText = if (regex != null) {
                regex.replace(text) { matchResult ->
                    replacements++
                    effectiveReplaceText.replace(Regex("\\$(\\d+)")) { groupRef ->
                        val groupIndex = groupRef.groupValues[1].toIntOrNull() ?: 0
                        matchResult.groupValues.getOrElse(groupIndex) { groupRef.value }
                    }
                }
            } else {
                val sb = StringBuilder()
                var pos = 0
                val searchLen = searchText.length
                while (pos < text.length) {
                    val idx = text.indexOf(searchText, pos, ignoreCase = !caseSensitive)
                    if (idx == -1) break
                    sb.append(text, pos, idx)
                    sb.append(effectiveReplaceText)
                    replacements++
                    pos = idx + searchLen
                }
                sb.append(text, pos, text.length)
                sb.toString()
            }

            if (replacements > 0) {
                document.setText(newText)
                PsiDocumentManager.getInstance(project).commitDocument(document)
                FileDocumentManager.getInstance().saveDocument(document)
            }

            relativePath = ProjectUtils.getToolFilePath(project, virtualFile)
        }

        if (replacements == 0) {
            return createJsonResult(ReplaceTextResult(
                success = true,
                file = relativePath,
                replacements = 0,
                message = "No matches found for '${searchText.take(80)}' in $relativePath"
            ))
        }

        return createJsonResult(ReplaceTextResult(
            success = true,
            file = relativePath,
            replacements = replacements,
            message = "Replaced $replacements occurrence(s) in $relativePath"
        ))
    }

    private fun unescapeText(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            if (text[i] == '\\' && i + 1 < text.length) {
                when (text[i + 1]) {
                    'n' -> { sb.append('\n'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    '\\' -> { sb.append('\\'); i += 2 }
                    else -> { sb.append(text[i]); i++ }
                }
            } else {
                sb.append(text[i])
                i++
            }
        }
        return sb.toString()
    }
}
