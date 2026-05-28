package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiReference
import io.mockk.every
import io.mockk.mockk
import junit.framework.TestCase

class RenameSymbolToolTargetResolutionUnitTest : TestCase() {

    private open class FakeElement(
        private val elementRange: TextRange,
        private val elementParent: PsiElement? = null,
        private var elementChildren: Array<PsiElement> = emptyArray(),
        private val elementText: String = "",
        private val elementReferences: Array<PsiReference> = emptyArray()
    ) : PsiElement by mockk(relaxed = true) {
        override fun getTextRange(): TextRange = elementRange
        override fun getParent(): PsiElement? = elementParent
        override fun getChildren(): Array<PsiElement> = elementChildren
        override fun getText(): String = elementText
        override fun getReferences(): Array<PsiReference> = elementReferences

        fun setChildren(children: Array<PsiElement>) {
            elementChildren = children
        }
    }

    private class CSharpDeclarationIdentifier(
        elementRange: TextRange,
        elementParent: PsiElement?,
        elementText: String
    ) : FakeElement(
        elementRange = elementRange,
        elementParent = elementParent,
        elementText = elementText
    )

    private class CSharpMethodDeclaration(
        elementRange: TextRange,
        elementParent: PsiElement?,
        elementChildren: Array<PsiElement>,
        elementText: String
    ) : FakeElement(
        elementRange = elementRange,
        elementParent = elementParent,
        elementChildren = elementChildren,
        elementText = elementText
    )

    private class FakeDirectoryNamedElement(
        private val elementName: String,
        private val elementRange: TextRange,
        private val elementParent: PsiElement?
    ) : PsiNamedElement by mockk(relaxed = true) {
        override fun getName(): String = elementName
        override fun getTextRange(): TextRange = elementRange
        override fun getParent(): PsiElement? = elementParent
        override fun getReferences(): Array<PsiReference> = emptyArray()
    }

    fun testResolveNamedElementCandidateSkipsDirectoryReferenceAndFallsBackToDeclarationAncestor() {
        val directoryTarget = mockk<PsiDirectory>(relaxed = true)
        val directoryReference = mockk<PsiReference>(relaxed = true)
        val declaration = mockk<PsiNamedElement>(relaxed = true)
        val leaf = mockk<PsiElement>(relaxed = true)

        every { directoryTarget.name } returns "Services"
        every { directoryTarget.textRange } returns TextRange(0, 20)
        every { directoryReference.resolve() } returns directoryTarget
        every { directoryReference.rangeInElement } returns TextRange(0, 1)
        every { declaration.name } returns "BuildMonthlySummary"
        every { declaration.references } returns emptyArray()
        every { declaration.parent } returns null
        every { declaration.textRange } returns TextRange(40, 80)
        every { leaf.references } returns arrayOf(directoryReference)
        every { leaf.parent } returns declaration
        every { leaf.textRange } returns TextRange(52, 53)

        val resolved = RenameSymbolTool.resolveNamedElementCandidateForRename(leaf)

        assertSame("Declaration ancestor should win when reference resolves to a container", declaration, resolved)
    }

    fun testResolveNamedElementCandidateStillUsesNonContainerReferenceTargets() {
        val resolvedTarget = mockk<PsiNamedElement>(relaxed = true)
        val reference = mockk<PsiReference>(relaxed = true)
        val leaf = mockk<PsiElement>(relaxed = true)

        every { resolvedTarget.name } returns "SimpleTypeModelDescription"
        every { resolvedTarget.textRange } returns TextRange(100, 140)
        every { reference.resolve() } returns resolvedTarget
        every { leaf.references } returns arrayOf(reference)
        every { leaf.parent } returns null
        every { leaf.textRange } returns TextRange(10, 15)
        every { reference.rangeInElement } returns TextRange(0, 5)

        val resolved = RenameSymbolTool.resolveNamedElementCandidateForRename(leaf)

        assertSame("Reference-based rename targets must still work for real declarations", resolvedTarget, resolved)
    }

    fun testInspectNamedElementCandidatePrefersEnclosingNamedAncestorOverReferenceResolution() {
        val resolvedTarget = mockk<PsiNamedElement>(relaxed = true)
        val declaration = mockk<PsiNamedElement>(relaxed = true)
        val reference = mockk<PsiReference>(relaxed = true)
        val leaf = mockk<PsiElement>(relaxed = true)

        every { resolvedTarget.name } returns "SimpleTypeModelDescription"
        every { resolvedTarget.textRange } returns TextRange(100, 140)
        every { declaration.name } returns "BuildMonthlySummary"
        every { declaration.references } returns emptyArray()
        every { declaration.parent } returns null
        every { declaration.textRange } returns TextRange(40, 90)
        every { reference.resolve() } returns resolvedTarget
        every { reference.rangeInElement } returns TextRange(0, 1)
        every { leaf.references } returns arrayOf(reference)
        every { leaf.parent } returns declaration
        every { leaf.textRange } returns TextRange(60, 61)

        val resolution = RenameSymbolTool.inspectNamedElementCandidateForRename(leaf, requestedOffset = 60)

        assertSame("Enclosing named ancestor should beat reference fallback when both are available", declaration, resolution.candidate)
        assertEquals("selected enclosing named element at depth=1", resolution.selectedReason)
    }

