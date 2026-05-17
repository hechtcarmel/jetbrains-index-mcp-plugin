package com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.php

import junit.framework.TestCase

class PhpSymbolReferenceHandlerUnitTest : TestCase() {

    fun testPhpSymbolPatternAcceptsSupportedFormats() {
        val validSymbols = listOf(
            "\\App\\Service\\UserService",
            "App\\Service\\UserService",
            "\\App\\Service\\UserService::find",
            "\\App\\Service\\UserService::find()",
            "\\App\\Service\\UserService::find(int, string)",
            "\\App\\Service\\UserService::\$repository",
            "\\App\\Service\\UserService::ROLE_ADMIN"
        )

        validSymbols.forEach { symbol ->
            assertTrue("Expected PHP symbol pattern to accept: $symbol", PhpSymbolReferenceHandler.PHP_SYMBOL_PATTERN.matches(symbol))
        }
    }

    fun testPhpSymbolPatternRejectsUnsupportedFormats() {
        val invalidSymbols = listOf(
            "",
            "\\App\\Service\\",
            "\\App\\Service\\UserService::",
            "\\App\\Service\\UserService#find",
            "\\App\\Service\\UserService::\$",
            "\\App\\Service\\UserService::123INVALID"
        )

        invalidSymbols.forEach { symbol ->
            assertFalse("Expected PHP symbol pattern to reject: $symbol", PhpSymbolReferenceHandler.PHP_SYMBOL_PATTERN.matches(symbol))
        }
    }
}
