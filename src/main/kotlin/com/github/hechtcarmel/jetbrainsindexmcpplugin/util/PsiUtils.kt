package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiReference
import com.intellij.psi.util.PsiTreeUtil

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
            if (resolved != null) return resolved
        }

        // Walk up parent chain looking for references (handles cases where
        // the leaf element doesn't have a reference but its parent does)
        val parentReference = findReferenceInParent(element)
        if (parentReference != null) {
            val resolved = parentReference.resolve()
            if (resolved != null) return resolved
        }

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
        val fullPath = if (relativePath.startsWith("/")) relativePath else "$basePath/$relativePath"
        // Use refreshAndFindFileByPath to handle externally created files
        return LocalFileSystem.getInstance().refreshAndFindFileByPath(fullPath)
    }

    fun resolveVirtualFileAnywhere(project: Project, path: String): VirtualFile? {
        val virtualFileManager = VirtualFileManager.getInstance()
        
        // Handle already-formatted jar:// URLs
        if (path.startsWith("jar://")) {
            return virtualFileManager.findFileByUrl(path)
        }

        // Handle jar path format: /path/to/file.jar!/internal/path
        if (path.contains("jar!/")) {
            val parts = path.split("!/", limit = 2)
            if (parts.size == 2) {
                val jarPath = parts[0]
                val internalPath = parts[1]
                
                // Normalize the jar file path to absolute path
                val absoluteJarPath = when {
                    jarPath.startsWith("/") -> jarPath
                    jarPath.startsWith("~") -> {
                        val homeDir = System.getProperty("user.home")
                        jarPath.replaceFirst("~", homeDir)
                    }
                    else -> {
                        // Try as relative to project first
                        val basePath = project.basePath
                        if (basePath != null) {
                            "$basePath/$jarPath"
                        } else {
                            // Try as absolute path without leading /
                            "/$jarPath"
                        }
                    }
                }
                
                // Construct the jar URL: jar://absolute/path/to/file.jar!/internal/path
                val jarUrl = "jar://$absoluteJarPath!/$internalPath"
                val findFileByUrl = virtualFileManager.findFileByUrl(jarUrl)
                return findFileByUrl
            }
        }

        return getVirtualFile(project, path)
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
        val lines = text.split("\n")
        val clampedStart = (startLine - 1).coerceAtLeast(0)
        val clampedEnd = (endLine - 1).coerceAtMost(lines.size - 1)
        if (clampedStart > clampedEnd) return ""
        return lines.subList(clampedStart, clampedEnd + 1).joinToString("\n")
    }

    fun getContainingClass(element: PsiElement): PsiClass? {
        return PsiTreeUtil.getParentOfType(element, PsiClass::class.java)
    }

    fun getContainingMethod(element: PsiElement): PsiMethod? {
        return PsiTreeUtil.getParentOfType(element, PsiMethod::class.java)
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

    fun extractDocumentation(element: PsiElement): String? {
        // Try to find doc comment
        val docComment = when (element) {
            is PsiMethod -> element.docComment
            is PsiClass -> element.docComment
            else -> null
        }

        return docComment?.text?.let { text ->
            // Clean up doc comment
            text.lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && it != "/**" && it != "*/" }
                .joinToString("\n") { line ->
                    line.removePrefix("*").trim()
                }
        }
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
