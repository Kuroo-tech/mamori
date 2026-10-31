package com.reed.fcmguard

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/** HyperOS/MIUI settings deep links with safe Android fallbacks. */
object HyperOsSettings {

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

    private fun launch(context: Context, intent: Intent): Boolean {
        return try {
            if (intent.resolveActivity(context.packageManager) == null) return false
            context.startActivity(intent)
            true
        } catch (_: Throwable) {
            false
        }
    }
}
