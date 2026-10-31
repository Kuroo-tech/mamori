package com.reed.fcmguard

import android.app.LocaleManager
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

object LocaleHelper {
    private const val PREFS = "guard_state"
    private const val KEY_LANG = "ui_language"
    private const val KEY_MIGRATED = "native_locale_migrated"

    /** Android 13+ uses LocaleManager; older Android keeps a small compatibility path. */
    @JvmStatic
    fun apply(context: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return context

        val code = getLegacyLanguage(context)
        if (LocaleTag.SYSTEM == code) return context

        val locale = Locale.forLanguageTag(code)
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        return context.createConfigurationContext(config)
    }

    @JvmStatic
    fun getLanguage(context: Context): String {
        if (Build.VERSION.SDK_INT >= 33) {
            val localeManager = context.getSystemService(Context.LOCALE_SERVICE) as? LocaleManager
                ?: return LocaleTag.SYSTEM
            val locales = localeManager.applicationLocales
            if (locales.isEmpty) return LocaleTag.SYSTEM
            return LocaleTag.normalize(locales[0].toLanguageTag())
        }
        return getLegacyLanguage(context)
    }

    @JvmStatic
    fun setLanguage(context: Context, code: String?) {
        val normalized = LocaleTag.normalize(code)
        if (Build.VERSION.SDK_INT >= 33) {
            val localeManager = context.getSystemService(Context.LOCALE_SERVICE) as? LocaleManager
                ?: return
            val locales = if (LocaleTag.SYSTEM == normalized) {
                LocaleList.getEmptyLocaleList()
            } else {
                LocaleList.forLanguageTags(normalized)
            }
            localeManager.applicationLocales = locales
            return
        }

        prefs(context).edit().putString(KEY_LANG, normalized).apply()
    }

    /** One-time bridge from the pre-v1.4.4 custom language preference. */
    @JvmStatic
    fun migrateLegacyPreference(context: Context) {
        if (Build.VERSION.SDK_INT < 33) return

        val prefs = prefs(context)
        if (prefs.getBoolean(KEY_MIGRATED, false)) return

        val legacy = LocaleTag.normalize(prefs.getString(KEY_LANG, LocaleTag.SYSTEM))
        prefs.edit().putBoolean(KEY_MIGRATED, true).remove(KEY_LANG).apply()

        val localeManager = context.getSystemService(Context.LOCALE_SERVICE) as? LocaleManager
            ?: return
        if (LocaleTag.SYSTEM == legacy) return
        val current = localeManager.applicationLocales
        if (current.isEmpty) {
            localeManager.applicationLocales = LocaleList.forLanguageTags(legacy)
        }
    }

    private fun getLegacyLanguage(context: Context): String =
        LocaleTag.normalize(prefs(context).getString(KEY_LANG, LocaleTag.SYSTEM))

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
