package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation

import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.SearchTextResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Behavior of the `paths` parameter on `ide_search_text` (issue #328): project-relative
 * globs restrict the search scope, `!` entries exclude, and an include glob whose literal
 * directory prefix does not exist fails loudly instead of returning zero matches.
 *
 * Fixtures use invented needle strings unique per test method: the search is project-wide
 * and the light-fixture project directory is shared across methods, so a reused needle
 * would make exact-count assertions order-dependent.
 */
class SearchTextPathsBehaviorTest : McpPlatformTestCase() {

    private val json = Json { ignoreUnknownKeys = true }

    private fun decode(resultText: String): SearchTextResult = json.decodeFromString(resultText)

    fun testPathsIncludeRestrictsToDirectory() = runBlocking {
        registerSourceRoot("stx-inc-src")
        writeProjectFile("stx-inc-src/alpha/AlphaHit.java", "class AlphaHit { String s = \"stxIncNeedle\"; }")
        writeProjectFile("stx-inc-src/beta/BetaHit.java", "class BetaHit { String s = \"stxIncNeedle\"; }")

        val result = SearchTextTool().execute(project, buildJsonObject {
            put("query", "stxIncNeedle")
            putJsonArray("paths") { add("stx-inc-src/alpha/**") }
        })

        assertToolSucceeded("Search with include glob should succeed", result)
        val parsed = decode(toolText(result))
        assertEquals(
            "Only the alpha file may match",
            listOf("stx-inc-src/alpha/AlphaHit.java"),
            parsed.matches.map { it.file }
        )
    }

    fun testPathsExcludeRemovesDirectory() = runBlocking {
        registerSourceRoot("stx-exc-src")
        writeProjectFile("stx-exc-src/keep/KeepHit.java", "class KeepHit { String s = \"stxExcNeedle\"; }")
        writeProjectFile("stx-exc-src/drop/DropHit.java", "class DropHit { String s = \"stxExcNeedle\"; }")

        val result = SearchTextTool().execute(project, buildJsonObject {
            put("query", "stxExcNeedle")
            putJsonArray("paths") { add("!**/drop/**") }
        })

        assertToolSucceeded("Search with exclude glob should succeed", result)
        val parsed = decode(toolText(result))
        assertEquals(
            "The excluded directory's file must be gone, the other kept",
            listOf("stx-exc-src/keep/KeepHit.java"),
            parsed.matches.map { it.file }
        )
    }

    fun testPlainDirectoryEntryMatchesEverythingBeneathIt() = runBlocking {
        registerSourceRoot("stx-dir-src")
        writeProjectFile("stx-dir-src/a/DirHitOne.java", "class DirHitOne { String s = \"stxDirNeedle\"; }")
        writeProjectFile("stx-dir-src/a/b/DirHitTwo.java", "class DirHitTwo { String s = \"stxDirNeedle\"; }")

        val result = SearchTextTool().execute(project, buildJsonObject {
            put("query", "stxDirNeedle")
            putJsonArray("paths") { add("stx-dir-src/a") }
        })

        assertToolSucceeded("Search with plain directory entry should succeed", result)
        val parsed = decode(toolText(result))
        assertEquals(
            "Both nested files must match beneath the plain directory entry",
            setOf("stx-dir-src/a/DirHitOne.java", "stx-dir-src/a/b/DirHitTwo.java"),
            parsed.matches.map { it.file }.toSet()
        )
    }

    fun testPathsComposesWithFilePattern() = runBlocking {
        registerSourceRoot("stx-mask-src")
        writeProjectFile("stx-mask-src/alpha/MaskHit.java", "class MaskHit { String s = \"stxMaskNeedle\"; }")
        writeProjectFile("stx-mask-src/alpha/mask-config.xml", "<v>stxMaskNeedle</v>")
        writeProjectFile("stx-mask-src/beta/MaskOther.java", "class MaskOther { String s = \"stxMaskNeedle\"; }")

        val result = SearchTextTool().execute(project, buildJsonObject {
            put("query", "stxMaskNeedle")
            put("filePattern", "*.java")
            putJsonArray("paths") { add("stx-mask-src/alpha/**") }
        })

        assertToolSucceeded("Search with paths + filePattern should succeed", result)
        val parsed = decode(toolText(result))
        assertEquals(
            "Both the mask (drops .xml) and the glob (drops beta) must apply",
            listOf("stx-mask-src/alpha/MaskHit.java"),
            parsed.matches.map { it.file }
        )
    }

    /**
     * A Windows-style glob must work end to end rather than returning a silent empty result —
     * the exact failure this feature exists to prevent, on the platform where a caller is
     * most likely to write separators that way.
     */
    fun testWindowsStyleSeparatorsAreNormalizedEndToEnd() = runBlocking {
        registerSourceRoot("stx-win-src")
        writeProjectFile("stx-win-src/alpha/WinHit.java", "class WinHit { String s = \"stxWinNeedle\"; }")
        writeProjectFile("stx-win-src/beta/WinMiss.java", "class WinMiss { String s = \"stxWinNeedle\"; }")

        val result = SearchTextTool().execute(project, buildJsonObject {
            put("query", "stxWinNeedle")
            putJsonArray("paths") { add("stx-win-src\\alpha\\**") }
        })

        assertToolSucceeded("Backslash separators should be normalized, not silently match nothing", result)
        val parsed = decode(toolText(result))
        assertEquals(
            "Only the alpha file may match, exactly as with '/' separators",
            listOf("stx-win-src/alpha/WinHit.java"),
            parsed.matches.map { it.file }
        )
    }

    fun testUnresolvableIncludeGlobFailsInsteadOfReturningNoMatches() = runBlocking {
        registerSourceRoot("stx-bad-src")
        writeProjectFile("stx-bad-src/real/RealHit.java", "class RealHit { String s = \"stxBadNeedle\"; }")

        val result = SearchTextTool().execute(project, buildJsonObject {
            put("query", "stxBadNeedle")
            putJsonArray("paths") { add("stx-bad-src/nosuchdir/**") }
        })

        assertToolFailed("A typo'd include glob must error, not report zero matches", result)
        val message = toolText(result)
        assertTrue(
            "Error must name the offending glob, got: $message",
            message.contains("stx-bad-src/nosuchdir/**")
        )
    }

    fun testOmittingPathsKeepsFullProjectSearch() = runBlocking {
        registerSourceRoot("stx-all-src")
        writeProjectFile("stx-all-src/one/AllHitOne.java", "class AllHitOne { String s = \"stxAllNeedle\"; }")
        writeProjectFile("stx-all-src/two/AllHitTwo.java", "class AllHitTwo { String s = \"stxAllNeedle\"; }")

        val result = SearchTextTool().execute(project, buildJsonObject {
            put("query", "stxAllNeedle")
        })

        assertToolSucceeded("Search without paths should succeed", result)
        val parsed = decode(toolText(result))
        assertEquals(
            "Without paths both directories must match",
            setOf("stx-all-src/one/AllHitOne.java", "stx-all-src/two/AllHitTwo.java"),
            parsed.matches.map { it.file }.toSet()
        )
    }
}
