package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.OrderEnumerator
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.toNioPathOrNull
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiReference
import java.nio.file.InvalidPathException
import java.nio.file.Path

object PsiUtils {

    /**
     * Default depth for searching parent chain for references.
     * 3 levels covers common cases: identifier -> expression -> call expression.
     */
    private const val DEFAULT_PARENT_SEARCH_DEPTH = 3

    /**
     * Resolves the target element from a position, using semantic reference resolution.
     *
     * This is the correct way to find what a position "refers to":
     * 1. First tries `element.reference.resolve()` to follow references semantically
     * 2. If no direct reference, walks up parent chain looking for references
     * 3. Falls back to [findNamedElement] for declarations (when cursor is ON a declaration)
     *
     * **Why this matters:**
     * When the cursor is on a method call like `myService.doWork()`, the leaf element
     * is the identifier "doWork". Using [findNamedElement] would walk up the tree and
     * find the *containing* method, not the *referenced* method. This function correctly
     * resolves through the reference system to find the actual `doWork` method declaration.
     *
     * @param element The leaf PSI element at a position (from `psiFile.findElementAt(offset)`)
     * @return The resolved target element (declaration), or null if resolution fails
     */
    fun resolveTargetElement(element: PsiElement): PsiElement? {
        // Try direct reference first
        val reference = element.reference
        if (reference != null) {
            val resolved = reference.resolve()
            val refined = PythonDefinitionResolver.refineResolvedTarget(element, resolved)
            if (refined != null) return refined
        }

        // Walk up parent chain looking for references (handles cases where
        // the leaf element doesn't have a reference but its parent does)
        val parentReference = findReferenceInParent(element)
        if (parentReference != null) {
            val resolved = parentReference.resolve()
            val refined = PythonDefinitionResolver.refineResolvedTarget(element, resolved)
            if (refined != null) return refined
        }

        PythonDefinitionResolver.refineResolvedTarget(element, null)?.let { return it }

        // Fallback: if we're ON a declaration (not a reference), find it syntactically
        return findNamedElement(element)
    }

    /**
     * Searches up the parent chain for a reference.
     *
     * Some PSI structures place the reference on a parent element rather than
     * the leaf identifier. This walks up a few levels to find it.
     *
     * @param element Starting element
     * @param maxDepth Maximum parent levels to check (default: [DEFAULT_PARENT_SEARCH_DEPTH])
     * @return The first reference found, or null
     * @see resolveTargetElement
     */
    fun findReferenceInParent(element: PsiElement, maxDepth: Int = DEFAULT_PARENT_SEARCH_DEPTH): PsiReference? {
        var current: PsiElement? = element
        repeat(maxDepth) {
            current = current?.parent ?: return null
            current?.reference?.let { return it }
        }
        return null
    }

    fun findElementAtPosition(
        project: Project,
        file: String,
        line: Int,
        column: Int
    ): PsiElement? {
        val psiFile = getPsiFile(project, file) ?: return null
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return null

        val lineIndex = line - 1
        if (lineIndex < 0 || lineIndex >= document.lineCount) return null

        val lineStartOffset = document.getLineStartOffset(lineIndex)
        val lineEndOffset = document.getLineEndOffset(lineIndex)
        val offset = lineStartOffset + (column - 1)

        return if (offset <= lineEndOffset) {
            psiFile.findElementAt(offset)
        } else {
            null
        }
    }

    fun getPsiFile(project: Project, relativePath: String): PsiFile? {
        val virtualFile = getVirtualFile(project, relativePath) ?: return null
        return PsiManager.getInstance(project).findFile(virtualFile)
    }

    fun getVirtualFile(project: Project, relativePath: String): VirtualFile? {
        val basePath = project.basePath ?: return null
        return resolveLocalFile(relativePath, sequenceOf(basePath))
    }

