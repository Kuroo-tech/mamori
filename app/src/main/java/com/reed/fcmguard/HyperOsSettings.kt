package com.reed.fcmguard

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.reed.fcmguard.core.log.MamoriLog

/** HyperOS/MIUI settings deep links with safe Android fallbacks. */
object HyperOsSettings {
    private const val TAG = "HyperOsSettings"

    @JvmStatic
    fun openAutoStartManager(context: Context): Boolean {
        val intent = Intent("miui.intent.action.OP_AUTO_START")
        intent.addCategory(Intent.CATEGORY_DEFAULT)
        if (launch(context, intent)) return true

        val explicit = Intent()
        explicit.component = ComponentName(
            "com.miui.securitycenter",
            "com.miui.permcenter.autostart.AutoStartManagementActivity"
        )
        if (launch(context, explicit)) return true

        return launch(context, Intent(Settings.ACTION_APPLICATION_SETTINGS))
    }

    @JvmStatic
    fun openAppPermissionEditor(context: Context, packageName: String): Boolean {
        val activityNames = arrayOf(
            "com.miui.permcenter.permissions.PermissionsEditorActivity",
            "com.miui.permcenter.permissions.AppPermissionsEditorActivity"
        )

        for (activityName in activityNames) {
            val intent = Intent()
            intent.component = ComponentName("com.miui.securitycenter", activityName)
            intent.putExtra("extra_pkgname", packageName)
            intent.putExtra("package_name", packageName)
            if (launch(context, intent)) return true
        }

        val details = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:$packageName")
        )
        details.addCategory(Intent.CATEGORY_DEFAULT)
        return launch(context, details)
    }

    /** The system language screen, used when a change needs the platform's own UI. */
    @JvmStatic
    fun openSystemLanguageSettings(context: Context): Boolean =
        launch(context, Intent(Settings.ACTION_LOCALE_SETTINGS))

    /**
     * App info. On Android 13+ this is where the per-app language screen lives; there is no
     * public deep link straight to it, so app info is the honest destination.
     */
    @JvmStatic
    fun openAppInfo(context: Context, packageName: String): Boolean {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:$packageName")
        )
        intent.addCategory(Intent.CATEGORY_DEFAULT)
        return launch(context, intent)
    }

    private fun launch(context: Context, intent: Intent): Boolean {
        return try {
            if (intent.resolveActivity(context.packageManager) == null) return false
            context.startActivity(intent)
            true
        } catch (t: Throwable) {
            MamoriLog.warnOnce("hyperos-launch-${intent.action}", TAG, "settings intent failed", t)
            false
        }
    }
}
