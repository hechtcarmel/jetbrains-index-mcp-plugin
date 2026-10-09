package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools

import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.FindClassResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.FindFileResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.FindUsagesResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.SearchTextResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.FindClassTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.FindFileTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.FindUsagesTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.SearchTextTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring.ReplaceTextInFileTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ProjectUtils
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PsiTestUtil
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.nio.file.Files
import java.nio.file.Path

/**
 * Paths reported for files in module content roots outside the project directory (issue #441).
 *
 * The fixture is the layout `ide_open_workspace` produces: its aggregator project lives in the
 * IDE system directory, so every repository is a content root outside `project.basePath`. The
 * two repositories here share their internal layout, as Maven modules do. Tools used to report
 * such files relative to their content root, so both repositories' files came back as the same
 * `src/...` path: one the agent's own file tools could not open, and one that collapsed distinct
 * results wherever a tool deduplicates by path. They are now reported by absolute path, while
 * files under the project directory keep their project-relative path.
 */
class WorkspaceRootPathsBehaviorTest : McpPlatformTestCase() {

    private val json = Json { ignoreUnknownKeys = true }

    /** Created outside the project directory; real path, so it equals what the VFS reports. */
    private var workspace: Path? = null
    private val outsideRoots = mutableListOf<VirtualFile>()

    private val svcA: VirtualFile get() = outsideRoots[0]
    private val svcB: VirtualFile get() = outsideRoots[1]

    override fun setUp() {
        super.setUp()
        val dir = Files.createTempDirectory("workspace-roots-441").toRealPath()
        workspace = dir
        val fs = LocalFileSystem.getInstance()
        for (name in listOf("svc-a", "svc-b")) {
            Files.createDirectories(dir.resolve("$name/src"))
            val root = requireNotNull(fs.refreshAndFindFileByNioFile(dir.resolve(name))) { "Failed to refresh $name" }
            val sourceRoot = requireNotNull(fs.refreshAndFindFileByNioFile(dir.resolve("$name/src"))) {
                "Failed to refresh $name/src"
            }
            PsiTestUtil.addContentRoot(module, root)
            outsideRoots += root
            PsiTestUtil.addSourceRoot(module, sourceRoot)
        }
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val basePath = requireNotNull(project.basePath) { "Light fixture must have a base path" }
        assertFalse(
            "Precondition: the repositories must be outside the project directory",
            outsideRoots.any { it.path.startsWith("$basePath/") }
        )
    }

    override fun tearDown() {
        try {
            val urls = outsideRoots.map { it.url }.toSet()
            ModuleRootModificationUtil.updateModel(module) { model ->
                model.contentEntries.filter { it.url in urls }.forEach(model::removeContentEntry)
            }
            workspace?.toFile()?.deleteRecursively()
        } finally {
            outsideRoots.clear()
            super.tearDown()
        }
    }

