package com.github.hechtcarmel.jetbrainsindexmcpplugin.resources

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ResourceUris
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ResourceContent
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class FileContentResource : McpResource {

    override val uri = ResourceUris.FILE_CONTENT_PATTERN

    override val name = "File Content"

    override val description = "Read the content of a file by its path relative to the project root"

    override val mimeType = "application/json"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    override suspend fun read(project: Project): ResourceContent {
        return ResourceContent(
            uri = uri,
            mimeType = mimeType,
            text = json.encodeToString(FileContentError(
                error = "path_required",
                message = "Please provide a file path. URI format: file://content/{relative-path}"
            ))
        )
    }

    suspend fun readWithPath(project: Project, relativePath: String): ResourceContent {
        return ReadAction.compute<ResourceContent, Throwable> {
            val basePath = project.basePath
                ?: return@compute createErrorResponse("Project base path not available")

            val fullPath = if (relativePath.startsWith("/")) {
                relativePath
            } else {
                "$basePath/$relativePath"
            }

            val virtualFile = LocalFileSystem.getInstance().findFileByPath(fullPath)
                ?: return@compute createErrorResponse("File not found: $relativePath")

            if (virtualFile.isDirectory) {
                return@compute createErrorResponse("Path is a directory, not a file: $relativePath")
            }

            try {
                val content = String(virtualFile.contentsToByteArray(), Charsets.UTF_8)
                val fileInfo = FileContentResult(
                    path = relativePath,
                    content = content,
                    size = virtualFile.length,
                    extension = virtualFile.extension,
                    encoding = "UTF-8"
                )

                ResourceContent(
                    uri = "${ResourceUris.FILE_CONTENT_PREFIX}$relativePath",
                    mimeType = getMimeType(virtualFile.extension),
                    text = json.encodeToString(fileInfo)
                )
            } catch (e: Exception) {
                createErrorResponse("Failed to read file: ${e.message}")
            }
        }
    }

    private fun createErrorResponse(message: String): ResourceContent {
        return ResourceContent(
            uri = uri,
            mimeType = "application/json",
            text = json.encodeToString(FileContentError(
                error = "read_error",
                message = message
            ))
        )
    }

    private fun getMimeType(extension: String?): String {
        return when (extension?.lowercase()) {
            "java" -> "text/x-java-source"
            "kt", "kts" -> "text/x-kotlin"
            "xml" -> "text/xml"
            "json" -> "application/json"
            "yaml", "yml" -> "text/yaml"
            "md" -> "text/markdown"
            "html", "htm" -> "text/html"
            "css" -> "text/css"
            "js" -> "text/javascript"
            "ts" -> "text/typescript"
            "py" -> "text/x-python"
            "rb" -> "text/x-ruby"
            "go" -> "text/x-go"
            "rs" -> "text/x-rust"
            "c", "h" -> "text/x-c"
            "cpp", "cc", "cxx", "hpp" -> "text/x-c++src"
            "sh", "bash" -> "text/x-shellscript"
            "sql" -> "text/x-sql"
            "properties" -> "text/x-java-properties"
            "gradle" -> "text/x-groovy"
            else -> "text/plain"
        }
    }

    @Serializable
    data class FileContentResult(
        val path: String,
        val content: String,
        val size: Long,
        val extension: String?,
        val encoding: String
    )

    @Serializable
    data class FileContentError(
        val error: String,
        val message: String
    )
}
