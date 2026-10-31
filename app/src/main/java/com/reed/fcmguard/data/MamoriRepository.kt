package com.reed.fcmguard.data

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.reed.fcmguard.FcmReconnect
import com.reed.fcmguard.GuardService
import com.reed.fcmguard.SettingsGuard
import com.reed.fcmguard.core.log.MamoriLog
import com.reed.fcmguard.execution.OperationRecord
import com.reed.fcmguard.execution.OperationState
import com.reed.fcmguard.execution.Operations
import com.reed.fcmguard.execution.RepairOutcome
import com.reed.fcmguard.execution.RestoreOutcome
import com.reed.fcmguard.execution.Snapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * The single data seam between the Compose UI and the verified execution layer.
 *
 * ViewModels never touch `Settings.System` or the journal directly, and no composable
 * reaches this object: every call here is a suspend function on a background dispatcher
 * or an explicit user-facing platform action (opening the permission screen is done by
 * the Activity, not here).
 */
class MamoriRepository(context: Context) {
    private val app: Context = context.applicationContext

    /** Everything the dashboard states as fact, read from the live system. */
    suspend fun readStatus(): DeviceStatus = withContext(Dispatchers.IO) {
        val canWrite = try {
            Settings.System.canWrite(app)
        } catch (_: Throwable) {
            false
        }
        val key = SettingsGuard.getConfiguredKey(app)
        val value = try {
            SettingsGuard.read(app)
        } catch (_: Throwable) {
            null
        }
        DeviceStatus(
            canWrite = canWrite,
            protectionEnabled = SettingsGuard.isProtectionEnabled(app),
            persistentNotification = SettingsGuard.usePersistentNotification(app),
            notificationVisible = SettingsGuard.usePersistentNotification(app) &&
                SettingsGuard.isProtectionEnabled(app) &&
                GuardService.canShowPersistentNotification(app),
            key = key,
            requiredItem = SettingsGuard.getConfiguredRequiredItem(app),
            whitelist = value,
            requiredPresent = SettingsGuard.hasRequiredItem(app, value),
            latestSnapshot = Operations.latestSnapshot(app),
            pendingInterrupted = Operations.pendingInterrupted(app)
        )
    }

    /** Read-only diagnostics: live status plus what is stored on disk. */
    suspend fun diagnostics(): DiagnosticsData = withContext(Dispatchers.IO) {
        DiagnosticsData(
            status = readStatus(),
            snapshots = Operations.snapshots(app),
            historyCount = Operations.history(app).size
        )
    }

    suspend fun repair(confirmEmptyWhitelist: Boolean): RepairOutcome =
        suspendCancellableCoroutine { continuation ->
            Operations.repairAsync(app, confirmEmptyWhitelist) { outcome ->
                if (continuation.isActive) continuation.resume(outcome)
            }
        }

    suspend fun restore(snapshotId: String): RestoreOutcome =
        suspendCancellableCoroutine { continuation ->
            Operations.restoreAsync(app, snapshotId) { outcome ->
                if (continuation.isActive) continuation.resume(outcome)
            }
        }

    suspend fun dismissInterrupted(): OperationRecord? = withContext(Dispatchers.IO) {
        Operations.dismissInterrupted(app)
    }

    suspend fun history(): List<OperationRecord> = withContext(Dispatchers.IO) {
        Operations.history(app)
    }

    /** Stage of the operation currently running in this process, or null. */
    fun currentStage(): OperationState? = Operations.currentStage(app)

    fun saveConfiguration(key: String, requiredItem: String) =
        SettingsGuard.saveConfig(app, key, requiredItem)

    fun setProtectionEnabled(enabled: Boolean) {
        SettingsGuard.setProtectionEnabled(app, enabled)
        if (enabled) {
            startProtectionService()
        } else {
            app.stopService(Intent(app, GuardService::class.java))
        }
    }

    fun setPersistentNotification(enabled: Boolean) {
        SettingsGuard.setPersistentNotification(app, enabled)
        if (SettingsGuard.isProtectionEnabled(app)) startProtectionService()
    }

    /** Creates the notification channel now so the permission follow-up can tell whether it is new. */
    fun ensureNotificationChannel(): Boolean = GuardService.ensureNotificationChannel(app)

    fun canShowPersistentNotification(): Boolean =
        GuardService.canShowPersistentNotification(app)

    fun wakeFcm(): Boolean = FcmReconnect.kick(app)

    /**
     * Restarts the service when protection is enabled and the foreground channel is
     * usable — the same recovery the old Activity performed on resume, so a ROM that
     * stopped the service does not silently end protection.
     */
    fun ensureProtectionServiceRunning() {
        if (SettingsGuard.isProtectionEnabled(app) &&
            SettingsGuard.usePersistentNotification(app) &&
            GuardService.canShowPersistentNotification(app)
        ) {
            startProtectionService()
        }
    }

    private companion object {
        const val TAG = "MamoriRepository"
    }

    private fun startProtectionService() {
        val persistent = SettingsGuard.usePersistentNotification(app)
        if (persistent) GuardService.ensureNotificationChannel(app)
        val intent = Intent(app, GuardService::class.java)
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O && persistent) {
                app.startForegroundService(intent)
            } else {
                app.startService(intent)
            }
        } catch (t: Throwable) {
            // The service may be blocked by the ROM; the dashboard shows the result on refresh.
            MamoriLog.warn(TAG, "protection service could not be started", t)
        }
    }
}

/**
 * A snapshot of the device state the UI is allowed to claim. Every field is the result
 * of a real read; there are no defaults that could be mistaken for success.
 */
data class DiagnosticsData(
    val status: DeviceStatus,
    val snapshots: List<Snapshot>,
    val historyCount: Int
)

data class DeviceStatus(
    val canWrite: Boolean,
    val protectionEnabled: Boolean,
    val persistentNotification: Boolean,
    val notificationVisible: Boolean,
    val key: String,
    val requiredItem: String,
    val whitelist: String?,
    val requiredPresent: Boolean,
    val latestSnapshot: Snapshot?,
    val pendingInterrupted: OperationRecord?
)
