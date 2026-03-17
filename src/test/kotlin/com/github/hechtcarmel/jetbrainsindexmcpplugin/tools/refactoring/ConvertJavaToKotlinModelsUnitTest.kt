package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import junit.framework.TestCase
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Unit tests for ConvertJavaToKotlinTool data models serialization.
 * These tests verify that the result classes serialize correctly for MCP responses.
 */
class ConvertJavaToKotlinModelsUnitTest : TestCase() {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    // ConvertedFileInfo tests

    fun testConvertedFileInfoSerialization() {
        val info = ConvertedFileInfo(
            originalJavaFile = "src/Main.java",
            newKotlinFile = "src/Main.kt",
            linesConverted = 42,
            deleted = true
        )

        val serialized = json.encodeToString(info)
        val deserialized = json.decodeFromString<ConvertedFileInfo>(serialized)

        assertEquals("src/Main.java", deserialized.originalJavaFile)
        assertEquals("src/Main.kt", deserialized.newKotlinFile)
        assertEquals(42, deserialized.linesConverted)
        assertTrue("Original should be deleted", deserialized.deleted)
    }

    fun testConvertedFileInfoKeepOriginal() {
        val info = ConvertedFileInfo(
            originalJavaFile = "Test.java",
            newKotlinFile = "Test.kt",
            linesConverted = 10,
            deleted = false
        )

        val serialized = json.encodeToString(info)
        val deserialized = json.decodeFromString<ConvertedFileInfo>(serialized)

        assertFalse("Original should be kept", deserialized.deleted)
    }

    fun testConvertedFileInfoIncludesAllFields() {
        val info = ConvertedFileInfo(
            originalJavaFile = "Utils.java",
            newKotlinFile = "Utils.kt",
            linesConverted = 100,
            deleted = true
        )

        val serialized = json.encodeToString(info)

        assertTrue("Should contain originalJavaFile", serialized.contains("\"originalJavaFile\""))
        assertTrue("Should contain newKotlinFile", serialized.contains("\"newKotlinFile\""))
        assertTrue("Should contain linesConverted", serialized.contains("\"linesConverted\""))
        assertTrue("Should contain deleted", serialized.contains("\"deleted\""))
    }

    // JavaToKotlinConversionResult tests

    fun testJavaToKotlinConversionResultSerialization() {
        val files = listOf(
            ConvertedFileInfo("A.java", "A.kt", 20, true),
            ConvertedFileInfo("B.java", "B.kt", 30, true)
        )

        val result = JavaToKotlinConversionResult(
            success = true,
            convertedFiles = files,
            message = "Successfully converted 2 Java file(s) to Kotlin"
        )

        val serialized = json.encodeToString(result)
        val deserialized = json.decodeFromString<JavaToKotlinConversionResult>(serialized)

        assertTrue("Conversion should succeed", deserialized.success)
        assertEquals(2, deserialized.convertedFiles.size)
        assertTrue("Message should mention success", deserialized.message.contains("Successfully"))
    }

    fun testJavaToKotlinConversionResultIncludesAllFields() {
        val result = JavaToKotlinConversionResult(
            success = true,
            convertedFiles = listOf(ConvertedFileInfo("X.java", "X.kt", 5, true)),
            message = "Done"
        )

        val serialized = json.encodeToString(result)

        assertTrue("Should contain success", serialized.contains("\"success\""))
        assertTrue("Should contain convertedFiles", serialized.contains("\"convertedFiles\""))
        assertTrue("Should contain message", serialized.contains("\"message\""))
    }

    // Edge cases

    fun testConvertedFileInfoWithLongPaths() {
        val longPath = "src/main/java/com/example/very/long/package/path/MyClass.java"
        val info = ConvertedFileInfo(
            originalJavaFile = longPath,
            newKotlinFile = longPath.replace(".java", ".kt"),
            linesConverted = 200,
            deleted = true
        )

        val serialized = json.encodeToString(info)
        val deserialized = json.decodeFromString<ConvertedFileInfo>(serialized)

        assertEquals(longPath, deserialized.originalJavaFile)
        assertTrue("Kotlin path should end with .kt", deserialized.newKotlinFile.endsWith(".kt"))
    }

    fun testJavaToKotlinConversionResultWithEmptyFiles() {
        val result = JavaToKotlinConversionResult(
            success = true,
            convertedFiles = emptyList(),
            message = "No files converted"
        )

        val serialized = json.encodeToString(result)
        val deserialized = json.decodeFromString<JavaToKotlinConversionResult>(serialized)

        assertTrue("Files should be empty", deserialized.convertedFiles.isEmpty())
    }

    fun testJavaToKotlinConversionResultMultipleFiles() {
        val files = (1..10).map { i ->
            ConvertedFileInfo("File$i.java", "File$i.kt", i * 10, true)
        }

        val result = JavaToKotlinConversionResult(
            success = true,
            convertedFiles = files,
            message = "Converted 10 files"
        )

        val serialized = json.encodeToString(result)
        val deserialized = json.decodeFromString<JavaToKotlinConversionResult>(serialized)

        assertEquals(10, deserialized.convertedFiles.size)
        assertEquals("File5.java", deserialized.convertedFiles[4].originalJavaFile)
        assertEquals(50, deserialized.convertedFiles[4].linesConverted)
    }

    fun testJavaToKotlinConversionResultWithSpecialCharacters() {
        val info = ConvertedFileInfo(
            originalJavaFile = "My\$Special-Class.java",
            newKotlinFile = "My\$Special-Class.kt",
            linesConverted = 15,
            deleted = false
        )

        val result = JavaToKotlinConversionResult(
            success = true,
            convertedFiles = listOf(info),
            message = "Converted with \$pecial characters"
        )

        val serialized = json.encodeToString(result)
        val deserialized = json.decodeFromString<JavaToKotlinConversionResult>(serialized)

        assertEquals("My\$Special-Class.java", deserialized.convertedFiles[0].originalJavaFile)
    }

    fun testConvertedFileInfoZeroLines() {
        // Edge case: empty file converted
        val info = ConvertedFileInfo(
            originalJavaFile = "Empty.java",
            newKotlinFile = "Empty.kt",
            linesConverted = 0,
            deleted = true
        )

        val serialized = json.encodeToString(info)
        val deserialized = json.decodeFromString<ConvertedFileInfo>(serialized)

        assertEquals(0, deserialized.linesConverted)
    }
}
