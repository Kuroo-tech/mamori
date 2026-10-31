package com.reed.fcmguard

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.reed.fcmguard.core.log.MamoriLog
import java.util.Locale

/**
 * Best-effort detector for installed apps that declare standard FCM/GCM receive
 * components. It does not inspect traffic or accounts and runs only when requested.
 */
object FcmAppScanner {
    private const val TAG = "FcmAppScanner"
    private const val ACTION_FCM = "com.google.firebase.MESSAGING_EVENT"
    private const val ACTION_GCM_RECEIVE = "com.google.android.c2dm.intent.RECEIVE"

    class AppEntry(@JvmField val packageName: String, @JvmField val label: String)

    @JvmStatic
    fun scan(context: Context): List<AppEntry> {
        val packageManager = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PackageManager.MATCH_ALL
        } else {
            0
        }

        val packages = LinkedHashSet<String>()

        try {
            packageManager.queryIntentServices(Intent(ACTION_FCM), flags)?.forEach { info ->
                info.serviceInfo?.packageName?.let { packages.add(it) }
            }
        } catch (t: Throwable) {
            // A restricted PackageManager must not abort the legacy probe below.
            MamoriLog.warnOnce(
                "scan-fcm-services", TAG,
                "FCM service query failed", t
            )
        }

        // Legacy GCM / compatibility path still used by some apps and libraries.
        try {
            packageManager.queryBroadcastReceivers(Intent(ACTION_GCM_RECEIVE), flags)?.forEach { info ->
                info.activityInfo?.packageName?.let { packages.add(it) }
            }
        } catch (t: Throwable) {
            // Results so far remain valid; the failure is recorded for diagnostics.
            MamoriLog.warnOnce(
                "scan-gcm-receivers", TAG,
                "GCM receiver query failed", t
            )
        }

        val result = ArrayList<AppEntry>()
        for (packageName in packages) {
            if (packageName == context.packageName ||
                "com.google.android.gms" == packageName ||
                "com.android.vending" == packageName
            ) {
                continue
            }

            try {
                val appInfo = packageManager.getApplicationInfo(packageName, 0)
                if (!appInfo.enabled) continue
                // Keep the list focused on user-facing apps that can actually be opened.
                if (packageManager.getLaunchIntentForPackage(packageName) == null) continue
                val label = packageManager.getApplicationLabel(appInfo)?.toString()?.trim()
                    ?.takeIf { it.isNotEmpty() } ?: packageName
                result.add(AppEntry(packageName, label))
            } catch (_: Throwable) {
                // Uninstalled or hidden between the query and the lookup.
            }
        }

        result.sortWith(
            compareBy<AppEntry> { it.label.lowercase(Locale.ROOT) }.thenBy { it.packageName }
        )
        return result
    }
}
