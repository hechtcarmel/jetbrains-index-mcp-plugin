package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

import io.mockk.mockk
import junit.framework.TestCase

class MemberResolverUnitTest : TestCase() {

    private fun member(
        name: String,
        kind: String = "method",
        signature: String? = null,
        parameterCount: Int? = null,
        line: Int = 1,
        startOffset: Int = 0,
        endOffset: Int = 10
    ): ResolvedMember = ResolvedMember(
        element = mockk(relaxed = true),
        name = name,
        kind = kind,
        signature = signature,
        parameterCount = parameterCount,
        startOffset = startOffset,
        endOffset = endOffset,
        bodyStartOffset = startOffset + 5,
        bodyEndOffset = endOffset - 1,
        line = line
    )

    fun testDisambiguateSingleMemberReturnsIt() {
        val m = member("process", parameterCount = 1, line = 10)
        val result = MemberResolverUtils.disambiguate(listOf(m), "process", null, null)
        assertTrue(result.isSuccess)
        assertEquals(m, result.getOrThrow())
    }

    fun testDisambiguateEmptyListReturnsMemberNotFound() {
        val result = MemberResolverUtils.disambiguate(emptyList(), "missing", null, null)
        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(error is MemberNotFoundException)
        assertEquals("missing", (error as MemberNotFoundException).memberName)
    }

    fun testDisambiguateByParameterCount() {
        val m1 = member("process", parameterCount = 1, line = 10)
        val m2 = member("process", parameterCount = 2, line = 20)
        val result = MemberResolverUtils.disambiguate(listOf(m1, m2), "process", parameterCount = 2, line = null)
        assertTrue(result.isSuccess)
        assertEquals(m2, result.getOrThrow())
    }

    fun testDisambiguateByLine() {
        val m1 = member("process", parameterCount = 1, line = 10)
        val m2 = member("process", parameterCount = 2, line = 20)
        val result = MemberResolverUtils.disambiguate(listOf(m1, m2), "process", parameterCount = null, line = 20)
        assertTrue(result.isSuccess)
        assertEquals(m2, result.getOrThrow())
    }

    fun testDisambiguateByBothParameterCountAndLine() {
        val m1 = member("process", parameterCount = 1, line = 10)
        val m2 = member("process", parameterCount = 2, line = 20)
        val m3 = member("process", parameterCount = 2, line = 30)
        val result = MemberResolverUtils.disambiguate(listOf(m1, m2, m3), "process", parameterCount = 2, line = 30)
        assertTrue(result.isSuccess)
        assertEquals(m3, result.getOrThrow())
    }

    fun testDisambiguateNoMatchReturnsAmbiguousWithOriginalCandidates() {
        val m1 = member("process", parameterCount = 1, line = 10)
        val m2 = member("process", parameterCount = 2, line = 20)
        val result = MemberResolverUtils.disambiguate(listOf(m1, m2), "process", parameterCount = 5, line = null)
        assertTrue(result.isFailure)
        val error = result.exceptionOrNull() as AmbiguousMemberException
        assertEquals("process", error.memberName)
        assertEquals(2, error.candidates.size)
        assertTrue(error.hint.contains("No match"))
    }

    fun testDisambiguateStillAmbiguousReturnsFilteredCandidates() {
        val m1 = member("process", parameterCount = 1, line = 10)
        val m2 = member("process", parameterCount = 1, line = 20)
        val result = MemberResolverUtils.disambiguate(listOf(m1, m2), "process", parameterCount = 1, line = null)
        assertTrue(result.isFailure)
        val error = result.exceptionOrNull() as AmbiguousMemberException
        assertEquals(2, error.candidates.size)
        assertTrue(error.hint.contains("parameterCount or line"))
    }

    fun testDisambiguateWithNoConstraintsAndMultipleMembersReturnsAmbiguous() {
        val m1 = member("process", parameterCount = 0, line = 10)
        val m2 = member("process", parameterCount = 1, line = 20)
        val m3 = member("process", parameterCount = 2, line = 30)
        val result = MemberResolverUtils.disambiguate(listOf(m1, m2, m3), "process", null, null)
        assertTrue(result.isFailure)
        val error = result.exceptionOrNull() as AmbiguousMemberException
        assertEquals(3, error.candidates.size)
    }

    fun testMemberNotFoundExceptionMessage() {
        val ex = MemberNotFoundException("doWork")
        assertEquals("doWork", ex.memberName)
        assertTrue(ex.message!!.contains("doWork"))
    }

    fun testAmbiguousMemberExceptionPreservesCandidates() {
        val candidates = listOf(
            member("foo", parameterCount = 0, line = 5),
            member("foo", parameterCount = 1, line = 15)
        )
        val ex = AmbiguousMemberException("foo", candidates, "Use line to disambiguate.")
        assertEquals("foo", ex.memberName)
        assertEquals(2, ex.candidates.size)
        assertEquals("Use line to disambiguate.", ex.hint)
    }

    fun testMemberClassNotFoundExceptionMessage() {
        val ex = MemberClassNotFoundException("MyService", listOf("Main", "Config"))
        assertEquals("MyService", ex.className)
        assertEquals(listOf("Main", "Config"), ex.availableClasses)
        assertTrue(ex.message!!.contains("MyService"))
    }

    fun testResolvedMemberFieldHasNullParameterCount() {
        val field = member("count", kind = "field", parameterCount = null, line = 5)
        assertNull(field.parameterCount)
        assertEquals("field", field.kind)
    }

    fun testDisambiguateParameterCountFilterIgnoresNullParameterCount() {
        val field = member("value", kind = "field", parameterCount = null, line = 5)
        val method = member("value", kind = "method", parameterCount = 0, line = 10)
        val result = MemberResolverUtils.disambiguate(listOf(field, method), "value", parameterCount = 0, line = null)
        assertTrue(result.isSuccess)
        assertEquals("method", result.getOrThrow().kind)
    }
}
