package com.reed.fcmguard

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings

/**
 * Configuration and read-only access to the HyperOS whitelist setting.
 *
 * Writes no longer live here. Every privileged change goes through
 * [com.reed.fcmguard.execution.RepairEngine], which snapshots, writes, and verifies the
 * result before it reports success.
 */
object SettingsGuard {
    const val PREFS = "guard_state"
    const val PREF_ENABLED = "enabled"
    const val PREF_PERSISTENT_NOTIFICATION = "persistent_notification"
    private const val LAST_GOOD_PREFIX = "last_good_"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @JvmStatic
    fun getConfiguredKey(context: Context): String =
        prefs(context).getString("settings_key", context.getString(R.string.default_key))
            ?: context.getString(R.string.default_key)

    @JvmStatic
    fun getConfiguredRequiredItem(context: Context): String =
        prefs(context).getString("required_item", context.getString(R.string.default_required_item))
            ?: context.getString(R.string.default_required_item)

    @JvmStatic
    fun isProtectionEnabled(context: Context): Boolean =
        prefs(context).getBoolean(PREF_ENABLED, false)

    @JvmStatic
    fun usePersistentNotification(context: Context): Boolean =
        prefs(context).getBoolean(PREF_PERSISTENT_NOTIFICATION, true)

    @JvmStatic
    fun setProtectionEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(PREF_ENABLED, enabled).apply()
    }

    @JvmStatic
    fun setPersistentNotification(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(PREF_PERSISTENT_NOTIFICATION, enabled).apply()
    }

    @JvmStatic
    fun saveConfig(context: Context, key: String?, requiredItem: String?) {
        prefs(context).edit()
            .putString("settings_key", safe(key, context.getString(R.string.default_key)))
            .putString("required_item", safe(requiredItem, context.getString(R.string.default_required_item)))
            .apply()
    }

    private fun safe(value: String?, fallback: String): String {
        if (value == null) return fallback
        val trimmed = value.trim()
        return trimmed.ifEmpty { fallback }
    }

    @JvmStatic
    fun read(context: Context): String? =
        Settings.System.getString(context.contentResolver, getConfiguredKey(context))

    @JvmStatic
    fun hasRequiredItem(context: Context, value: String?): Boolean =
        PackageList.contains(value, getConfiguredRequiredItem(context))

    /** Last value that a verified operation left behind, used only as a repair fallback. */
    @JvmStatic
    fun getLastGoodValue(context: Context, key: String): String? =
        prefs(context).getString(LAST_GOOD_PREFIX + key, null)

    /**
     * Seeds the repair fallback with what is currently stored. This is a read, not a
     * verification: [rememberVerifiedValue] is the post-read-back path.
     */
    @JvmStatic
    fun rememberCurrentValue(context: Context) {
        rememberVerifiedValue(context, getConfiguredKey(context), read(context))
    }

    /** Records the value that a read-back just confirmed. Never called before verification. */
    @JvmStatic
    fun rememberVerifiedValue(context: Context, key: String, value: String?) {
        if (value.isNullOrBlank()) return
        val prefKey = LAST_GOOD_PREFIX + key
        val prefs = prefs(context)
        if (value != prefs.getString(prefKey, null)) {
            prefs.edit().putString(prefKey, value).apply()
        }
    }
}
