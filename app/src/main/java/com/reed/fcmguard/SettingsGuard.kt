package com.reed.fcmguard

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings

/**
 * The single place that reads and writes the HyperOS whitelist setting.
 *
 * Behavior is intentionally identical to the upstream implementation: preserve every
 * existing package, append the required item only when missing, write once, and do
 * nothing when the value is already correct. Verification of the write (read-back)
 * is not implemented yet and is the next safety milestone.
 */
object SettingsGuard {
    const val PREFS = "guard_state"
    const val PREF_ENABLED = "enabled"
    const val PREF_PERSISTENT_NOTIFICATION = "persistent_notification"
    private const val LAST_GOOD_PREFIX = "last_good_"

    /**
     * Upstream seeds used when both the live value and the last-known-good snapshot
     * are empty. Inventing packages here is unsafe; callers pass these explicitly and
     * Phase 2 replaces this path with a user confirmation.
     */
    private val LEGACY_EMPTY_SEEDS = listOf("com.tencent.mm", "com.android.vending")

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

    /**
     * Repairs only when necessary. A no-op repair performs no Settings write and, unless
     * the actual package list changed, no SharedPreferences write either. This is
     * important because the common background path should be practically idle.
     */
    @Synchronized
    @JvmStatic
    fun repair(context: Context): Result {
        if (!Settings.System.canWrite(context)) {
            return Result(false, false, read(context), context.getString(R.string.permission_missing))
        }

        val key = getConfiguredKey(context)
        val required = getConfiguredRequiredItem(context)
        val current = read(context)

        if (hasRequiredItem(context, current)) {
            rememberIfUseful(context, current)
            return Result(true, false, current, context.getString(R.string.already_protected))
        }

        val packages = PackageList.buildRepaired(
            current = current,
            lastGood = prefs(context).getString(LAST_GOOD_PREFIX + key, null),
            required = required,
            emptySeeds = LEGACY_EMPTY_SEEDS
        )
        val repaired = PackageList.join(packages)
        try {
            val ok = Settings.System.putString(context.contentResolver, key, repaired)
            if (ok) {
                saveLastGoodIfChanged(prefs(context), key, repaired)
                return Result(true, true, repaired, context.getString(R.string.repaired))
            }
            return Result(false, false, current, context.getString(R.string.write_rejected))
        } catch (t: Throwable) {
            return Result(
                false, false, current,
                context.getString(R.string.write_failed, t.javaClass.simpleName)
            )
        }
    }

    @JvmStatic
    fun rememberIfUseful(context: Context) {
        rememberIfUseful(context, read(context))
    }

    private fun rememberIfUseful(context: Context, current: String?) {
        if (current.isNullOrBlank()) return
        val key = getConfiguredKey(context)
        val packages = PackageList.parse(current)
        if (packages.isEmpty()) return
        saveLastGoodIfChanged(prefs(context), key, PackageList.join(packages))
    }

    private fun saveLastGoodIfChanged(prefs: SharedPreferences, key: String, value: String) {
        val prefKey = LAST_GOOD_PREFIX + key
        if (value != prefs.getString(prefKey, null)) {
            prefs.edit().putString(prefKey, value).apply()
        }
    }

    class Result(
        @JvmField val success: Boolean,
        @JvmField val changed: Boolean,
        @JvmField val value: String?,
        @JvmField val message: String
    )
}
