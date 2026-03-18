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

    // ConversionStatus tests

    fun testConversionStatusSerialization() {
        val converted = json.encodeToString(ConversionStatus.CONVERTED)
        val skipped = json.encodeToString(ConversionStatus.SKIPPED)
        val failed = json.encodeToString(ConversionStatus.FAILED)

        assertEquals("\"CONVERTED\"", converted)
        assertEquals("\"SKIPPED\"", skipped)
        assertEquals("\"FAILED\"", failed)
    }

    // FileConversionResult tests

    fun testFileConversionResultConverted() {
        val result = FileConversionResult(
            requestedPath = "src/Main.java",
            status = ConversionStatus.CONVERTED,
            kotlinFile = "src/Main.kt",
            linesConverted = 42,
            javaFileDeleted = true
        )

        val serialized = json.encodeToString(result)
        val deserialized = json.decodeFromString<FileConversionResult>(serialized)

        assertEquals("src/Main.java", deserialized.requestedPath)
        assertEquals(ConversionStatus.CONVERTED, deserialized.status)
        assertEquals("src/Main.kt", deserialized.kotlinFile)
        assertEquals(42, deserialized.linesConverted)
        assertEquals(true, deserialized.javaFileDeleted)
        assertNull("Reason should be null for successful conversion", deserialized.reason)
    }

    fun testFileConversionResultSkipped() {
        val result = FileConversionResult(
            requestedPath = "missing.java",
            status = ConversionStatus.SKIPPED,
            reason = "File not found"
        )

        val serialized = json.encodeToString(result)
        val deserialized = json.decodeFromString<FileConversionResult>(serialized)

        assertEquals("missing.java", deserialized.requestedPath)
        assertEquals(ConversionStatus.SKIPPED, deserialized.status)
        assertEquals("File not found", deserialized.reason)
        assertNull("kotlinFile should be null for skipped", deserialized.kotlinFile)
        assertNull("linesConverted should be null for skipped", deserialized.linesConverted)
        assertNull("javaFileDeleted should be null for skipped", deserialized.javaFileDeleted)
    }

    fun testFileConversionResultFailed() {
        val result = FileConversionResult(
            requestedPath = "bad.java",
            status = ConversionStatus.FAILED,
            reason = "Conversion error: syntax error"
        )

        val serialized = json.encodeToString(result)
        val deserialized = json.decodeFromString<FileConversionResult>(serialized)

        assertEquals("bad.java", deserialized.requestedPath)
        assertEquals(ConversionStatus.FAILED, deserialized.status)
        assertEquals("Conversion error: syntax error", deserialized.reason)
    }

    fun testFileConversionResultIncludesAllFields() {
        val result = FileConversionResult(
            requestedPath = "Test.java",
            status = ConversionStatus.CONVERTED,
            kotlinFile = "Test.kt",
            linesConverted = 10,
            javaFileDeleted = false
        )

        val serialized = json.encodeToString(result)

        assertTrue("Should contain requestedPath", serialized.contains("\"requestedPath\""))
        assertTrue("Should contain status", serialized.contains("\"status\""))
        assertTrue("Should contain kotlinFile", serialized.contains("\"kotlinFile\""))
        assertTrue("Should contain linesConverted", serialized.contains("\"linesConverted\""))
        assertTrue("Should contain javaFileDeleted", serialized.contains("\"javaFileDeleted\""))
    }

    // ConversionSummary tests

    fun testConversionSummarySerialization() {
        val summary = ConversionSummary(
            totalRequested = 5,
            converted = 3,
            skipped = 1,
            failed = 1
        )

        val serialized = json.encodeToString(summary)
        val deserialized = json.decodeFromString<ConversionSummary>(serialized)

        assertEquals(5, deserialized.totalRequested)
        assertEquals(3, deserialized.converted)
        assertEquals(1, deserialized.skipped)
        assertEquals(1, deserialized.failed)
    }

    // JavaToKotlinConversionResult tests

    fun testJavaToKotlinConversionResultFullSuccess() {
        val files = listOf(
            FileConversionResult("A.java", ConversionStatus.CONVERTED, "A.kt", 20, true),
            FileConversionResult("B.java", ConversionStatus.CONVERTED, "B.kt", 30, true)
        )
        val summary = ConversionSummary(
            totalRequested = 2,
            converted = 2,
            skipped = 0,
            failed = 0
        )

        val result = JavaToKotlinConversionResult(files = files, summary = summary)

        val serialized = json.encodeToString(result)
        val deserialized = json.decodeFromString<JavaToKotlinConversionResult>(serialized)

        assertEquals(2, deserialized.files.size)
        assertEquals(2, deserialized.summary.converted)
        assertEquals(0, deserialized.summary.skipped)
        assertEquals(0, deserialized.summary.failed)
    }

    fun testJavaToKotlinConversionResultPartialSuccess() {
        val files = listOf(
            FileConversionResult("A.java", ConversionStatus.CONVERTED, "A.kt", 20, true),
            FileConversionResult("missing.java", ConversionStatus.SKIPPED, reason = "File not found"),
            FileConversionResult("bad.java", ConversionStatus.FAILED, reason = "Conversion error")
        )
        val summary = ConversionSummary(
            totalRequested = 3,
            converted = 1,
            skipped = 1,
            failed = 1
        )

        val result = JavaToKotlinConversionResult(files = files, summary = summary)

        val serialized = json.encodeToString(result)
        val deserialized = json.decodeFromString<JavaToKotlinConversionResult>(serialized)

        assertEquals(3, deserialized.files.size)
        assertEquals(1, deserialized.summary.converted)
        assertEquals(1, deserialized.summary.skipped)
        assertEquals(1, deserialized.summary.failed)

        // Verify individual file results
        assertEquals(ConversionStatus.CONVERTED, deserialized.files[0].status)
        assertEquals(ConversionStatus.SKIPPED, deserialized.files[1].status)
        assertEquals(ConversionStatus.FAILED, deserialized.files[2].status)
    }

    fun testJavaToKotlinConversionResultIncludesAllFields() {
        val files = listOf(FileConversionResult("X.java", ConversionStatus.CONVERTED, "X.kt", 5, true))
        val summary = ConversionSummary(1, 1, 0, 0)
        val result = JavaToKotlinConversionResult(files = files, summary = summary)

        val serialized = json.encodeToString(result)

        assertTrue("Should contain files", serialized.contains("\"files\""))
        assertTrue("Should contain summary", serialized.contains("\"summary\""))
        assertTrue("Should contain totalRequested", serialized.contains("\"totalRequested\""))
        assertTrue("Should contain converted", serialized.contains("\"converted\""))
        assertTrue("Should contain skipped", serialized.contains("\"skipped\""))
        assertTrue("Should contain failed", serialized.contains("\"failed\""))
    }

    // Edge cases

    fun testFileConversionResultWithLongPaths() {
        val longPath = "src/main/java/com/example/very/long/package/path/MyClass.java"
        val result = FileConversionResult(
            requestedPath = longPath,
            status = ConversionStatus.CONVERTED,
            kotlinFile = longPath.replace(".java", ".kt"),
            linesConverted = 200,
            javaFileDeleted = true
        )

        val serialized = json.encodeToString(result)
        val deserialized = json.decodeFromString<FileConversionResult>(serialized)

        assertEquals(longPath, deserialized.requestedPath)
        assertTrue("Kotlin path should end with .kt", deserialized.kotlinFile!!.endsWith(".kt"))
    }

    fun testJavaToKotlinConversionResultWithEmptyFiles() {
        val summary = ConversionSummary(0, 0, 0, 0)
        val result = JavaToKotlinConversionResult(
            files = emptyList(),
            summary = summary
        )

        val serialized = json.encodeToString(result)
        val deserialized = json.decodeFromString<JavaToKotlinConversionResult>(serialized)

        assertTrue("Files should be empty", deserialized.files.isEmpty())
        assertEquals(0, deserialized.summary.totalRequested)
    }

    fun testJavaToKotlinConversionResultMultipleFiles() {
        val files = (1..10).map { i ->
            FileConversionResult("File$i.java", ConversionStatus.CONVERTED, "File$i.kt", i * 10, true)
        }
        val summary = ConversionSummary(10, 10, 0, 0)

        val result = JavaToKotlinConversionResult(files = files, summary = summary)

        val serialized = json.encodeToString(result)
        val deserialized = json.decodeFromString<JavaToKotlinConversionResult>(serialized)

        assertEquals(10, deserialized.files.size)
        assertEquals("File5.java", deserialized.files[4].requestedPath)
        assertEquals(50, deserialized.files[4].linesConverted)
    }

    fun testJavaToKotlinConversionResultPreservesDuplicateRequestedPaths() {
        val files = listOf(
            FileConversionResult("Duplicate.java", ConversionStatus.SKIPPED, reason = "File not found"),
            FileConversionResult("Duplicate.java", ConversionStatus.SKIPPED, reason = "File not found")
        )
        val summary = ConversionSummary(2, 0, 2, 0)

        val result = JavaToKotlinConversionResult(files = files, summary = summary)

        val serialized = json.encodeToString(result)
        val deserialized = json.decodeFromString<JavaToKotlinConversionResult>(serialized)

        assertEquals(2, deserialized.files.size)
        assertEquals("Duplicate.java", deserialized.files[0].requestedPath)
        assertEquals("Duplicate.java", deserialized.files[1].requestedPath)
        assertEquals(2, deserialized.summary.totalRequested)
        assertEquals(2, deserialized.summary.skipped)
    }

    fun testJavaToKotlinConversionResultWithSpecialCharacters() {
        val fileResult = FileConversionResult(
            requestedPath = "My\$Special-Class.java",
            status = ConversionStatus.CONVERTED,
            kotlinFile = "My\$Special-Class.kt",
            linesConverted = 15,
            javaFileDeleted = false
        )
        val summary = ConversionSummary(1, 1, 0, 0)

        val result = JavaToKotlinConversionResult(
            files = listOf(fileResult),
            summary = summary
        )

        val serialized = json.encodeToString(result)
        val deserialized = json.decodeFromString<JavaToKotlinConversionResult>(serialized)

        assertEquals("My\$Special-Class.java", deserialized.files[0].requestedPath)
    }

    fun testFileConversionResultZeroLines() {
        // Edge case: empty file converted
        val result = FileConversionResult(
            requestedPath = "Empty.java",
            status = ConversionStatus.CONVERTED,
            kotlinFile = "Empty.kt",
            linesConverted = 0,
            javaFileDeleted = true
        )

        val serialized = json.encodeToString(result)
        val deserialized = json.decodeFromString<FileConversionResult>(serialized)

        assertEquals(0, deserialized.linesConverted)
    }

    fun testFileConversionResultWithMultipleReasons() {
        // Test different skip reasons
        val reasons = listOf(
            "File not found",
            "Not a Java file (.java extension required)",
            "No module found for file",
            "Module 'app' does not have Kotlin plugin enabled"
        )

        for (reason in reasons) {
            val result = FileConversionResult(
                requestedPath = "test.java",
                status = ConversionStatus.SKIPPED,
                reason = reason
            )

            val serialized = json.encodeToString(result)
            val deserialized = json.decodeFromString<FileConversionResult>(serialized)

            assertEquals(ConversionStatus.SKIPPED, deserialized.status)
            assertEquals(reason, deserialized.reason)
        }
    }

    fun testConversionSummaryAllStatuses() {
        // Test summary with all status counts
        val summary = ConversionSummary(
            totalRequested = 10,
            converted = 5,
            skipped = 3,
            failed = 2
        )

        val serialized = json.encodeToString(summary)
        val deserialized = json.decodeFromString<ConversionSummary>(serialized)

        assertEquals(10, deserialized.totalRequested)
        assertEquals(5, deserialized.converted)
        assertEquals(3, deserialized.skipped)
        assertEquals(2, deserialized.failed)
        // Verify total matches
        assertEquals(
            deserialized.totalRequested,
            deserialized.converted + deserialized.skipped + deserialized.failed
        )
    }
}
