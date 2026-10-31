package com.reed.fcmguard.core.locale

/**
 * Locales Mamori itself ships UI resources for.
 *
 * On Android 13+, `LocaleManager.setApplicationLocales` rejects any locale that is not
 * declared in `res/xml/locales_config.xml`. This list is that declaration in Kotlin form;
 * `tools/check_layout_profiles.py` fails if the two drift apart or if the language arrays
 * stop matching.
 */
object SupportedAppLocales {
    val codes: List<String> = listOf(
        "en",
        "zh-CN",
        "zh-TW",
        "fr",
        "ja",
        "ko",
        "es",
        "pt",
        "de",
        "ru",
        "vi"
    )

    /** Locale list plus the explicit "follow the system" choice. */
    val pickerCodes: List<String> = listOf(SYSTEM) + codes

    const val SYSTEM = "system"

    fun isSupported(code: String?): Boolean = codes.contains(code)
}
