package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import junit.framework.TestCase
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Unit tests for SafeDeleteTool data models serialization.
 * These tests verify that the result classes serialize correctly for MCP responses.
 */
class SafeDeleteModelsUnitTest : TestCase() {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    // SymbolSuggestion tests

    fun testSymbolSuggestionSerialization() {
        val suggestion = SymbolSuggestion(
            name = "myMethod",
            type = "method",
            line = 10,
            column = 5,
            distance = 3
        )

        val serialized = json.encodeToString(suggestion)
        val deserialized = json.decodeFromString<SymbolSuggestion>(serialized)

        assertEquals("myMethod", deserialized.name)
        assertEquals("method", deserialized.type)
        assertEquals(10, deserialized.line)
        assertEquals(5, deserialized.column)
        assertEquals(3, deserialized.distance)
    }

    fun testSymbolSuggestionIncludesAllFields() {
        val suggestion = SymbolSuggestion(
            name = "TestClass",
            type = "class",
            line = 1,
            column = 1,
            distance = 0
        )

        val serialized = json.encodeToString(suggestion)

        assertTrue("Should contain name", serialized.contains("\"name\""))
        assertTrue("Should contain type", serialized.contains("\"type\""))
        assertTrue("Should contain line", serialized.contains("\"line\""))
        assertTrue("Should contain column", serialized.contains("\"column\""))
        assertTrue("Should contain distance", serialized.contains("\"distance\""))
    }

    // SymbolInfo tests

    fun testSymbolInfoSerialization() {
        val info = SymbolInfo(
            name = "processData",
            type = "method",
            line = 25,
            column = 12
        )

        val serialized = json.encodeToString(info)
        val deserialized = json.decodeFromString<SymbolInfo>(serialized)

        assertEquals("processData", deserialized.name)
        assertEquals("method", deserialized.type)
        assertEquals(25, deserialized.line)
        assertEquals(12, deserialized.column)
    }

    // PositionInfo tests

    fun testPositionInfoSerialization() {
        val position = PositionInfo(
            line = 15,
            column = 8,
            elementType = "whitespace"
        )

        val serialized = json.encodeToString(position)
        val deserialized = json.decodeFromString<PositionInfo>(serialized)

        assertEquals(15, deserialized.line)
        assertEquals(8, deserialized.column)
        assertEquals("whitespace", deserialized.elementType)
    }

    // NoSymbolFoundResult tests

    fun testNoSymbolFoundResultSerialization() {
        val suggestions = listOf(
            SymbolSuggestion("methodA", "method", 10, 5, 2),
            SymbolSuggestion("ClassB", "class", 18, 1, 6)
        )

        val result = NoSymbolFoundResult(
            error = "No symbol found at line 14, column 8 (found whitespace)",
            position = PositionInfo(14, 8, "whitespace"),
            suggestions = suggestions,
            hint = "Try one of the suggested symbols"
        )

        val serialized = json.encodeToString(result)
        val deserialized = json.decodeFromString<NoSymbolFoundResult>(serialized)

        assertEquals("No symbol found at line 14, column 8 (found whitespace)", deserialized.error)
        assertEquals(14, deserialized.position.line)
        assertEquals(8, deserialized.position.column)
        assertEquals("whitespace", deserialized.position.elementType)
        assertEquals(2, deserialized.suggestions.size)
        assertEquals("methodA", deserialized.suggestions[0].name)
        assertEquals("ClassB", deserialized.suggestions[1].name)
        assertEquals("Try one of the suggested symbols", deserialized.hint)
    }

    fun testNoSymbolFoundResultWithEmptySuggestions() {
        val result = NoSymbolFoundResult(
            error = "No symbol found",
            position = PositionInfo(1, 1, "comment"),
            suggestions = emptyList(),
            hint = "Use target_type=\"file\" to delete the entire file"
        )

        val serialized = json.encodeToString(result)
        val deserialized = json.decodeFromString<NoSymbolFoundResult>(serialized)

        assertTrue("Suggestions should be empty", deserialized.suggestions.isEmpty())
    }

    // UsageInfo tests

    fun testUsageInfoSerialization() {
        val usage = UsageInfo(
            file = "src/main/MyClass.java",
            line = 42,
            column = 15,
            context = "myService.doSomething()"
        )

        val serialized = json.encodeToString(usage)
        val deserialized = json.decodeFromString<UsageInfo>(serialized)

        assertEquals("src/main/MyClass.java", deserialized.file)
        assertEquals(42, deserialized.line)
        assertEquals(15, deserialized.column)
        assertEquals("myService.doSomething()", deserialized.context)
    }

