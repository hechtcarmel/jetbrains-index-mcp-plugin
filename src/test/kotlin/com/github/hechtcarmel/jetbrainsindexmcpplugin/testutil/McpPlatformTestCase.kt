package com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil

import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.isFailure

import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.Path

/**
 * Base class for tests that drive MCP tools end to end against a real project layout.
 *
 * ## Why files go on disk, not into `myFixture.addFileToProject`
 *
 * `addFileToProject` writes into IntelliJ's in-memory `TempFileSystem` (`temp:///src/...`).
 * Every production entry point that turns a tool argument into a `VirtualFile` —
 * [com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool.resolveFile],
 * [com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PsiUtils], and the JS/TS symbol
 * resolver — goes through `LocalFileSystem`, which cannot see `temp://` files.
 *
 * A fixture built with `addFileToProject` therefore fails to resolve, the tool returns
 * "file not found", and any assertion looser than an exact-error check passes for the wrong
 * reason. That is precisely how 23 JS/TS tests sat green without ever executing.
 *
 * Writing to `project.basePath` matches how real projects are laid out, so tools exercise the
 * same resolution path a user hits.
 */
abstract class McpPlatformTestCase : BasePlatformTestCase() {

    /**
     * Paths present under the project directory before the test ran.
     *
     * The light fixture reuses one project directory for every test method in a class, so
     * without cleanup a file written by one test is still on disk for the next. That makes any
     * absence assertion (`assertProjectFileAbsent`, "only foo/index.ts exists") silently
     * order-dependent — passing or failing on method ordering rather than on behavior.
     *
     * A snapshot is used rather than a list of writes because refactorings under test create
     * files the test never wrote: a rename produces `NewName.java`, a move produces a file in a
     * new directory. Diffing against the snapshot cleans those up too.
     */
    private var preExistingPaths: Set<Path> = emptySet()

    override fun setUp() {
        super.setUp()
        preExistingPaths = snapshotProjectPaths()
    }

    override fun tearDown() {
        try {
            // Deepest first, so directories are empty by the time they are removed.
            (snapshotProjectPaths() - preExistingPaths)
                .sortedByDescending { it.nameCount }
                .forEach { runCatching { Files.deleteIfExists(it) } }
            project.basePath?.let {
                LocalFileSystem.getInstance().refreshAndFindFileByPath(it)?.refresh(false, true)
            }
        } finally {
            super.tearDown()
        }
    }

    private fun snapshotProjectPaths(): Set<Path> {
        val basePath = project.basePath ?: return emptySet()
        val root = Path.of(basePath)
        if (!Files.isDirectory(root)) return emptySet()
        return Files.walk(root).use { paths -> paths.filter { it != root }.toList() }.toSet()
    }

    /**
     * Writes [content] to [relativePath] under the project base path and makes it visible to
     * the VFS and indexes.
     */
    protected fun writeProjectFile(relativePath: String, content: String): Path {
        val basePath = requireNotNull(project.basePath) { "Project base path is null" }
        val path = Path.of(basePath, relativePath)
        Files.createDirectories(path.parent)
        Files.writeString(path, content)
        requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByPath(path.toString())) {
            "Failed to refresh VFS for test file $path"
        }
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        return path
    }

    /**
     * Reads a project file through the VFS/document layer rather than off disk, so in-memory
     * edits made by a refactoring are visible without an explicit save.
     */
    protected fun readProjectFileVfs(relativePath: String): String {
        val basePath = requireNotNull(project.basePath) { "Project base path is null" }
        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByPath("$basePath/$relativePath")
            ?: return Files.readString(Path.of(basePath, relativePath))
        val document = FileDocumentManager.getInstance().getDocument(virtualFile)
        return document?.text ?: String(virtualFile.contentsToByteArray())
    }

    protected fun projectFileExists(relativePath: String): Boolean {
        val basePath = requireNotNull(project.basePath) { "Project base path is null" }
        LocalFileSystem.getInstance().refreshAndFindFileByPath("$basePath/$relativePath")
        return Files.exists(Path.of(basePath, relativePath))
    }

    /**
     * Registers [relativePath] as a module source root.
     *
     * Needed whenever a test relies on index-backed search — `ReferencesSearch`, inheritor
     * search, JS/TS import resolution — because the default `project_files` scope only covers
     * files inside a content root.
     */
    protected fun registerSourceRoot(relativePath: String): VirtualFile {
        val basePath = requireNotNull(project.basePath) { "Project base path is null" }
        val path = Path.of(basePath, relativePath)
        Files.createDirectories(path)
        val root = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByPath(path.toString())) {
            "Failed to refresh VFS for source root $path"
        }
        PsiTestUtil.addSourceRoot(module, root)
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        return root
    }

    // ── Assertions ──────────────────────────────────────────────────────────────────────

    protected fun toolText(result: CallToolResult): String =
        (result.content.firstOrNull() as? TextContent)?.text.orEmpty()

    protected fun assertToolSucceeded(message: String, result: CallToolResult) {
        assertFalse("$message — tool returned error: ${toolText(result)}", result.isFailure)
    }

    protected fun assertToolFailed(message: String, result: CallToolResult) {
        assertTrue("$message — expected an error but tool succeeded: ${toolText(result)}", result.isFailure)
    }

    /**
     * Asserts a rename actually happened *everywhere* in a file.
     *
     * Checking only that the new name appears is the single most common way a refactoring test
     * lies: after renaming a declaration whose call sites were never updated, the new name is
     * present and the test passes. Both directions are required.
     */
    protected fun assertRenamedInFile(relativePath: String, oldName: String, newName: String) {
        val text = readProjectFileVfs(relativePath)
        assertTrue(
            "Expected '$newName' in $relativePath after rename, but it is absent.\n--- content ---\n$text",
            text.contains(newName)
        )
        assertFalse(
            "Found leftover '$oldName' in $relativePath after rename — references were not updated.\n" +
                "--- content ---\n$text",
            text.contains(oldName)
        )
    }

    protected fun assertFileContains(relativePath: String, expected: String) {
        val text = readProjectFileVfs(relativePath)
        assertTrue(
            "Expected $relativePath to contain '$expected'.\n--- content ---\n$text",
            text.contains(expected)
        )
    }

    protected fun assertFileDoesNotContain(relativePath: String, unexpected: String) {
        val text = readProjectFileVfs(relativePath)
        assertFalse(
            "Expected $relativePath NOT to contain '$unexpected'.\n--- content ---\n$text",
            text.contains(unexpected)
        )
    }

    protected fun assertProjectFileExists(relativePath: String) {
        assertTrue("Expected project file to exist: $relativePath", projectFileExists(relativePath))
    }

    protected fun assertProjectFileAbsent(relativePath: String) {
        assertFalse("Expected project file to be gone: $relativePath", projectFileExists(relativePath))
    }
}
