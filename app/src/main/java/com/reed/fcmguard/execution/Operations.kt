package com.reed.fcmguard.execution

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.reed.fcmguard.SettingsGuard
import com.reed.fcmguard.core.log.MamoriLog
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Android entry point for privileged work.
 *
 * Everything here runs on one serialized background thread, so two operations can never
 * interleave writes to the same setting, and no caller can accidentally block the UI
 * thread on a content provider.
 */
object Operations {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "mamori-operations").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())

    /**
     * True while this process is between journal.start and a terminal state. Used only to
     * tell an in-flight operation apart from one left behind by a dead process.
     */
    private val inFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    fun interface RepairCallback {
        fun onResult(outcome: RepairOutcome)
    }

    fun interface RestoreCallback {
        fun onResult(outcome: RestoreOutcome)
    }

    @JvmStatic
    fun repairAsync(context: Context, confirmEmptyWhitelist: Boolean, callback: RepairCallback) {
        val app = context.applicationContext
        MamoriLog.attach(app)
        executor.execute {
            MamoriLog.info(TAG, "repair started (confirmEmptyWhitelist=$confirmEmptyWhitelist)")
            val outcome = tracked { repairBlocking(app, confirmEmptyWhitelist) }
            logRepair(outcome)
            main.post { callback.onResult(outcome) }
        }
    }

    @JvmStatic
    fun restoreAsync(context: Context, snapshotId: String, callback: RestoreCallback) {
        val app = context.applicationContext
        MamoriLog.attach(app)
        executor.execute {
            MamoriLog.info(TAG, "restore started (snapshot=$snapshotId)")
            val outcome = tracked { restoreBlocking(app, snapshotId) }
            logRestore(outcome)
            main.post { callback.onResult(outcome) }
        }
    }

    private fun logRepair(outcome: RepairOutcome) {
        when (outcome) {
            is RepairOutcome.Applied -> MamoriLog.info(
                TAG,
                "repair applied op=${outcome.operationId} snapshot=${outcome.snapshotId}"
            )
            RepairOutcome.AlreadyCorrect -> MamoriLog.info(TAG, "repair skipped: already correct")
            is RepairOutcome.RecoveryRequired -> MamoriLog.warn(
                TAG,
                "repair blocked by pending operation ${outcome.pending.id}"
            )
            else -> MamoriLog.warn(TAG, "repair outcome=${outcome::class.simpleName} $outcome")
        }
    }

    private fun logRestore(outcome: RestoreOutcome) {
        when (outcome) {
            is RestoreOutcome.Restored -> MamoriLog.info(
                TAG,
                "restore applied op=${outcome.operationId} snapshot=${outcome.snapshotId}"
            )
            is RestoreOutcome.RecoveryRequired -> MamoriLog.warn(
                TAG,
                "restore blocked by pending operation ${outcome.pending.id}"
            )
            else -> MamoriLog.warn(TAG, "restore outcome=${outcome::class.simpleName} $outcome")
        }
    }

    /** Seeds the repair fallback from the current value, off the main thread. */
    @JvmStatic
    fun primeFallbackAsync(context: Context) {
        val app = context.applicationContext
        executor.execute { SettingsGuard.rememberCurrentValue(app) }
    }

    private inline fun <T> tracked(block: () -> T): T {
        inFlight.set(true)
        try {
            return block()
        } finally {
            inFlight.set(false)
        }
    }

    @JvmStatic
    fun repairBlocking(context: Context, confirmEmptyWhitelist: Boolean): RepairOutcome {
        val app = context.applicationContext
        val key = SettingsGuard.getConfiguredKey(app)
        val outcome = engine(app).repair(
            key = key,
            requiredItem = SettingsGuard.getConfiguredRequiredItem(app),
            fallbackValue = SettingsGuard.getLastGoodValue(app, key),
            confirmEmptyWhitelist = confirmEmptyWhitelist
        )
        if (outcome is RepairOutcome.Applied) {
            SettingsGuard.rememberVerifiedValue(app, key, outcome.value)
        }
        return outcome
    }

    @JvmStatic
    fun restoreBlocking(context: Context, snapshotId: String): RestoreOutcome {
        val app = context.applicationContext
        val outcome = engine(app).restore(snapshotId)
        if (outcome is RestoreOutcome.Restored) {
            SettingsGuard.rememberVerifiedValue(app, outcome.key, outcome.value)
        }
        return outcome
    }

    /** Most recent intact snapshot for the currently configured key, if any. */
    @JvmStatic
    fun latestSnapshot(context: Context): Snapshot? =
        snapshotStore(context).latestFor(SettingsGuard.getConfiguredKey(context))

    /** All intact snapshots, newest first. Read-only; used by diagnostics. */
    @JvmStatic
    fun snapshots(context: Context): List<Snapshot> = snapshotStore(context).all()

    /**
     * A non-terminal record left behind by a previous process. Never cleared silently.
     * An operation running right now is not an interrupted one, so it is not reported.
     */
    @JvmStatic
    fun pendingInterrupted(context: Context): OperationRecord? =
        if (inFlight.get()) null else journal(context).active()

    /** Explicit user action: records the leftover as interrupted and clears the blocker. */
    @JvmStatic
    fun dismissInterrupted(context: Context): OperationRecord? =
        journal(context).abandonInterrupted(System.currentTimeMillis())

    @JvmStatic
    fun history(context: Context): List<OperationRecord> = journal(context).history()

    /**
     * The journaled stage of an operation that is running in this process right now, or
     * null when nothing is in flight. Read-only and never invents a stage: the record is
     * the one the engine itself wrote before each privileged step.
     */
    @JvmStatic
    fun currentStage(context: Context): OperationState? =
        if (inFlight.get()) journal(context).active()?.state else null

    private fun engine(context: Context): RepairEngine = RepairEngine(
        gateway = AndroidSystemSettingsGateway(context),
        snapshots = snapshotStore(context),
        journal = journal(context)
    )

    private fun snapshotStore(context: Context): SnapshotStore =
        SnapshotStore(File(context.applicationContext.filesDir, "snapshots"))

    private const val TAG = "Operations"

    private fun journal(context: Context): OperationJournal =
        SharedPreferencesOperationJournal(
            PreferencesKeyValueStore(
                context.getSharedPreferences(SettingsGuard.PREFS, Context.MODE_PRIVATE)
            )
        )
}
