package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import io.mockk.every
import io.mockk.mockk
import junit.framework.TestCase

/**
 * Headless coverage for [KotlinMemberResolver.getInsertionOffset] on file-level scopes.
 *
 * The Kotlin plugin is not on the test classpath, so a real KtFile cannot be built here.
 * The resolver reads the file header reflectively (`getPackageDirective`/`getImportList`),
 * which lets a mocked [PsiFile] exposing those accessors drive the exact production code path:
 * with a KtFile scope the KtClassOrObject instance-check fails and execution falls through to
 * the same `scope is PsiFile` branch exercised here.
 */
class KotlinMemberResolverInsertionOffsetUnitTest : TestCase() {

    /** Mirrors the two KtFile header accessors the resolver invokes reflectively. */
    private interface FileWithHeader : PsiFile {
        fun getPackageDirective(): PsiElement?
        fun getImportList(): PsiElement?
    }

    private fun element(start: Int, end: Int): PsiElement {
        val element = mockk<PsiElement>()
        every { element.textRange } returns TextRange(start, end)
        return element
    }

    private fun fileScope(
        packageDirective: PsiElement?,
        importList: PsiElement?,
        length: Int = 200
    ): PsiFile {
        val file = mockk<FileWithHeader>()
        every { file.getPackageDirective() } returns packageDirective
        every { file.getImportList() } returns importList
        every { file.textRange } returns TextRange(0, length)
        return file
    }

    private val resolver = KotlinMemberResolver(mockk<Project>(relaxed = true))

    fun testFirstOnFileScopeInsertsAfterImports() {
        val scope = fileScope(
            packageDirective = element(0, 19), // package com.example
            importList = element(20, 55)       // import statements
        )
        assertEquals(
            "position='first' at file scope must insert after the package/imports header, " +
                "not at offset 0 before the package declaration",
            55,
            resolver.getInsertionOffset(scope, "first", null)
        )
    }

    fun testFirstOnFileScopeWithPackageOnlyInsertsAfterPackageDirective() {
        val scope = fileScope(
            packageDirective = element(0, 19),
            importList = element(0, 0) // empty KtImportList when the file has no imports
        )
        assertEquals(19, resolver.getInsertionOffset(scope, "first", null))
    }

    fun testFirstOnHeaderlessFileScopeInsertsAtStart() {
        // Script-like files with neither package nor imports keep the offset-0 behavior.
        val scope = fileScope(packageDirective = null, importList = null)
        assertEquals(0, resolver.getInsertionOffset(scope, "first", null))
    }

    fun testLastOnFileScopeStaysAtEndOfFile() {
        val scope = fileScope(packageDirective = element(0, 19), importList = element(20, 55), length = 321)
        assertEquals(321, resolver.getInsertionOffset(scope, "last", null))
    }
}
