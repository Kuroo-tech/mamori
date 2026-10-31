package com.reed.fcmguard

import android.content.Context
import android.content.res.Configuration

object ThemeHelper {
    private const val PREFS = "guard_state"
    private const val KEY_MODE = "appearance_mode"
    private const val KEY_DYNAMIC_COLOR = "dynamic_color"

    const val MODE_SYSTEM = "system"
    const val MODE_LIGHT = "light"
    const val MODE_DARK = "dark"

    /**
     * Applies only a local Configuration override. No service, timer, receiver, or
     * background task is involved; system mode remains the default.
     */
    @JvmStatic
    fun apply(context: Context): Context {
        val mode = getMode(context)
        if (MODE_SYSTEM == mode) return context

        val config = Configuration(context.resources.configuration)
        val night = if (MODE_DARK == mode) {
            Configuration.UI_MODE_NIGHT_YES
        } else {
            Configuration.UI_MODE_NIGHT_NO
        }
        config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
        return context.createConfigurationContext(config)
    }

    @JvmStatic
    fun getMode(context: Context): String {
        val mode = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MODE, MODE_SYSTEM)
        if (MODE_LIGHT == mode || MODE_DARK == mode) return mode!!
        return MODE_SYSTEM
    }

    @JvmStatic
    fun setMode(context: Context, mode: String?) {
        val normalized = if (MODE_LIGHT == mode || MODE_DARK == mode) mode!! else MODE_SYSTEM
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MODE, normalized)
            .apply()
    }

    /** Dynamic color is on by default; it only ever affects the app's own UI. */
    @JvmStatic
    fun isDynamicColorEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_DYNAMIC_COLOR, true)

    @JvmStatic
    fun setDynamicColorEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DYNAMIC_COLOR, enabled)
            .apply()
    }

    @JvmStatic
    fun isDark(context: Context): Boolean {
        val current = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return current == Configuration.UI_MODE_NIGHT_YES
    }
}
