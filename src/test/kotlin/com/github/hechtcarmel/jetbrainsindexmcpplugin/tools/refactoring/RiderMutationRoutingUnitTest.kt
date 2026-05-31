package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.MutationVerification
import junit.framework.TestCase

class RiderMutationRoutingUnitTest : TestCase() {

    fun testSharedMutationVerificationSummaryKeepsLimitedVerificationObservable() {
        val summary = RiderMutationResultMapper.summary(
            legacySuccess = true,
            status = "success",
            affectedFiles = listOf("src/Service.cs"),
            changesCount = 1,
            message = "Rename applied with bounded verification.",
            verification = MutationVerification(
                status = "limited",
                checksRun = listOf("post_change_semantics"),
                warnings = listOf("Closed-file diagnostics are supplementary only")
            ),
            contract = RiderMutationResultMapper.StatusContract.CANONICAL
        )

        assertFalse(summary.success)
        assertEquals("failed", summary.status)
        assertEquals("limited", summary.verification?.status)
        assertEquals(listOf("src/Service.cs"), summary.affectedFiles)
        assertEquals(1, summary.changesCount)
    }

    fun testSharedMutationVerificationSummaryDowngradesFailedVerificationToNonSuccess() {
        val summary = RiderMutationResultMapper.summary(
            legacySuccess = true,
            status = "success",
            affectedFiles = listOf("src/Service.cs"),
            changesCount = 1,
            message = "Move applied but semantic verification failed.",
            verification = MutationVerification(
                status = "failed",
                checksRun = listOf("post_change_semantics"),
                warnings = listOf("Semantic verification failed")
            ),
            contract = RiderMutationResultMapper.StatusContract.CANONICAL
        )

        assertFalse(summary.success)
        assertEquals("failed", summary.status)
        assertEquals("failed", summary.verification?.status)
        assertEquals(listOf("src/Service.cs"), summary.affectedFiles)
        assertEquals(1, summary.changesCount)
    }

    fun testSharedMutationVerificationSummaryMapsBlockedToCanonicalUnsupportedContextByDefault() {
        val summary = RiderMutationResultMapper.summary(
            legacySuccess = false,
            status = "blocked",
            affectedFiles = emptyList(),
            changesCount = 0,
            message = "Rename remained blocked because the workflow requires preview interaction.",
            verification = null,
            contract = RiderMutationResultMapper.StatusContract.CANONICAL
        )

        assertFalse(summary.success)
        assertEquals("conflict", summary.status)
        assertTrue(summary.affectedFiles.isEmpty())
        assertEquals(0, summary.changesCount)
    }

    fun testSharedMutationVerificationSummaryCanPreserveBlockedForSafeDeleteContracts() {
        val summary = RiderMutationResultMapper.summary(
            legacySuccess = false,
            status = "blocked",
            affectedFiles = listOf("src/Service.cs"),
            changesCount = 0,
            message = "Deletion blocked by usages.",
            verification = null,
            contract = RiderMutationResultMapper.StatusContract.PRESERVE_BLOCKED
        )

        assertFalse(summary.success)
        assertEquals("blocked", summary.status)
        assertTrue(summary.affectedFiles.isEmpty())
        assertEquals(0, summary.changesCount)
    }
}
