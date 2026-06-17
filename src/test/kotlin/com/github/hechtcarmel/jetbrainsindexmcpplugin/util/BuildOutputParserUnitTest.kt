package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

import junit.framework.TestCase

class BuildOutputParserUnitTest : TestCase() {

    fun testParsesMsvcErrorAndWarning() {
        val output = """
            D:\Project\app\src\VideoInfo.cpp(42,13): error C2065: 'foo': undeclared identifier
            D:\Project\app\src\VideoInfo.cpp(43): warning C4101: 'bar': unreferenced local variable
        """.trimIndent()

        val messages = BuildOutputParser.parse(output, ::relativizeWindowsPath)

        assertEquals(2, messages.size)
        assertEquals("ERROR", messages[0].category)
        assertEquals("C2065: 'foo': undeclared identifier", messages[0].message)
        assertEquals("src/VideoInfo.cpp", messages[0].file)
        assertEquals(42, messages[0].line)
        assertEquals(13, messages[0].column)

        assertEquals("WARNING", messages[1].category)
        assertEquals("C4101: 'bar': unreferenced local variable", messages[1].message)
        assertEquals("src/VideoInfo.cpp", messages[1].file)
        assertEquals(43, messages[1].line)
        assertNull(messages[1].column)
    }

    fun testNormalizesWindowsBackslashesBeforeRelativizing() {
        val output = "D:\\Project\\app\\src\\VideoInfo.cpp(42,13): error C2065: 'foo': undeclared identifier"

        val messages = BuildOutputParser.parse(output) { path ->
            if (path == "D:/Project/app/src/VideoInfo.cpp") "src/VideoInfo.cpp" else null
        }

        assertEquals(1, messages.size)
        assertEquals("src/VideoInfo.cpp", messages.single().file)
    }

    fun testParsesClangStylePathsWithWindowsDriveLetters() {
        val output = "D:/Project/app/src/VideoInfo.cpp:42:13: error: use of undeclared identifier 'foo'"

        val messages = BuildOutputParser.parse(output, ::relativizeWindowsPath)

        assertEquals(1, messages.size)
        assertEquals("ERROR", messages[0].category)
        assertEquals("use of undeclared identifier 'foo'", messages[0].message)
        assertEquals("src/VideoInfo.cpp", messages[0].file)
        assertEquals(42, messages[0].line)
        assertEquals(13, messages[0].column)
    }

    fun testParsesUnixClangWarning() {
        val output = "/repo/src/main.cpp:7:5: warning: unused variable 'x'"

        val messages = BuildOutputParser.parse(output) { it.removePrefix("/repo/") }

        assertEquals(1, messages.size)
        assertEquals("WARNING", messages[0].category)
        assertEquals("unused variable 'x'", messages[0].message)
        assertEquals("src/main.cpp", messages[0].file)
        assertEquals(7, messages[0].line)
        assertEquals(5, messages[0].column)
    }

    fun testParsesCMakeErrorLocation() {
        val output = "CMake Error at CMakeLists.txt:12 (target_link_libraries):"

        val messages = BuildOutputParser.parse(output)

        assertEquals(1, messages.size)
        assertEquals("ERROR", messages[0].category)
        assertEquals("target_link_libraries", messages[0].message)
        assertEquals("CMakeLists.txt", messages[0].file)
        assertEquals(12, messages[0].line)
        assertNull(messages[0].column)
    }

    fun testParsesCMakeContinuationLinesAsMessage() {
        val output = """
            CMake Error at CMakeLists.txt:12 (target_link_libraries):
              Cannot specify link libraries for target "foo" which is not built by this project.
              Call Stack (most recent call first):
                src/CMakeLists.txt:7 (include)
        """.trimIndent()

        val messages = BuildOutputParser.parse(output)

        assertEquals(1, messages.size)
        assertEquals("ERROR", messages[0].category)
        assertEquals(
            "Cannot specify link libraries for target \"foo\" which is not built by this project.\n" +
                "Call Stack (most recent call first):\n" +
                "src/CMakeLists.txt:7 (include)",
            messages[0].message
        )
        assertEquals("CMakeLists.txt", messages[0].file)
        assertEquals(12, messages[0].line)
    }

    fun testDeduplicatesRepeatedCompilerLines() {
        val output = """
            /repo/src/main.cpp:7:5: error: use of undeclared identifier 'x'
            /repo/src/main.cpp:7:5: error: use of undeclared identifier 'x'
        """.trimIndent()

        val messages = BuildOutputParser.parse(output) { it.removePrefix("/repo/") }

        assertEquals(1, messages.size)
        assertEquals("src/main.cpp", messages.single().file)
    }

    fun testUnrecognizedFailureTextStaysEmpty() {
        val output = "FAILED: build stopped without a compiler location"

        val messages = BuildOutputParser.parse(output)

        assertTrue(messages.isEmpty())
    }

    private fun relativizeWindowsPath(path: String): String =
        path.replace('\\', '/').removePrefix("D:/Project/app/")
}