    fun testResolveNamedElementCandidateSkipsContainerLikeNamedAncestors() {
        val declaration = mockk<PsiNamedElement>(relaxed = true)
        val leaf = mockk<PsiElement>(relaxed = true)
        val containerAncestor = FakeDirectoryNamedElement(
            elementName = "Services",
            elementRange = TextRange(20, 100),
            elementParent = declaration
        )

        every { declaration.name } returns "BuildMonthlySummary"
        every { declaration.references } returns emptyArray()
        every { declaration.parent } returns null
        every { declaration.textRange } returns TextRange(10, 120)
        every { leaf.references } returns emptyArray()
        every { leaf.parent } returns containerAncestor
        every { leaf.textRange } returns TextRange(60, 61)

        val resolved = RenameSymbolTool.resolveNamedElementCandidateForRename(leaf)

        assertSame("Container-like named ancestors must be ignored during fallback resolution", declaration, resolved)
    }

    fun testResolveNamedElementCandidateFailsClosedWhenOnlyContainerLikeCandidatesExist() {
        val directoryTarget = FakeDirectoryNamedElement(
            elementName = "ModelDescriptions",
            elementRange = TextRange(0, 200),
            elementParent = null
        )
        val directoryReference = mockk<PsiReference>(relaxed = true)
        val leaf = mockk<PsiElement>(relaxed = true)

        every { directoryReference.resolve() } returns directoryTarget
        every { directoryReference.rangeInElement } returns TextRange(0, 1)
        every { leaf.references } returns arrayOf(directoryReference)
        every { leaf.parent } returns directoryTarget
        every { leaf.textRange } returns TextRange(50, 51)

        val resolution = RenameSymbolTool.inspectNamedElementCandidateForRename(leaf, requestedOffset = 50)

        assertNull("Fallback must fail closed when only container-like candidates exist", resolution.candidate)
        assertEquals("no supported non-container named element found", resolution.selectedReason)
    }

    fun testInspectNamedElementCandidatePromotesProcessableNonNamedDeclarationAncestor() {
        val declaration = CSharpMethodDeclaration(
            elementRange = TextRange(40, 90),
            elementParent = null,
            elementChildren = emptyArray(),
            elementText = "void BuildMonthlySummary() { }"
        )
        val identifier = CSharpDeclarationIdentifier(
            elementRange = TextRange(45, 64),
            elementParent = declaration,
            elementText = "BuildMonthlySummary"
        )
        declaration.setChildren(arrayOf(identifier))

        val resolution = RenameSymbolTool.inspectNamedElementCandidateForRename(
            element = identifier,
            requestedOffset = 50,
            processorSupportOverride = { candidate -> candidate === declaration }
        )

        assertSame("Nearest processable declaration-like ancestor should be promoted", declaration, resolution.candidate)
        assertEquals("BuildMonthlySummary", resolution.candidateName)
        assertEquals("selected processable declaration-like element at depth=1", resolution.selectedReason)
        assertTrue(resolution.parentChainDescription.contains("canProcessElement=true"))
        assertTrue(resolution.parentChainDescription.contains("identifierRange=45..64"))
    }

    fun testInspectNamedElementCandidateTracesProcessorUnsupportedNonNamedDeclarationAncestor() {
        val declaration = CSharpMethodDeclaration(
            elementRange = TextRange(40, 90),
            elementParent = null,
            elementChildren = emptyArray(),
            elementText = "void BuildMonthlySummary() { }"
        )
        val identifier = CSharpDeclarationIdentifier(
            elementRange = TextRange(45, 64),
            elementParent = declaration,
            elementText = "BuildMonthlySummary"
        )
        declaration.setChildren(arrayOf(identifier))

        val resolution = RenameSymbolTool.inspectNamedElementCandidateForRename(
            element = identifier,
            requestedOffset = 50,
            processorSupportOverride = { false }
        )

        assertNull("Fallback must fail closed when the non-named declaration is not frontend-processable", resolution.candidate)
        assertEquals("no supported non-container named element found", resolution.selectedReason)
        assertTrue(resolution.parentChainDescription.contains("rejected=processor-unsupported"))
    }
}
