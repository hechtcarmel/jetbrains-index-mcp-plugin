package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.MutationVerification
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.RefactoringResult

internal object RiderMutationResultMapper {
    private const val STATUS_SUCCESS = "success"
    private const val STATUS_NO_OP = "no_op"
    private const val STATUS_BLOCKED = "blocked"
    private const val STATUS_UNSUPPORTED = "unsupported"
    private const val STATUS_NOT_SUPPORTED = "not_supported"
    private const val STATUS_VERIFICATION_LIMITED = "verification_limited"
    private const val STATUS_VERIFICATION_FAILED = "verification_failed"
    private const val STATUS_NEEDS_ACTIVE_EDITOR = "needs_active_editor"
    private const val STATUS_CONFLICT = "conflict"
    private const val STATUS_UNSUPPORTED_CONTEXT = "unsupported_context"
    private const val STATUS_FAILED = "failed"
    private const val VERIFICATION_LIMITED = "limited"
    private const val VERIFICATION_FAILED = "failed"

    internal enum class StatusContract {
        CANONICAL,
        PRESERVE_BLOCKED
    }

    internal data class Summary(
        val success: Boolean,
        val status: String,
        val affectedFiles: List<String>,
        val changesCount: Int,
        val message: String,
        val verification: MutationVerification?
    ) {
        fun toRefactoringResult(): RefactoringResult = RefactoringResult(
            success = success,
            affectedFiles = affectedFiles,
            changesCount = changesCount,
            message = message,
            status = status,
            verification = verification
        )
    }

    internal fun summary(
        legacySuccess: Boolean,
        status: String?,
        affectedFiles: List<String>,
        changesCount: Int,
        message: String,
        verification: MutationVerification?,
        contract: StatusContract = StatusContract.CANONICAL
    ): Summary {
        val normalizedFiles = affectedFiles.distinct()
        val normalizedChangesCount = changesCount.coerceAtLeast(0)
        val normalizedStatus = normalizeStatus(
            legacySuccess = legacySuccess,
            status = status,
            affectedFiles = normalizedFiles,
            changesCount = normalizedChangesCount,
            message = message,
            verification = verification,
            contract = contract
        )
        val keepObservableChanges = keepsObservableChanges(normalizedStatus, contract)

        return Summary(
            success = isSuccess(normalizedStatus, contract),
            status = normalizedStatus,
            affectedFiles = if (keepObservableChanges) normalizedFiles else emptyList(),
            changesCount = if (keepObservableChanges) normalizedChangesCount else 0,
            message = message,
            verification = normalizeVerification(normalizedStatus, verification, contract)
        )
    }

    internal fun toMutationVerification(
        rawVerification: Any?,
        propertyReader: (Any, String) -> Any?
    ): MutationVerification? {
        if (rawVerification == null) return null

        val status = propertyReader(rawVerification, "status") as? String ?: return null
        val checksRun = (propertyReader(rawVerification, "checksRun") as? List<*>)
            ?.mapNotNull { it as? String }
            ?: emptyList()
        val warnings = (propertyReader(rawVerification, "warnings") as? List<*>)
            ?.mapNotNull { it as? String }
            ?: emptyList()

        return MutationVerification(
            status = status,
            checksRun = checksRun,
            warnings = warnings
        )
    }