    fun resolveVirtualFileAnywhere(project: Project, path: String): VirtualFile? {
        // On Windows, \ is a path separator (and is forbidden in filenames), so normalizing is
        // always safe and necessary. On POSIX, \ is a valid filename character and must not be
        // treated as a separator.
        val normalizedPath = if (SystemInfo.isWindows) path.replace('\\', '/') else path
        val virtualFileManager = VirtualFileManager.getInstance()

        // Handle already-formatted jar:// URLs
        if (normalizedPath.startsWith("jar://")) {
            val jarPath = normalizedPath.removePrefix("jar://").substringBefore("!/")
            val projectLibraryJars = project.getProjectLibraryJars()
            if (projectLibraryJars.none { isPathPrefixOf(it, jarPath) }) return null
            return virtualFileManager.findFileByUrl(normalizedPath)
        }

        // Handle jar path format: /path/to/file.jar!/internal/path
        if (normalizedPath.contains(".jar!/")) {
            val parts = normalizedPath.split("!/", limit = 2)
            if (parts.size == 2) {
                val jarPath = parts[0]
                val internalPath = parts[1]

                val homeExpandedJarPath = expandHome(jarPath)
                val absoluteJarPath = resolveAbsolutePathString(homeExpandedJarPath, listOfNotNull(project.basePath).asSequence())
                    ?: homeExpandedJarPath

                val projectLibraryJars = project.getProjectLibraryJars()

                if (projectLibraryJars.none { isPathPrefixOf(it, absoluteJarPath) }) {
                    return null
                }
                
                // Construct the jar URL: jar://absolute/path/to/file.jar!/internal/path
                val jarUrl = "jar://$absoluteJarPath!/$internalPath"
                val findFileByUrl = virtualFileManager.findFileByUrl(jarUrl)
                return findFileByUrl
            }
        }

        return getVirtualFile(project, normalizedPath)
    }

    fun resolveNavigableVirtualFile(project: Project, path: String): VirtualFile? {
        // Match resolveVirtualFileAnywhere() semantics for path normalization.
        val normalizedPath = if (SystemInfo.isWindows) path.replace('\\', '/') else path
        val virtualFileManager = VirtualFileManager.getInstance()
        val rootCandidates = sequence {
            project.basePath?.let { yield(it) }
            for (root in ProjectUtils.getModuleContentRoots(project)) {
                yield(root)
            }
        }

        val resolved = when {
            normalizedPath.startsWith("jar://") -> virtualFileManager.findFileByUrl(normalizedPath)
            normalizedPath.contains(".jar!/") -> {
                val parts = normalizedPath.split("!/", limit = 2)
                if (parts.size != 2) {
                    null
                } else {
                    val jarPath = parts[0]
                    val internalPath = parts[1]
                    val homeExpandedJarPath = expandHome(jarPath)
                    val absoluteJarPath = resolveAbsolutePathString(homeExpandedJarPath, rootCandidates)
                        ?: homeExpandedJarPath
                    virtualFileManager.findFileByUrl("jar://$absoluteJarPath!/$internalPath")
                }
            }
            else -> resolveLocalFile(normalizedPath, rootCandidates)
        } ?: return null

        return resolved.takeIf { ProjectUtils.isAccessibleFile(project, it) }
    }

    fun resolveLocalFile(path: String, rootCandidates: Sequence<String>): VirtualFile? {
        val expandedPath = expandHome(path)
        // resolveAbsolutePath handles both absolute paths and relative paths against each root candidate.
        val absolutePath = resolveAbsolutePath(expandedPath, rootCandidates) ?: return null
        return LocalFileSystem.getInstance().findFileByNioFile(absolutePath)
    }

    fun resolveAbsolutePath(path: String, rootCandidates: Sequence<String> = emptySequence()): Path? {
        val candidate = toPathOrNull(path) ?: return null
        if (candidate.isAbsolute) return candidate.normalize()

        for (root in rootCandidates) {
            val resolved = resolveAgainstRoot(path, root)
            if (resolved != null) return resolved
        }
        return null
    }

    fun resolveAbsolutePathString(path: String, rootCandidates: Sequence<String> = emptySequence()): String? =
        resolveAbsolutePath(path, rootCandidates)?.toString()?.replace('\\', '/')

    private fun resolveAgainstRoot(path: String, root: String?): Path? {
        val rootPath = toPathOrNull(root) ?: return null
        val relativePath = toPathOrNull(path) ?: return null
        return rootPath.resolve(relativePath).normalize()
    }

    private fun toPathOrNull(path: String?): Path? {
        if (path.isNullOrBlank()) return null
        return try {
            Path.of(path)
        } catch (_: InvalidPathException) {
            null
        }
    }

    private fun expandHome(path: String): String {
        if (!path.startsWith("~")) return path
        val homeDir = System.getProperty("user.home") ?: return path
        return path.replaceFirst("~", homeDir)
    }

