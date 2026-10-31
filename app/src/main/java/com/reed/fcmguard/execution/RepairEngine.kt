package com.reed.fcmguard.execution

import com.reed.fcmguard.PackageList
import java.util.UUID

/**
 * Result of a repair attempt. Every outcome states what is known, not what was hoped:
 * "applied" is only returned after a read-back confirmed the stored value.
 */
sealed interface RepairOutcome {
    /** Written and confirmed by read-back. [snapshotId] is null when there was nothing to save. */
    data class Applied(
        val operationId: String,
        val value: String,
        val previousValue: String?,
        val snapshotId: String?
    ) : RepairOutcome

    /** The required item was already present; nothing was written. */
    data object AlreadyCorrect : RepairOutcome

    /** WRITE_SETTINGS is not granted; nothing was written. */
    data object PermissionMissing : RepairOutcome

    /**
     * The whitelist is empty and Mamori will not invent packages. The caller must ask the
     * user; [proposedValue] is exactly what a confirmed repair would write.
     */
    data class ConfirmationRequired(
        val requiredItem: String,
        val proposedValue: String
    ) : RepairOutcome

    /** A pre-write copy could not be stored, so the write was abandoned. */
    data class SnapshotFailed(val cause: String) : RepairOutcome

    data class ReadFailed(val cause: String) : RepairOutcome

    data class WriteRejected(val operationId: String) : RepairOutcome

    data class WriteFailed(val operationId: String, val cause: String) : RepairOutcome

    /**
     * The platform accepted the write but the value does not read back as written.
     * The write may or may not have landed, so this is never reported as success.
     */
    data class VerificationFailed(
        val operationId: String,
        val expected: String,
        val actual: String?
    ) : RepairOutcome

    /** A previous operation never finished. Nothing is attempted until the user decides. */
    data class RecoveryRequired(val pending: OperationRecord) : RepairOutcome
}

sealed interface RestoreOutcome {
    data class Restored(
        val operationId: String,
        val key: String,
        val value: String,
        val snapshotId: String
    ) : RestoreOutcome

    data object NoSnapshot : RestoreOutcome

    /** The stored copy no longer matches its digest; it must not be written back. */
    data class SnapshotCorrupt(val snapshotId: String) : RestoreOutcome

    data object PermissionMissing : RestoreOutcome

    data class SnapshotFailed(val cause: String) : RestoreOutcome

    data class ReadFailed(val cause: String) : RestoreOutcome

    data class WriteRejected(val operationId: String) : RestoreOutcome

    data class WriteFailed(val operationId: String, val cause: String) : RestoreOutcome

    data class VerificationFailed(
        val operationId: String,
        val expected: String,
        val actual: String?
    ) : RestoreOutcome

    data class RecoveryRequired(val pending: OperationRecord) : RestoreOutcome
}

/**
 * The verified write pipeline.
 *
 * `read → (nothing to do | confirm) → snapshot → write → read-back → completed`
 *
 * Rules that are not negotiable here:
 * 1. No existing package is ever dropped; appends only.
 * 2. No packages are invented. An empty whitelist needs explicit confirmation.
 * 3. A write is only "applied" when the value reads back identically.
 * 4. A pending operation blocks new work; recovery is the user's decision, never automatic.
 *
 * This class performs blocking I/O by design. Callers must run it off the main thread;
 * [Operations] is the Android entry point that guarantees that.
 */
