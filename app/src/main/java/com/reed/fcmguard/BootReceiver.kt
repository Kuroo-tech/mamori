package com.reed.fcmguard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!SettingsGuard.isProtectionEnabled(context)) return

        val service = Intent(context, GuardService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                SettingsGuard.usePersistentNotification(context)
            ) {
                context.startForegroundService(service)
            } else {
                context.startService(service)
            }
        } catch (_: Throwable) {
            // Startup can legitimately be restricted by the ROM; protection is
            // re-established the next time the app is opened.
        }
    }
}