    fun getFileContent(project: Project, virtualFile: VirtualFile): String? {
        val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
        val document = psiFile?.let { PsiDocumentManager.getInstance(project).getDocument(it) }
        return document?.text ?: runCatching { VfsUtil.loadText(virtualFile) }.getOrNull()
    }

    fun getFileContentByLines(
        project: Project,
        virtualFile: VirtualFile,
        startLine: Int,
        endLine: Int
    ): String? {
        val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
        val document = psiFile?.let { PsiDocumentManager.getInstance(project).getDocument(it) }

        if (document != null) {
            val clampedStart = (startLine - 1).coerceAtLeast(0)
            val clampedEnd = (endLine - 1).coerceAtMost(document.lineCount - 1)
            if (clampedStart > clampedEnd) return ""
            val startOffset = document.getLineStartOffset(clampedStart)
            val endOffset = document.getLineEndOffset(clampedEnd)
            return document.getText(com.intellij.openapi.util.TextRange(startOffset, endOffset))
        }

        val text = runCatching { VfsUtil.loadText(virtualFile) }.getOrNull() ?: return null
        val lines = text.lines()
        val clampedStart = (startLine - 1).coerceAtLeast(0)
        val clampedEnd = (endLine - 1).coerceAtMost(lines.size - 1)
        if (clampedStart > clampedEnd) return ""
        return lines.subList(clampedStart, clampedEnd + 1).joinToString("\n")
    }

    /**
     * Returns the chain of named PSI ancestors enclosing [element], ordered from the
     * outermost (closest to file root) to the innermost (immediate named parent).
     * The [element] itself is never included, nor are anonymous (unnamed) nodes.
     */
    fun getAstPath(element: PsiElement): List<String> {
        val ancestors = mutableListOf<String>()
        var current: PsiElement? = element.parent
        while (current != null && current !is PsiFile) {
            if (current is PsiNamedElement) {
                val name = current.name
                if (!name.isNullOrEmpty()) {
                    ancestors.add(name)
                }
            }
            current = current.parent
        }
        return ancestors.asReversed()
    }

    fun findNamedElement(element: PsiElement): PsiNamedElement? {
        var current: PsiElement? = element
        while (current != null) {
            // Exclude PsiFile - it's too high-level to be a useful "named element" target
            // and would cause accidental file deletion when targeting whitespace/comments
            if (current is PsiNamedElement && current !is PsiFile && current.name != null) {
                return current
            }
            current = current.parent
        }
        return null
    }

    /**
     * Gets the navigation element for a PSI element, preferring source files over compiled files.
     *
     * This is crucial for library classes where we want to navigate to `.java` source files
     * instead of `.class` bytecode files when sources are available.
     *
     * **Why this matters:**
     * When you have a library with attached sources (e.g., via Maven or Gradle), IntelliJ
     * stores both the compiled `.class` files and the source `.java` files. By default,
     * PSI elements may point to the `.class` file, but `navigationElement` provides the
     * source file if available, which is much more useful for reading code.
     *
     * @param element The PSI element to get the navigation target for
     * @return The navigation element (preferably source), or the original element if no navigation target exists
     */
    fun getNavigationElement(element: PsiElement): PsiElement {
        return element.navigationElement ?: element
    }
}

/**
 * Checks whether [prefix] is a path prefix of [child], using NIO Path semantics.
 * On Windows, NIO Path comparison is case-insensitive, matching the OS file system behavior.
 */
private fun isPathPrefixOf(prefix: String, child: String): Boolean {
    return try {
        Path.of(child).startsWith(Path.of(prefix))
    } catch (_: InvalidPathException) {
        child.startsWith(prefix)
    }
}

private fun Project.getProjectLibraryJars(): List<String> = OrderEnumerator.orderEntries(this)
    .librariesOnly()
    .classes()
    .roots
    .mapNotNull { root ->
        root.toNioPathOrNull()?.toString()?.replace('\\', '/')
            ?: root.path.takeIf { it.contains("!/") }
                ?.substringBefore("!/")
                ?.replace('\\', '/')
                ?.let { p ->
                    // On Windows, JarFileSystem paths start with /C:/ — normalize to C:/
                    if (p.length >= 3 && p[0] == '/' && p[2] == ':') p.substring(1) else p
                }
    }