class RepairEngine(
    private val gateway: SystemSettingsGateway,
    private val snapshots: SnapshotStore,
    private val journal: OperationJournal,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() }
) {

    fun repair(
        key: String,
        requiredItem: String,
        fallbackValue: String?,
        confirmEmptyWhitelist: Boolean = false
    ): RepairOutcome {
        journal.active()?.let { return RepairOutcome.RecoveryRequired(it) }

        // A missing permission is a precondition, not an attempted operation: it does not
        // create a journal entry, so a revoked permission cannot flood the history.
        if (!gateway.canWrite()) return RepairOutcome.PermissionMissing

        val at = now()
        val operation = journal.start(newId(), key, at)

        val current = when (val read = read(key)) {
            is Read.Ok -> read.value
            is Read.Failed -> {
                journal.complete(operation, OperationState.FAILED, at, "$DETAIL_READ_FAILED:${read.cause}")
                return RepairOutcome.ReadFailed(read.cause)
            }
        }

        if (PackageList.contains(current, requiredItem)) {
            journal.complete(operation, OperationState.COMPLETED, at, DETAIL_ALREADY_CORRECT)
            return RepairOutcome.AlreadyCorrect
        }

        val required = requiredItem.trim()
        val known = PackageList.parse(current).ifEmpty { PackageList.parse(fallbackValue) }
        if (known.isEmpty() && required.isNotEmpty() && !confirmEmptyWhitelist) {
            journal.complete(operation, OperationState.CANCELLED, at, DETAIL_CONFIRMATION_REQUIRED)
            return RepairOutcome.ConfirmationRequired(required, required)
        }

        val value = PackageList.join(PackageList.buildRepaired(current, fallbackValue, required))
        if (value == current) {
            journal.complete(operation, OperationState.COMPLETED, at, DETAIL_ALREADY_CORRECT)
            return RepairOutcome.AlreadyCorrect
        }

        val snapshotId = when (val saved = snapshotBeforeWrite(operation, key, current, at)) {
            is Save.Saved -> saved.id
            is Save.Failed -> {
                journal.complete(operation, OperationState.FAILED, at, "$DETAIL_SNAPSHOT_FAILED:${saved.cause}")
                return RepairOutcome.SnapshotFailed(saved.cause)
            }
        }

        return when (val written = writeVerified(operation, key, value, snapshotId, at)) {
            is Write.Written ->
                RepairOutcome.Applied(written.operationId, value, current, snapshotId)
            is Write.Rejected -> RepairOutcome.WriteRejected(written.operationId)
            is Write.Failed -> RepairOutcome.WriteFailed(written.operationId, written.cause)
            is Write.Unverified ->
                RepairOutcome.VerificationFailed(written.operationId, value, written.actual)
        }
    }

    /**
     * Restores a stored value. The current value is snapshotted first, so a restore is
     * itself reversible and never destroys the only copy of anything.
     */
    fun restore(snapshotId: String): RestoreOutcome {
        journal.active()?.let { return RestoreOutcome.RecoveryRequired(it) }

        val snapshot = snapshots.find(snapshotId) ?: return RestoreOutcome.NoSnapshot
        if (!snapshots.verify(snapshot)) return RestoreOutcome.SnapshotCorrupt(snapshot.id)
        // Checked before any journal entry or snapshot so a refusal leaves no trace of a
        // change that never started.
        if (!gateway.canWrite()) return RestoreOutcome.PermissionMissing

        val at = now()
        val operation = journal.start(newId(), snapshot.key, at)

        val current = when (val read = read(snapshot.key)) {
            is Read.Ok -> read.value
            is Read.Failed -> {
                journal.complete(operation, OperationState.FAILED, at, "$DETAIL_READ_FAILED:${read.cause}")
                return RestoreOutcome.ReadFailed(read.cause)
            }
        }

        if (current == snapshot.value) {
            journal.complete(operation, OperationState.COMPLETED, at, DETAIL_ALREADY_CORRECT, snapshot.id)
            return RestoreOutcome.Restored(operation.id, snapshot.key, snapshot.value, snapshot.id)
        }

        val preRestore = when (val saved = snapshotBeforeWrite(operation, snapshot.key, current, at)) {
            is Save.Saved -> saved.id
            is Save.Failed -> {
                journal.complete(operation, OperationState.FAILED, at, "$DETAIL_SNAPSHOT_FAILED:${saved.cause}")
                return RestoreOutcome.SnapshotFailed(saved.cause)
            }
        }

        val writeOperation = journal.advance(operation, OperationState.ROLLING_BACK, at, null)
        val written = writeVerified(writeOperation, snapshot.key, snapshot.value, preRestore, at)

        return when (written) {
            is Write.Written ->
                RestoreOutcome.Restored(written.operationId, snapshot.key, snapshot.value, snapshot.id)
            is Write.Rejected -> RestoreOutcome.WriteRejected(written.operationId)
            is Write.Failed -> RestoreOutcome.WriteFailed(written.operationId, written.cause)
            is Write.Unverified ->
                RestoreOutcome.VerificationFailed(written.operationId, snapshot.value, written.actual)
        }
    }

    private fun writeVerified(
        operation: OperationRecord,
        key: String,
        value: String,
        snapshotId: String?,
        at: Long
    ): Write {
        journal.advance(operation, OperationState.WRITING, at, null, snapshotId)

        val accepted = try {
            gateway.write(key, value)
        } catch (t: Throwable) {
            journal.complete(
                operation, OperationState.FAILED, at,
                "$DETAIL_WRITE_FAILED:${t.javaClass.simpleName}", snapshotId
            )
            return Write.Failed(operation.id, t.javaClass.simpleName)
        }

        if (!accepted) {
            journal.complete(operation, OperationState.FAILED, at, DETAIL_WRITE_REJECTED, snapshotId)
            return Write.Rejected(operation.id)
        }

        journal.advance(operation, OperationState.VERIFYING, at, null, snapshotId)
        val actual = when (val read = read(key)) {
            is Read.Ok -> read.value
            is Read.Failed -> null
        }

        if (actual != value) {
            // Accepted but not confirmed: the write may have landed, so this is partial.
            journal.complete(
                operation, OperationState.PARTIALLY_COMPLETED, at,
                DETAIL_VERIFICATION_FAILED, snapshotId
            )
            return Write.Unverified(operation.id, value, actual)
        }

        journal.complete(operation, OperationState.COMPLETED, at, null, snapshotId)
        return Write.Written(operation.id)
    }

    private fun snapshotBeforeWrite(
        operation: OperationRecord,
        key: String,
        current: String?,
        at: Long
    ): Save {
        journal.advance(operation, OperationState.SNAPSHOTTING, at)

        // An absent or empty value cannot be snapshotted, because deleting a setting has
        // no verified implementation here. Nothing to save is not a failure.
        val storable = current?.takeIf { PackageList.parse(it).isNotEmpty() } ?: return Save.Saved(null)
        return try {
            Save.Saved(snapshots.save(operation.id, key, storable, at).id)
        } catch (t: Throwable) {
            Save.Failed(t.javaClass.simpleName)
        }
    }

    private fun read(key: String): Read =
        try {
            Read.Ok(gateway.read(key))
        } catch (t: Throwable) {
            Read.Failed(t.javaClass.simpleName)
        }

    private sealed interface Read {
        data class Ok(val value: String?) : Read
        data class Failed(val cause: String) : Read
    }

    private sealed interface Save {
        data class Saved(val id: String?) : Save
        data class Failed(val cause: String) : Save
    }

    private sealed interface Write {
        data class Written(val operationId: String) : Write
        data class Rejected(val operationId: String) : Write
        data class Failed(val operationId: String, val cause: String) : Write
        data class Unverified(val operationId: String, val expected: String, val actual: String?) : Write
    }

    private companion object {
        const val DETAIL_READ_FAILED = "read_failed"
        const val DETAIL_ALREADY_CORRECT = "already_correct"
        const val DETAIL_CONFIRMATION_REQUIRED = "confirmation_required"
        const val DETAIL_WRITE_REJECTED = "write_rejected"
        const val DETAIL_WRITE_FAILED = "write_failed"
        const val DETAIL_VERIFICATION_FAILED = "verification_failed"
        const val DETAIL_SNAPSHOT_FAILED = "snapshot_failed"
    }
}
