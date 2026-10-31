package com.reed.fcmguard

import java.util.Locale

/**
 * Pure normalization of app-language tags, extracted from [LocaleHelper] so the
 * mapping rules are testable without an Android runtime.
 */
internal object LocaleTag {
    const val SYSTEM = "system"

    fun normalize(code: String?): String {
        if (code.isNullOrBlank() || SYSTEM.equals(code, ignoreCase = true)) return SYSTEM

        val lower = code.lowercase(Locale.ROOT)
        if (lower.startsWith("zh-tw") || lower.startsWith("zh-hk") ||
            lower.startsWith("zh-mo") || lower.contains("hant")
        ) {
            return "zh-TW"
        }
        if (lower.startsWith("zh")) return "zh-CN"
        if (lower.startsWith("fr")) return "fr"
        if (lower.startsWith("ja")) return "ja"
        if (lower.startsWith("ko")) return "ko"
        if (lower.startsWith("es")) return "es"
        if (lower.startsWith("pt")) return "pt"
        if (lower.startsWith("de")) return "de"
        if (lower.startsWith("ru")) return "ru"
        if (lower.startsWith("en")) return "en"
        return SYSTEM
    }
}
