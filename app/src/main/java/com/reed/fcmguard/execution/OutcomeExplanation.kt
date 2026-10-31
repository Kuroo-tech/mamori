package com.reed.fcmguard.execution

import android.content.Context
import androidx.annotation.StringRes
import com.reed.fcmguard.R

enum class OutcomeSeverity { SUCCESS, WARNING, ERROR }

/**
 * Everything a user needs after an operation: what happened, the technical detail that
 * was captured, and what to do next. The headline strings are the same ones
 * [OutcomeText] uses, so the notification and the result dialog cannot disagree.
 */
data class OutcomeExplanation(
    @StringRes val headline: Int,
    val arguments: List<String> = emptyList(),
    @StringRes val suggestion: Int? = null,
    val technicalDetail: String? = null,
    val severity: OutcomeSeverity
) {
    fun headline(context: Context): String =
        if (arguments.isEmpty()) {
            context.getString(headline)
        } else {
            context.getString(headline, *arguments.toTypedArray())
        }
}

/**
 * Maps verified-operation outcomes to a message, a recovery suggestion and the raw
 * detail worth showing. No outcome ever gains a suggestion it does not deserve: a
 * successful verified write has nothing to recover from.
 */
object OutcomeExplanations {

    fun repair(outcome: RepairOutcome): OutcomeExplanation = when (outcome) {
        is RepairOutcome.Applied -> OutcomeExplanation(
            headline = R.string.result_applied,
            technicalDetail = detail(outcome.operationId, outcome.snapshotId),
            severity = OutcomeSeverity.SUCCESS
        )

        RepairOutcome.AlreadyCorrect -> OutcomeExplanation(
            headline = R.string.already_protected,
            severity = OutcomeSeverity.SUCCESS
        )

        RepairOutcome.PermissionMissing -> OutcomeExplanation(
            headline = R.string.permission_missing,
            suggestion = R.string.suggestion_grant_permission,
            severity = OutcomeSeverity.ERROR
        )

        is RepairOutcome.ConfirmationRequired -> OutcomeExplanation(
            headline = R.string.result_confirmation_required,
            suggestion = R.string.suggestion_confirm_whitelist,
            technicalDetail = outcome.proposedValue,
            severity = OutcomeSeverity.WARNING
        )

        is RepairOutcome.SnapshotFailed -> OutcomeExplanation(
            headline = R.string.result_snapshot_failed,
            arguments = listOf(outcome.cause),
            suggestion = R.string.suggestion_check_storage,
            severity = OutcomeSeverity.ERROR
        )

        is RepairOutcome.ReadFailed -> OutcomeExplanation(
            headline = R.string.result_read_failed,
            arguments = listOf(outcome.cause),
            suggestion = R.string.suggestion_retry_permission,
            severity = OutcomeSeverity.ERROR
        )

        is RepairOutcome.WriteRejected -> OutcomeExplanation(
            headline = R.string.write_rejected,
            suggestion = R.string.suggestion_retry_permission,
            technicalDetail = detail(outcome.operationId, null),
            severity = OutcomeSeverity.ERROR
        )

        is RepairOutcome.WriteFailed -> OutcomeExplanation(
            headline = R.string.write_failed,
            arguments = listOf(outcome.cause),
            suggestion = R.string.suggestion_retry_permission,
            technicalDetail = detail(outcome.operationId, null),
            severity = OutcomeSeverity.ERROR
        )

        is RepairOutcome.VerificationFailed -> OutcomeExplanation(
            headline = R.string.result_unverified,
            suggestion = R.string.suggestion_check_history,
            technicalDetail = "expected=${outcome.expected} actual=${outcome.actual ?: "null"}\n${detail(outcome.operationId, null)}",
            severity = OutcomeSeverity.ERROR
        )

        is RepairOutcome.RecoveryRequired -> OutcomeExplanation(
            headline = R.string.result_recovery_required,
            suggestion = R.string.suggestion_recover,
            technicalDetail = "${outcome.pending.state.name} ${outcome.pending.id}",
            severity = OutcomeSeverity.WARNING
        )
    }

    fun restore(outcome: RestoreOutcome): OutcomeExplanation = when (outcome) {
        is RestoreOutcome.Restored -> OutcomeExplanation(
            headline = R.string.restore_done,
            technicalDetail = detail(outcome.operationId, outcome.snapshotId),
            severity = OutcomeSeverity.SUCCESS
        )

        RestoreOutcome.NoSnapshot -> OutcomeExplanation(
            headline = R.string.restore_none,
            severity = OutcomeSeverity.WARNING
        )

        is RestoreOutcome.SnapshotCorrupt -> OutcomeExplanation(
            headline = R.string.restore_corrupt,
            suggestion = R.string.suggestion_check_backup,
            technicalDetail = outcome.snapshotId,
            severity = OutcomeSeverity.ERROR
        )

        RestoreOutcome.PermissionMissing -> OutcomeExplanation(
            headline = R.string.permission_missing,
            suggestion = R.string.suggestion_grant_permission,
            severity = OutcomeSeverity.ERROR
        )

        is RestoreOutcome.SnapshotFailed -> OutcomeExplanation(
            headline = R.string.result_snapshot_failed,
            arguments = listOf(outcome.cause),
            suggestion = R.string.suggestion_check_storage,
            severity = OutcomeSeverity.ERROR
        )

        is RestoreOutcome.ReadFailed -> OutcomeExplanation(
            headline = R.string.result_read_failed,
            arguments = listOf(outcome.cause),
            suggestion = R.string.suggestion_retry_permission,
            severity = OutcomeSeverity.ERROR
        )

        is RestoreOutcome.WriteRejected -> OutcomeExplanation(
            headline = R.string.write_rejected,
            suggestion = R.string.suggestion_retry_permission,
            technicalDetail = detail(outcome.operationId, null),
            severity = OutcomeSeverity.ERROR
        )

        is RestoreOutcome.WriteFailed -> OutcomeExplanation(
            headline = R.string.write_failed,
            arguments = listOf(outcome.cause),
            suggestion = R.string.suggestion_retry_permission,
            technicalDetail = detail(outcome.operationId, null),
            severity = OutcomeSeverity.ERROR
        )

        is RestoreOutcome.VerificationFailed -> OutcomeExplanation(
            headline = R.string.restore_unverified,
            suggestion = R.string.suggestion_check_history,
            technicalDetail = "expected=${outcome.expected} actual=${outcome.actual ?: "null"}\n${detail(outcome.operationId, null)}",
            severity = OutcomeSeverity.ERROR
        )

        is RestoreOutcome.RecoveryRequired -> OutcomeExplanation(
            headline = R.string.result_recovery_required,
            suggestion = R.string.suggestion_recover,
            technicalDetail = "${outcome.pending.state.name} ${outcome.pending.id}",
            severity = OutcomeSeverity.WARNING
        )
    }

    private fun detail(operationId: String, snapshotId: String?): String {
        val backup = snapshotId?.let { " snapshot=$it" }.orEmpty()
        return "operation=$operationId$backup"
    }
}
