package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Uses actual Windows-generated short names; a Linux symlink is not a substitute. */
class ProjectPathMatcherWindowsUnitTest {
    @Test fun driveAndUncRootsKeepTheirRootComponents() {
        assumeTrue("Requires the Windows Path provider", System.getProperty("os.name").startsWith("Windows"))
        assertEquals("workspace/A.kt", ProjectPathMatcher.relativePath("C:/workspace/A.kt", "C:/"))
        assertEquals("", ProjectPathMatcher.relativePath("C:/", "C:\\"))
        // These are lexical matches: no connection to a real UNC server is made.
        assertEquals("workspace/A.kt", ProjectPathMatcher.relativePath(
            "\\\\server\\share\\workspace\\A.kt", "\\\\server\\share\\"))
    }

    @Test fun actualShortNamesMatchLongNamesInBothDirections() {
        assumeTrue("Requires Windows and an NTFS short-name alias", System.getProperty("os.name").startsWith("Windows"))
        val root = Files.createTempDirectory("Index long project name 450 ").toRealPath()
        try {
            // %~sI asks Windows for the actual short spelling. The path is passed as data in an
            // environment variable; no guessed ~1 name and no filesystem/settings changes.
            val builder = ProcessBuilder("cmd.exe", "/d", "/c", "for %I in (\"%IDE_INDEX_LONG_PATH%\") do @echo %~sI")
                .redirectErrorStream(true)
            builder.environment()["IDE_INDEX_LONG_PATH"] = root.toString()
            val process = builder.start()
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                fail("Timed out asking Windows for a short path")
            }
            val output = process.inputStream.bufferedReader().use { it.readText().trim() }
            assertEquals("cmd failed to resolve a short filename: $output", 0, process.exitValue())
            val shortRoot = Path.of(output)
            assumeFalse("8.3 short names are disabled for this filesystem; no real alias to test",
                shortRoot.toString().equals(root.toString(), ignoreCase = true))
            assertTrue("Windows short and long names must identify the same root", Files.isSameFile(root, shortRoot))
            val nested = Files.createDirectory(root.resolve("nested"))
            val file = Files.writeString(nested.resolve("A.kt"), "fixture")
            assertTrue(Files.isSameFile(file, shortRoot.resolve("nested/A.kt")))
            assertEquals("nested/A.kt", ProjectPathMatcher.relativePath(file.toString(), shortRoot.toString()))
            assertEquals("nested/A.kt", ProjectPathMatcher.relativePath(shortRoot.resolve("nested/A.kt").toString(), root.toString()))
            assertEquals("A.kt", ProjectPathMatcher.relativePath(file.toString(), nested.toString()))
            assertEquals(nested.toString(), ProjectPathMatcher.findMatchingRoot(file.toString(), listOf(shortRoot.toString(), nested.toString())))
            assertNull(ProjectPathMatcher.relativePath(root.resolveSibling(root.fileName.toString() + "-other").resolve("A.kt").toString(), shortRoot.toString()))
            assertEquals("nested/A.kt", ProjectPathMatcher.relativePath(file.toString().lowercase(), shortRoot.toString().uppercase()))
            assertEquals("nested/A.kt", ProjectPathMatcher.relativePath(file.toString().replace('\\', '/'), shortRoot.toString() + "\\"))
            println("Verified actual Windows 8.3 alias: ${shortRoot.fileName} -> ${root.fileName}")
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }
}
