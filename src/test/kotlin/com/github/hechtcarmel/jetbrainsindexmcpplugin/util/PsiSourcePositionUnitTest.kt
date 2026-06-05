package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

import com.intellij.psi.PsiElement
import io.mockk.every
import io.mockk.mockk
import junit.framework.TestCase

class PsiSourcePositionUnitTest : TestCase() {

    fun testSourceOffsetRejectsNegativeOffset() {
        val element = mockk<PsiElement>()
        every { element.textOffset } returns -1

        assertNull(PsiSourcePosition.sourceOffset(element))
    }

    fun testSourceOffsetAcceptsZeroOffset() {
        val element = mockk<PsiElement>()
        every { element.textOffset } returns 0

        assertEquals(0, PsiSourcePosition.sourceOffset(element))
    }

    fun testSourceOffsetAcceptsPositiveOffset() {
        val element = mockk<PsiElement>()
        every { element.textOffset } returns 42

        assertEquals(42, PsiSourcePosition.sourceOffset(element))
    }
}
