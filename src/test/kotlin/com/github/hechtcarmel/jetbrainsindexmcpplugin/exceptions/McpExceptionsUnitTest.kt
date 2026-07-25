package com.github.hechtcarmel.jetbrainsindexmcpplugin.exceptions

import junit.framework.TestCase

class McpExceptionsUnitTest : TestCase() {

    fun testEachExceptionCarriesItsWireErrorCode() {
        assertEquals(-32700, ParseErrorException("test").errorCode)
        assertEquals(-32600, InvalidRequestException("test").errorCode)
        assertEquals(-32601, MethodNotFoundException("test").errorCode)
        assertEquals(-32602, InvalidParamsException("test").errorCode)
        assertEquals(-32603, InternalErrorException("test").errorCode)
        assertEquals(-32001, IndexNotReadyException("test").errorCode)
        assertEquals(-32002, FileNotFoundException("test").errorCode)
        assertEquals(-32003, SymbolNotFoundException("test").errorCode)
        assertEquals(-32004, RefactoringConflictException("test").errorCode)
    }

    fun testMethodNotFoundExceptionFormatsMessage() {
        val exception = MethodNotFoundException("tools/execute")

        assertEquals("Method not found: tools/execute", exception.message)
    }

    fun testFileNotFoundExceptionFormatsPath() {
        val exception = FileNotFoundException("/absolute/path/to/file.java")

        assertEquals("File not found: /absolute/path/to/file.java", exception.message)
    }
}
