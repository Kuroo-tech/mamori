package com.reed.fcmguard

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import com.reed.fcmguard.core.log.MamoriLog
import com.reed.fcmguard.execution.Operations
import com.reed.fcmguard.execution.OutcomeText
import com.reed.fcmguard.execution.RepairOutcome

class GuardService : Service() {
    // v2 intentionally uses a new channel id. Android does not allow an app to raise
    // an existing channel from IMPORTANCE_MIN to IMPORTANCE_LOW after creation.
    private val handler = Handler(Looper.getMainLooper())
    private var observer: ContentObserver? = null
    private var foreground = false
    private var repairing = false

    private val fallbackCheck = object : Runnable {
        override fun run() {
            requestRepair(notifyFailure = false)
            handler.postDelayed(this, FALLBACK_INTERVAL_MS)
        }
    }

    private val repairDebounced = Runnable { requestRepair(notifyFailure = true) }

    override fun onCreate() {
        super.onCreate()
        MamoriLog.attach(applicationContext)
        MamoriLog.info(TAG, "service created")
        applyExecutionMode()
        registerObserver()
        Operations.primeFallbackAsync(this)
        handler.post(fallbackCheck)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        SettingsGuard.setProtectionEnabled(this, true)
        registerObserver()
        applyExecutionMode()
        handler.removeCallbacks(fallbackCheck)
        handler.post(fallbackCheck)
        return START_STICKY
    }

    /**
     * Privileged work runs on the serialized background executor, never on this handler.
     * A repair already in flight suppresses overlapping requests instead of queueing them.
     */
    private fun requestRepair(notifyFailure: Boolean) {
        if (repairing) return
        repairing = true
        Operations.repairAsync(this, confirmEmptyWhitelist = false) { outcome ->
            repairing = false
            handleOutcome(outcome, notifyFailure)
        }
    }

    private fun handleOutcome(outcome: RepairOutcome, notifyFailure: Boolean) {
        when (outcome) {
            is RepairOutcome.Applied -> {
                FcmReconnect.kick(this)
                if (foreground) refreshNotification(getString(R.string.notification_repaired))
            }
            // Expected states that need no background noise: the dashboard explains them.
            RepairOutcome.AlreadyCorrect,
            is RepairOutcome.ConfirmationRequired,
            is RepairOutcome.RecoveryRequired -> Unit
            else -> {
                MamoriLog.warn(TAG, "background repair failed: ${outcome::class.simpleName}")
                if (notifyFailure && foreground) {
                    refreshNotification(OutcomeText.repair(this, outcome))
                }
            }
        }
    }

    private fun registerObserver() {
        val resolver = contentResolver
        try {
            observer?.let { resolver.unregisterContentObserver(it) }
        } catch (_: Throwable) {
            // Already unregistered.
        }

        val newObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: android.net.Uri?) {
                handler.removeCallbacks(repairDebounced)
                handler.postDelayed(repairDebounced, DEBOUNCE_MS)
            }
        }
        resolver.registerContentObserver(
            Settings.System.getUriFor(SettingsGuard.getConfiguredKey(this)),
            false,
            newObserver
        )
        observer = newObserver
    }

    private fun applyExecutionMode() {
        if (SettingsGuard.usePersistentNotification(this)) {
            ensureNotificationChannel(this)
            startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.notification_active)))
            foreground = true
        } else {
            if (foreground) stopForeground(true)
            val manager = getSystemService(NOTIFICATION_SERVICE) as? NotificationManager
            manager?.cancel(NOTIFICATION_ID)
            foreground = false
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try {
            observer?.let { contentResolver.unregisterContentObserver(it) }
        } catch (_: Throwable) {
            // Already unregistered.
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun refreshNotification(text: String) {
        if (!foreground) return
        val manager = getSystemService(NOTIFICATION_SERVICE) as? NotificationManager
        manager?.notify(NOTIFICATION_ID, buildNotification(text))
    }

    @Suppress("DEPRECATION")
    private fun buildNotification(text: String): Notification {
        val open = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this).setPriority(Notification.PRIORITY_LOW)
        }

        return builder
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .build()
    }

    companion object {
        private const val TAG = "GuardService"
        private const val CHANNEL_ID = "fcm_guard_persistent_v2"
        private const val NOTIFICATION_ID = 426
        private const val FALLBACK_INTERVAL_MS = 30L * 60L * 1000L
        private const val DEBOUNCE_MS = 400L

        /**
         * Creates the visible-but-silent foreground-service channel. Returns true only
         * when this call created the channel for the first time.
         */
        @JvmStatic
        fun ensureNotificationChannel(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return false

            val created = manager.getNotificationChannel(CHANNEL_ID) == null
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = context.getString(R.string.notification_channel_description)
            channel.setShowBadge(false)
            channel.enableVibration(false)
            channel.enableLights(false)
            channel.setSound(null, null)
            channel.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            manager.createNotificationChannel(channel)
            return created
        }

        /** True when Android will actually place the foreground notification in the shade. */
        @JvmStatic
        fun canShowPersistentNotification(context: Context): Boolean {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return false

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !manager.areNotificationsEnabled()) {
                return false
            }
            if (Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                return false
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = manager.getNotificationChannel(CHANNEL_ID) ?: return false
                return channel.importance != NotificationManager.IMPORTANCE_NONE
            }
            return true
        }
    }
}
