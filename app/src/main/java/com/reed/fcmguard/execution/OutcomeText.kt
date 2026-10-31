package com.reed.fcmguard.execution

import android.content.Context
import com.reed.fcmguard.R

/**
 * Turns operation outcomes into user-facing text. Kept in one place so the wording of a
 * failure cannot drift between the service notification and the dashboard.
 */
object OutcomeText {

    @JvmStatic
    fun repair(context: Context, outcome: RepairOutcome): String = when (outcome) {
        is RepairOutcome.Applied -> context.getString(R.string.result_applied)
        RepairOutcome.AlreadyCorrect -> context.getString(R.string.already_protected)
        RepairOutcome.PermissionMissing -> context.getString(R.string.permission_missing)
        is RepairOutcome.ConfirmationRequired -> context.getString(R.string.result_confirmation_required)
        is RepairOutcome.SnapshotFailed ->
            context.getString(R.string.result_snapshot_failed, outcome.cause)
        is RepairOutcome.ReadFailed -> context.getString(R.string.result_read_failed, outcome.cause)
        is RepairOutcome.WriteRejected -> context.getString(R.string.write_rejected)
        is RepairOutcome.WriteFailed -> context.getString(R.string.write_failed, outcome.cause)
        is RepairOutcome.VerificationFailed -> context.getString(R.string.result_unverified)
        is RepairOutcome.RecoveryRequired -> context.getString(R.string.result_recovery_required)
    }

    @JvmStatic
    fun restore(context: Context, outcome: RestoreOutcome): String = when (outcome) {
        is RestoreOutcome.Restored -> context.getString(R.string.restore_done)
        RestoreOutcome.NoSnapshot -> context.getString(R.string.restore_none)
        is RestoreOutcome.SnapshotCorrupt -> context.getString(R.string.restore_corrupt)
        RestoreOutcome.PermissionMissing -> context.getString(R.string.permission_missing)
        is RestoreOutcome.SnapshotFailed ->
            context.getString(R.string.result_snapshot_failed, outcome.cause)
        is RestoreOutcome.ReadFailed -> context.getString(R.string.result_read_failed, outcome.cause)
        is RestoreOutcome.WriteRejected -> context.getString(R.string.write_rejected)
        is RestoreOutcome.WriteFailed -> context.getString(R.string.write_failed, outcome.cause)
        is RestoreOutcome.VerificationFailed -> context.getString(R.string.restore_unverified)
        is RestoreOutcome.RecoveryRequired -> context.getString(R.string.result_recovery_required)
    }
}