    private fun writeInRoot(root: VirtualFile, relativePath: String, content: String): VirtualFile {
        val path = Path.of(root.path, relativePath)
        Files.createDirectories(path.parent)
        Files.writeString(path, content)
        val file = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)) {
            "Failed to refresh VFS for $path"
        }
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        return file
    }

    /** Reads through the document layer, so an edit a tool has not saved yet is still visible. */
    private fun readInRoot(path: String): String {
        val file = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByPath(path)) { "Missing $path" }
        return FileDocumentManager.getInstance().getDocument(file)?.text ?: String(file.contentsToByteArray())
    }

    fun testFindFileReportsSameLayoutFilesInDifferentRootsByDistinctAbsolutePaths() = runBlocking {
        val inA = writeInRoot(svcA, "src/com/example/WsHelper441.java", "class WsHelper441 {}\n")
        val inB = writeInRoot(svcB, "src/com/example/WsHelper441.java", "class WsHelper441 {}\n")
        registerSourceRoot("ws441-local")
        writeProjectFile("ws441-local/WsHelper441.java", "class WsHelper441 {}\n")

        val result = FindFileTool().execute(project, buildJsonObject { put("query", "WsHelper441.java") })

        assertToolSucceeded("ide_find_file", result)
        val files = json.decodeFromString<FindFileResult>(toolText(result)).files
        assertEquals(
            "each repository's file must be named by its absolute path, the project's own file relatively",
            setOf(inA.path, inB.path, "ws441-local/WsHelper441.java"),
            files.map { it.path }.toSet()
        )
        assertEquals(
            "the directory must name the repository too",
            "${svcA.path}/src/com/example",
            files.single { it.path == inA.path }.directory
        )
    }

    /** Same class, same position, same relative path: one result per repository, not one in total. */
    fun testFindClassKeepsSameLayoutClassesFromDifferentRoots() = runBlocking {
        val source = "package com.example;\n\npublic class WsDup441 {}\n"
        val inA = writeInRoot(svcA, "src/com/example/WsDup441.java", source)
        val inB = writeInRoot(svcB, "src/com/example/WsDup441.java", source)

        val result = FindClassTool().execute(project, buildJsonObject {
            put("query", "WsDup441")
            put("matchMode", "exact")
        })

        assertToolSucceeded("ide_find_class", result)
        val classes = json.decodeFromString<FindClassResult>(toolText(result)).classes
        assertEquals(
            "both repositories' classes must be reported, each by its own path",
            listOf(inA.path, inB.path).sorted(),
            classes.filter { it.name == "WsDup441" }.map { it.file }.sorted()
        )
    }

    /**
     * Two references at the same line and column of same-layout files. Deduplication keys on
     * the reported path, so a shared content-root-relative path dropped one of them.
     */
    fun testFindReferencesKeepsSamePositionReferencesFromDifferentRoots() = runBlocking {
        val target = writeInRoot(
            svcA,
            "src/com/example/usage/WsTarget441.java",
            "package com.example.usage;\n\npublic class WsTarget441 {\n}\n"
        )
        val useA = writeInRoot(
            svcA,
            "src/com/example/usage/WsUse441.java",
            "package com.example.usage;\n\nclass WsUseA441 { WsTarget441 t; }\n"
        )
        val useB = writeInRoot(
            svcB,
            "src/com/example/usage/WsUse441.java",
            "package com.example.usage;\n\nclass WsUseB441 { WsTarget441 t; }\n"
        )

        val result = FindUsagesTool().execute(project, buildJsonObject {
            put("file", target.path)
            put("line", 3)
            put("column", 14)
        })

        assertToolSucceeded("ide_find_references on WsTarget441", result)
        val usages = json.decodeFromString<FindUsagesResult>(toolText(result)).usages
        assertEquals(
            "one reference per repository, each at line 3 column 19 of its own file",
            listOf(useA.path to 3, useB.path to 3).sortedBy { it.first },
            usages.map { it.file to it.line }.sortedBy { it.first }
        )
        assertEquals(setOf(19), usages.map { it.column }.toSet())
    }

    /** The path a search reports must be one an edit tool resolves to exactly that file. */
    fun testReportedPathRoundTripsIntoAnEditOfThatFileOnly() = runBlocking {
        val content = "class WsConfig441 { String v = \"wsRoundTrip441\"; }\n"
        val inA = writeInRoot(svcA, "src/com/example/WsConfig441.java", content)
        val inB = writeInRoot(svcB, "src/com/example/WsConfig441.java", content)

        val search = SearchTextTool().execute(project, buildJsonObject { put("query", "wsRoundTrip441") })
        assertToolSucceeded("ide_search_text", search)
        val reported = json.decodeFromString<SearchTextResult>(toolText(search)).matches.map { it.file }
        assertEquals(
            "both repositories' matches must be reported, by distinct paths",
            setOf(inA.path, inB.path),
            reported.toSet()
        )

        val edit = ReplaceTextInFileTool().execute(project, buildJsonObject {
            put("file", reported.single { it.startsWith("${svcB.path}/") })
            put("searchText", "wsRoundTrip441")
            put("replaceText", "wsEdited441")
        })

        assertToolSucceeded("the reported path must resolve for an edit", edit)
        assertTrue("svc-b must be edited", readInRoot(inB.path).contains("wsEdited441"))
        val untouched = readInRoot(inA.path)
        assertTrue("svc-a must be untouched, was: $untouched", untouched.contains("wsRoundTrip441"))
        assertFalse("svc-a must be untouched, was: $untouched", untouched.contains("wsEdited441"))
    }

    fun testAbsoluteGlobRestrictsSearchToOneRoot() = runBlocking {
        val content = "class WsGlob441 { String v = \"wsAbsoluteGlob441\"; }\n"
        val inA = writeInRoot(svcA, "src/com/example/WsGlob441.java", content)
        writeInRoot(svcB, "src/com/example/WsGlob441.java", content)

        val result = SearchTextTool().execute(project, buildJsonObject {
            put("query", "wsAbsoluteGlob441")
            putJsonArray("paths") { add("${svcA.path}/src") }
        })

        assertToolSucceeded("an absolute glob naming a workspace root must validate", result)
        assertEquals(
            listOf(inA.path),
            json.decodeFromString<SearchTextResult>(toolText(result)).matches.map { it.file }
        )
    }

    /**
     * A leading double star matches whole non-empty segments, so it only reaches files outside
     * the project directory because their absolute path is matched without its leading '/'.
     */
    fun testLeadingDoubleStarExcludeAppliesOutsideProjectDirectory() = runBlocking {
        val kept = writeInRoot(svcA, "src/com/example/WsKept441.java", "class WsKept441 { String v = \"wsExclude441\"; }\n")
        writeInRoot(svcA, "src/generated/WsGen441.java", "class WsGen441 { String v = \"wsExclude441\"; }\n")

        val result = SearchTextTool().execute(project, buildJsonObject {
            put("query", "wsExclude441")
            putJsonArray("paths") { add("!**/generated/**") }
        })

        assertToolSucceeded("an exclude-only filter must succeed", result)
        assertEquals(
            "the generated file must be excluded, the other kept",
            listOf(kept.path),
            json.decodeFromString<SearchTextResult>(toolText(result)).matches.map { it.file }
        )
    }

    /** Files outside the project directory are matched by absolute path, so the error must say so. */
    fun testRelativeGlobForAWorkspaceRootDirectoryNamesItsAbsoluteSpelling() = runBlocking {
        writeInRoot(svcA, "src/wsonlya441/WsOnly441.java", "class WsOnly441 {}\n")

        val result = SearchTextTool().execute(project, buildJsonObject {
            put("query", "WsOnly441")
            putJsonArray("paths") { add("src/wsonlya441/**") }
        })

        assertToolFailed("a glob that would match nothing must fail", result)
        val message = toolText(result)
        assertTrue(
            "the error must name the spelling that works, was: $message",
            message.contains("resolves to '${svcA.path}/src/wsonlya441'")
        )
    }

    /** A leading '/' on a project-relative glob keeps working: the absolute reading is tried first. */
    fun testRootedProjectRelativeGlobStillMatchesUnderProjectDirectory() = runBlocking {
        registerSourceRoot("ws441-rooted")
        writeProjectFile("ws441-rooted/alpha/WsRootedHit.java", "class WsRootedHit { String v = \"wsRooted441\"; }")
        writeProjectFile("ws441-rooted/beta/WsRootedMiss.java", "class WsRootedMiss { String v = \"wsRooted441\"; }")

        val result = SearchTextTool().execute(project, buildJsonObject {
            put("query", "wsRooted441")
            putJsonArray("paths") { add("/ws441-rooted/alpha") }
        })

        assertToolSucceeded("a '/'-prefixed project-relative glob must still validate", result)
        assertEquals(
            listOf("ws441-rooted/alpha/WsRootedHit.java"),
            json.decodeFromString<SearchTextResult>(toolText(result)).matches.map { it.file }
        )
    }

    /** Build and test output have no VirtualFile; their paths follow the same rule. */
    fun testPathsWithoutAVirtualFileFollowTheSameRule() {
        val basePath = requireNotNull(project.basePath)
        assertEquals("module/src/A.java", ProjectUtils.getRelativePath(project, "$basePath/module/src/A.java"))
        assertEquals("", ProjectUtils.getRelativePath(project, basePath))
        val outside = "${svcA.path}/src/com/example/A.java"
        assertEquals(outside, ProjectUtils.getRelativePath(project, outside))
    }
}
