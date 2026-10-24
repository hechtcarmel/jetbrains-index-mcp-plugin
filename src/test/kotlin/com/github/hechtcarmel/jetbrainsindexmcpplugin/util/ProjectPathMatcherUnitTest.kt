package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeNoException
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

class ProjectPathMatcherUnitTest {
    private lateinit var directory: Path

    @Before fun setUp() {
        directory = Files.createTempDirectory("project-path-matcher-450-").toRealPath()
    }

    @After fun tearDown() {
        Files.walk(directory).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    @Test fun ordinaryPathsPreserveBoundariesAndRootEquality() {
        val root = directory.resolve("project")
        assertEquals("src/A.kt", ProjectPathMatcher.relativePath(root.resolve("src/A.kt").toString(), root.toString()))
        assertEquals("", ProjectPathMatcher.relativePath(root.toString(), root.toString()))
        assertNull(ProjectPathMatcher.relativePath(directory.resolve("project-other/A.kt").toString(), root.toString()))
        assertNull(ProjectPathMatcher.relativePath(root.resolve("../outside/A.kt").toString(), root.toString()))
    }

    @Test fun trailingSeparatorsAndFilesystemRootKeepTheirMeaning() {
        val file = directory.resolve("A.kt")
        assertEquals("A.kt", ProjectPathMatcher.relativePath(file.toString(), "$directory/"))
        val root = directory.root
        assertEquals(root.relativize(file).toString().replace('\\', '/'),
            ProjectPathMatcher.relativePath(file.toString(), root.toString()))
    }

    @Test fun invalidAndRelativePathsDoNotMatch() {
        assertNull(ProjectPathMatcher.relativePath("relative/A.kt", directory.toString()))
        assertNull(ProjectPathMatcher.relativePath(directory.toString(), "relative"))
        assertNull(ProjectPathMatcher.relativePath("bad\u0000path", directory.toString()))
        assertNull(ProjectPathMatcher.relativePath(directory.toString(), "bad\u0000root"))
        assertEquals(directory.toString(), ProjectPathMatcher.findMatchingRoot(directory.toString(),
            listOf("bad\u0000root", directory.toString())))
        assertNull(ProjectPathMatcher.findMatchingRoot(directory.toString(), emptyList()))
    }

    @Test fun aliasesMatchInBothDirectionsIncludingMissingSuffixes() {
        val root = Files.createDirectory(directory.resolve("Long project name"))
        val alias = symbolicLink("SHORT~1", root)
        val file = Files.writeString(Files.createDirectory(root.resolve("src")).resolve("A.kt"), "fixture")
        assertTrue(Files.isSameFile(file, alias.resolve("src/A.kt")))
        assertEquals("src/A.kt", ProjectPathMatcher.relativePath(file.toString(), alias.toString()))
        assertEquals("src/A.kt", ProjectPathMatcher.relativePath(alias.resolve("src/A.kt").toString(), root.toString()))
        assertEquals("", ProjectPathMatcher.relativePath(root.toString(), alias.toString()))
        assertEquals("future/Deleted.kt", ProjectPathMatcher.relativePath(root.resolve("future/Deleted.kt").toString(), alias.toString()))
        assertNull(ProjectPathMatcher.relativePath(directory.resolve("Long project name-other/A.kt").toString(), alias.toString()))
    }

    @Test fun mostSpecificRootWinsAcrossDifferentAliasLengths() {
        val outer = Files.createDirectory(directory.resolve("outer"))
        val nested = Files.createDirectory(outer.resolve("nested"))
        val alias = symbolicLink("very-long-spelling-for-the-outer-root", outer)
        val file = Files.writeString(nested.resolve("A.kt"), "fixture")
        assertEquals(nested.toString(), ProjectPathMatcher.findMatchingRoot(file.toString(), listOf(alias.toString(), nested.toString())))
        assertEquals(nested.toString(), ProjectPathMatcher.findMatchingRoot(alias.resolve("nested/A.kt").toString(), listOf(alias.toString(), nested.toString())))
        assertEquals(alias.toString(), ProjectPathMatcher.findMatchingRoot(alias.resolve("A.kt").toString(), listOf(outer.toString(), alias.toString())))
        assertEquals(outer.toString(), ProjectPathMatcher.findMatchingRoot(outer.resolve("A.kt").toString(), listOf(alias.toString(), outer.toString())))
    }

    @Test fun unresolvedAliasDoesNotInventAnUnrelatedMatch() {
        val loop = symbolicLink("loop", Path.of("loop"))
        val unrelated = directory.resolve("unrelated")
        assertNull(ProjectPathMatcher.relativePath(loop.resolve("A.kt").toString(), unrelated.toString()))
        assertNull(ProjectPathMatcher.findMatchingRoot(loop.resolve("A.kt").toString(), listOf(unrelated.toString())))
        // An unavailable physical identity does not break an ordinary, explicitly registered path.
        assertEquals("A.kt", ProjectPathMatcher.relativePath(loop.resolve("A.kt").toString(), loop.toString()))
    }

    private fun symbolicLink(name: String, target: Path): Path {
        val link = directory.resolve(name)
        try {
            Files.createSymbolicLink(link, target)
        } catch (e: IOException) {
            assumeNoException("Symbolic links unavailable on this test filesystem", e)
        } catch (e: UnsupportedOperationException) {
            assumeNoException("Symbolic links unsupported", e)
        } catch (e: SecurityException) {
            assumeNoException("Symbolic links not permitted", e)
        }
        return link
    }
}
