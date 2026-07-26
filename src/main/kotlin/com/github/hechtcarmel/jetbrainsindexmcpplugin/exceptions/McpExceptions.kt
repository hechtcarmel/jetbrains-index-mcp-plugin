package com.github.hechtcarmel.jetbrainsindexmcpplugin.exceptions

/**
 * JSON-RPC error codes carried by [McpException].
 *
 * These used to live in `server/models/JsonRpcModels.kt` alongside the hand-written envelope
 * models; the MCP Kotlin SDK owns the envelope now, so the codes moved to their only consumer.
 *
 * Note that tool-level failures are **not** reported with these codes any more. Per the MCP
 * spec, a tool that fails returns `CallToolResult(isError = true)` so the model can read the
 * message; a JSON-RPC error is a transport-level failure that clients surface as a hard error.
 * The codes remain meaningful for anything that genuinely is a protocol violation.
 */
object McpErrorCodes {
    const val PARSE_ERROR = -32700
    const val INVALID_REQUEST = -32600
    const val METHOD_NOT_FOUND = -32601
    const val INVALID_PARAMS = -32602
    const val INTERNAL_ERROR = -32603

    // Plugin-specific codes.
    const val INDEX_NOT_READY = -32001
    const val FILE_NOT_FOUND = -32002
    const val SYMBOL_NOT_FOUND = -32003
    const val REFACTORING_CONFLICT = -32004
}

sealed class McpException(
    message: String,
    val errorCode: Int
) : Exception(message)

class ParseErrorException(message: String) :
    McpException(message, McpErrorCodes.PARSE_ERROR)

class InvalidRequestException(message: String) :
    McpException(message, McpErrorCodes.INVALID_REQUEST)

class MethodNotFoundException(method: String) :
    McpException("Method not found: $method", McpErrorCodes.METHOD_NOT_FOUND)

class InvalidParamsException(message: String) :
    McpException(message, McpErrorCodes.INVALID_PARAMS)

class InternalErrorException(message: String) :
    McpException(message, McpErrorCodes.INTERNAL_ERROR)

/**
 * Thrown by [com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool.requireSmartMode]
 * when the IDE is indexing. Caught by the dispatcher and turned into an `isError` tool result.
 */
class IndexNotReadyException(message: String) :
    McpException(message, McpErrorCodes.INDEX_NOT_READY)

class FileNotFoundException(path: String) :
    McpException("File not found: $path", McpErrorCodes.FILE_NOT_FOUND)

class SymbolNotFoundException(message: String) :
    McpException(message, McpErrorCodes.SYMBOL_NOT_FOUND)

class RefactoringConflictException(message: String) :
    McpException(message, McpErrorCodes.REFACTORING_CONFLICT)