    private fun normalizeStatus(
        legacySuccess: Boolean,
        status: String?,
        affectedFiles: List<String>,
        changesCount: Int,
        message: String,
        verification: MutationVerification?,
        contract: StatusContract
    ): String {
        when (verification?.status?.trim()?.lowercase()) {
            VERIFICATION_FAILED, STATUS_VERIFICATION_FAILED -> {
                return if (contract == StatusContract.CANONICAL) STATUS_FAILED else STATUS_VERIFICATION_FAILED
            }

            VERIFICATION_LIMITED, STATUS_VERIFICATION_LIMITED -> {
                return if (contract == StatusContract.CANONICAL) STATUS_FAILED else STATUS_VERIFICATION_LIMITED
            }
        }

        val normalizedStatus = status?.trim()?.lowercase()
        val hasObservableChanges = changesCount > 0 || affectedFiles.isNotEmpty()

        if (!normalizedStatus.isNullOrEmpty()) {
            return when (normalizedStatus) {
                STATUS_SUCCESS -> if (!hasObservableChanges) STATUS_NO_OP else STATUS_SUCCESS
                STATUS_NO_OP,
                STATUS_NEEDS_ACTIVE_EDITOR,
                STATUS_CONFLICT,
                STATUS_UNSUPPORTED_CONTEXT,
                STATUS_FAILED -> normalizedStatus
                STATUS_BLOCKED -> if (contract == StatusContract.CANONICAL) {
                    classifyBlockedStatus(message)
                } else {
                    STATUS_BLOCKED
                }

                STATUS_UNSUPPORTED,
                STATUS_NOT_SUPPORTED -> if (contract == StatusContract.CANONICAL) STATUS_UNSUPPORTED_CONTEXT else normalizedStatus
                STATUS_VERIFICATION_LIMITED,
                STATUS_VERIFICATION_FAILED -> if (contract == StatusContract.CANONICAL) STATUS_FAILED else normalizedStatus
                else -> if (contract == StatusContract.CANONICAL) {
                    if (legacySuccess && hasObservableChanges) STATUS_SUCCESS else STATUS_FAILED
                } else {
                    normalizedStatus
                }
            }
        }

        if (!legacySuccess) {
            return if (contract == StatusContract.CANONICAL) STATUS_FAILED else STATUS_BLOCKED
        }
        return if (hasObservableChanges) STATUS_SUCCESS else STATUS_NO_OP
    }

    private fun classifyBlockedStatus(message: String): String {
        val normalizedMessage = message.trim().lowercase()
        return when {
            normalizedMessage.contains("active editor") ||
                normalizedMessage.contains("editor required") ||
                normalizedMessage.contains("focused interaction") -> STATUS_NEEDS_ACTIVE_EDITOR

            normalizedMessage.contains("preview") ||
                normalizedMessage.contains("conflict") ||
                normalizedMessage.contains("chooser") ||
                normalizedMessage.contains("modal") ||
                normalizedMessage.contains("user interaction") -> STATUS_CONFLICT

            else -> STATUS_UNSUPPORTED_CONTEXT
        }
    }

    private fun normalizeVerification(status: String, verification: MutationVerification?, contract: StatusContract): MutationVerification? {
        if (contract == StatusContract.CANONICAL) {
            return verification
        }

        return when (status) {
            STATUS_VERIFICATION_LIMITED -> verification ?: MutationVerification(
                status = VERIFICATION_LIMITED,
                checksRun = listOf("post_change_semantics"),
                warnings = listOf("verification remained limited")
            )
            STATUS_VERIFICATION_FAILED -> verification ?: MutationVerification(
                status = VERIFICATION_FAILED,
                checksRun = listOf("post_change_semantics"),
                warnings = listOf("semantic verification failed")
            )
            else -> verification
        }
    }

    private fun isSuccess(status: String, contract: StatusContract): Boolean {
        return if (contract == StatusContract.CANONICAL) {
            status == STATUS_SUCCESS
        } else {
            status == STATUS_SUCCESS || status == STATUS_VERIFICATION_LIMITED
        }
    }

    private fun keepsObservableChanges(status: String, contract: StatusContract): Boolean {
        return if (contract == StatusContract.CANONICAL) {
            status == STATUS_SUCCESS || status == STATUS_FAILED
        } else {
            status == STATUS_SUCCESS ||
                status == STATUS_VERIFICATION_LIMITED ||
                status == STATUS_VERIFICATION_FAILED
        }
    }
}