    // SafeDeleteBlockedResult tests

    fun testSafeDeleteBlockedResultSerialization() {
        val usages = listOf(
            UsageInfo("src/Main.java", 10, 5, "obj.method()"),
            UsageInfo("src/Test.java", 20, 10, "new MyClass()")
        )

        val result = SafeDeleteBlockedResult(
            canDelete = false,
            elementName = "MyClass",
            elementType = "class",
            usageCount = 2,
            blockingUsages = usages,
            message = "Cannot delete 'MyClass': found 2 usage(s)."
        )

        val serialized = json.encodeToString(result)
        val deserialized = json.decodeFromString<SafeDeleteBlockedResult>(serialized)

        assertFalse("canDelete should be false", deserialized.canDelete)
        assertEquals("MyClass", deserialized.elementName)
        assertEquals("class", deserialized.elementType)
        assertEquals(2, deserialized.usageCount)
        assertEquals(2, deserialized.blockingUsages.size)
        assertTrue("Message should mention cannot delete", deserialized.message.contains("Cannot delete"))
    }

    // SafeDeleteFileBlockedResult tests

    fun testSafeDeleteFileBlockedResultSerialization() {
        val usages = listOf(
            UsageInfo("src/Other.java", 5, 10, "import com.example.Utils;"),
            UsageInfo("src/Main.java", 15, 3, "Utils.helper()")
        )

        val result = SafeDeleteFileBlockedResult(
            canDelete = false,
            fileName = "Utils.java",
            symbolCount = 5,
            externalUsageCount = 2,
            blockingUsages = usages,
            message = "Cannot delete file 'Utils.java': found 2 external usage(s)."
        )

        val serialized = json.encodeToString(result)
        val deserialized = json.decodeFromString<SafeDeleteFileBlockedResult>(serialized)

        assertFalse("canDelete should be false", deserialized.canDelete)
        assertEquals("Utils.java", deserialized.fileName)
        assertEquals(5, deserialized.symbolCount)
        assertEquals(2, deserialized.externalUsageCount)
        assertEquals(2, deserialized.blockingUsages.size)
        assertTrue("Message should mention external usages", deserialized.message.contains("external usage"))
    }

    fun testSafeDeleteFileBlockedResultDistinguishesExternalUsages() {
        // This test verifies the semantic difference between symbol and file deletion results
        val symbolResult = SafeDeleteBlockedResult(
            canDelete = false,
            elementName = "myMethod",
            elementType = "method",
            usageCount = 3,
            blockingUsages = emptyList(),
            message = "Has usages"
        )

        val fileResult = SafeDeleteFileBlockedResult(
            canDelete = false,
            fileName = "MyFile.java",
            symbolCount = 10,
            externalUsageCount = 3, // Only external usages matter for file deletion
            blockingUsages = emptyList(),
            message = "Has external usages"
        )

        val symbolSerialized = json.encodeToString(symbolResult)
        val fileSerialized = json.encodeToString(fileResult)

        // Symbol result has elementName/elementType
        assertTrue("Symbol result should have elementName", symbolSerialized.contains("elementName"))
        assertTrue("Symbol result should have elementType", symbolSerialized.contains("elementType"))

        // File result has fileName/symbolCount/externalUsageCount
        assertTrue("File result should have fileName", fileSerialized.contains("fileName"))
        assertTrue("File result should have symbolCount", fileSerialized.contains("symbolCount"))
        assertTrue("File result should have externalUsageCount", fileSerialized.contains("externalUsageCount"))
    }

    // Edge cases

    fun testSymbolSuggestionWithSpecialCharactersInName() {
        val suggestion = SymbolSuggestion(
            name = "get\$value",
            type = "method",
            line = 1,
            column = 1,
            distance = 0
        )

        val serialized = json.encodeToString(suggestion)
        val deserialized = json.decodeFromString<SymbolSuggestion>(serialized)

        assertEquals("get\$value", deserialized.name)
    }

    fun testUsageInfoWithLongContext() {
        val longContext = "a".repeat(500)
        val usage = UsageInfo(
            file = "test.java",
            line = 1,
            column = 1,
            context = longContext
        )

        val serialized = json.encodeToString(usage)
        val deserialized = json.decodeFromString<UsageInfo>(serialized)

        assertEquals(longContext, deserialized.context)
    }
}
