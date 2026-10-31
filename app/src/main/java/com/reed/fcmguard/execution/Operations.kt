package com.reed.fcmguard.execution

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.reed.fcmguard.SettingsGuard
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
        executor.execute {
            val outcome = tracked { repairBlocking(app, confirmEmptyWhitelist) }
            main.post { callback.onResult(outcome) }
        }
    }

    @JvmStatic
    fun restoreAsync(context: Context, snapshotId: String, callback: RestoreCallback) {
        val app = context.applicationContext
        executor.execute {
            val outcome = tracked { restoreBlocking(app, snapshotId) }
            main.post { callback.onResult(outcome) }
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
        snapshots(context).latestFor(SettingsGuard.getConfiguredKey(context))

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

    private fun engine(context: Context): RepairEngine = RepairEngine(
        gateway = AndroidSystemSettingsGateway(context),
        snapshots = snapshots(context),
        journal = journal(context)
    )

    private fun snapshots(context: Context): SnapshotStore =
        SnapshotStore(File(context.applicationContext.filesDir, "snapshots"))

    private fun journal(context: Context): OperationJournal =
        SharedPreferencesOperationJournal(
            PreferencesKeyValueStore(
                context.getSharedPreferences(SettingsGuard.PREFS, Context.MODE_PRIVATE)
            )
        )
}
