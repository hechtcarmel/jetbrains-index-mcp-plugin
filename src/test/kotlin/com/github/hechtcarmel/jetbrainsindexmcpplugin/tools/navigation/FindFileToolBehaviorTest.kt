package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation

import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.FindFileResult
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PsiTestUtil
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * End-to-end behavior tests for `ide_find_file`, centred on result completeness.
 *
 * The file-name index streams candidate names for a scope-blind superset of the requested
 * scope (project content + libraries + SDK) in stable hash order. FindFileTool used to abort
 * that enumeration after `limit * 3` (= 1500) matching names, so whenever more names matched
 * globally than the window held, in-scope files were silently dropped while the response still
 * reported `hasMore: false`. These tests reproduce that mechanism inside one fixture project by
 * splitting matches across production and test source roots and searching a scope that only
 * covers the smaller side.
 */
class FindFileToolBehaviorTest : McpPlatformTestCase() {

    private companion object {
        const val PROD_ROOT = "src/main/resources"
        const val TEST_ROOT = "src/test/resources"

        /**
         * The pre-fix code stopped name enumeration after `limit * 3` = 1500 matching names
         * (limit = max(PaginationService.DEFAULT_OVERCOLLECT, pageSize) = 500). With 2400
         * fillers + 20 targets = 2420 matching names, the old 1500-name window kept ~62% of
         * them in index-hash order, so P(all 20 targets survive the window) ≈ 0.62^20 ≈ 7e-5:
         * the completeness test is effectively deterministic against the reverted code.
         */
        const val BIG_FILLER_COUNT = 2400
        const val TARGET_COUNT = 20

        /** Enough matches to exceed one 500-result page without the big fixture's cost. */
        const val SMALL_FILLER_COUNT = 550

        const val PAGE_SIZE = 500
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val tool = FindFileTool()

    fun testScopedSearchReturnsAllMatchesWhenMatchingNamesExceedOldEnumerationWindow() {
        val prodRoot = addSourceRoot(PROD_ROOT, isTestSource = false)
        val testRoot = addSourceRoot(TEST_ROOT, isTestSource = true)

        bulkWrite(PROD_ROOT, BIG_FILLER_COUNT) { "filler$it.properties" }
        val expectedPaths = bulkWrite(TEST_ROOT, TARGET_COUNT) { "target$it.properties" }.toSet()
        refreshAndIndex(prodRoot, testRoot)

        val result = runBlocking {
            tool.execute(project, buildJsonObject {
                put("query", "properties")
                put("scope", "project_test_files")
                put("pageSize", PAGE_SIZE)
            })
        }
        assertToolSucceeded("ide_find_file over project_test_files", result)
        val parsed = json.decodeFromString<FindFileResult>(toolText(result))

        assertEquals(
            "Every in-scope .properties file must be returned even though matching names " +
                "outnumber the old 1500-name enumeration window, and no out-of-scope file may " +
                "leak in",
            expectedPaths,
            parsed.files.map { it.path }.toSet()
        )
        assertFalse("All in-scope matches fit in one page, so hasMore must be false", parsed.hasMore)
        assertEquals("totalCollected must equal the number of in-scope matches", TARGET_COUNT, parsed.totalCollected)
    }

    fun testWildcardQueryPaginatesToExactlyTheFullMatchSet() {
        val prodRoot = addSourceRoot(PROD_ROOT, isTestSource = false)
        val testRoot = addSourceRoot(TEST_ROOT, isTestSource = true)

        val expectedPaths = (
            bulkWrite(PROD_ROOT, SMALL_FILLER_COUNT) { "filler$it.properties" } +
                bulkWrite(TEST_ROOT, TARGET_COUNT) { "target$it.properties" }
            ).toSet()
        refreshAndIndex(prodRoot, testRoot)

        val first = runBlocking {
            tool.execute(project, buildJsonObject {
                put("query", "*.properties")
                put("scope", "project_files")
                put("pageSize", PAGE_SIZE)
            })
        }
        assertToolSucceeded("ide_find_file wildcard search", first)
        val firstPage = json.decodeFromString<FindFileResult>(toolText(first))

        assertEquals("First page must be a full page", PAGE_SIZE, firstPage.files.size)
        assertTrue("More matches exist than one page holds, so hasMore must be true", firstPage.hasMore)
        assertNotNull("hasMore implies a nextCursor", firstPage.nextCursor)

        val collectedPaths = firstPage.files.map { it.path }.toMutableSet()
        var cursor = firstPage.nextCursor
        var pagesFollowed = 0
        while (cursor != null) {
            assertTrue("Pagination must terminate well before 10 pages for this fixture", pagesFollowed < 10)
            val token = cursor
            val next = runBlocking {
                tool.execute(project, buildJsonObject {
                    put("cursor", token)
                })
            }
            assertToolSucceeded("ide_find_file page $pagesFollowed", next)
            val page = json.decodeFromString<FindFileResult>(toolText(next))
            collectedPaths += page.files.map { it.path }
            cursor = page.nextCursor
            pagesFollowed++
        }

        assertEquals(
            "Walking pagination to the end must yield exactly the full in-scope match set — " +
                "nothing missing, nothing duplicated from outside the scope",
            expectedPaths,
            collectedPaths
        )
    }

    fun testCamelCaseQueryMatchesFileName() {
        val root = addSourceRoot(PROD_ROOT, isTestSource = false)
        writeProjectFile("$PROD_ROOT/build.gradle", "// build file\n")
        writeProjectFile("$PROD_ROOT/settings.txt", "unrelated\n")
        refreshAndIndex(root)

        val result = runBlocking {
            tool.execute(project, buildJsonObject {
                put("query", "BG")
                put("scope", "project_files")
            })
        }
        assertToolSucceeded("ide_find_file camelCase search", result)
        val parsed = json.decodeFromString<FindFileResult>(toolText(result))

        assertTrue(
            "camelCase query 'BG' must match build.gradle; got: ${parsed.files.map { it.path }}",
            parsed.files.any { it.path == "$PROD_ROOT/build.gradle" }
        )
        assertFalse(
            "'BG' must not match settings.txt",
            parsed.files.any { it.name == "settings.txt" }
        )
    }

    /**
     * `McpPlatformTestCase.registerSourceRoot` only registers production roots, and the
     * completeness test needs a test source root so `project_test_files` selects a strict
     * subset of the indexed names. Idempotent because the light fixture's module is shared
     * across the methods of this class.
     */
    private fun addSourceRoot(relativePath: String, isTestSource: Boolean): VirtualFile {
        val basePath = requireNotNull(project.basePath) { "Project base path is null" }
        val path = Path.of(basePath, relativePath)
        Files.createDirectories(path)
        val root = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByPath(path.toString())) {
            "Failed to refresh VFS for source root $path"
        }
        if (ModuleRootManager.getInstance(module).sourceRoots.none { it.path == root.path }) {
            PsiTestUtil.addSourceRoot(module, root, isTestSource)
        }
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        return root
    }

    /**
     * Writes [count] files straight to disk and returns their project-relative paths.
     * `writeProjectFile`'s per-file VFS refresh + index wait is far too slow for thousands of
     * files, so callers batch all writes and follow up with one [refreshAndIndex].
     */
    private fun bulkWrite(dirRelativePath: String, count: Int, nameOf: (Int) -> String): List<String> {
        val basePath = requireNotNull(project.basePath) { "Project base path is null" }
        val dir = Path.of(basePath, dirRelativePath)
        Files.createDirectories(dir)
        return (0 until count).map { i ->
            val name = nameOf(i)
            Files.writeString(dir.resolve(name), "value=$i\n")
            "$dirRelativePath/$name"
        }
    }

    private fun refreshAndIndex(vararg roots: VirtualFile) {
        VfsUtil.markDirtyAndRefresh(false, true, true, *roots)
        IndexingTestUtil.waitUntilIndexesAreReady(project)
    }
}
