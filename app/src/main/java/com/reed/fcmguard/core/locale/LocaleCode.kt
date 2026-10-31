package com.reed.fcmguard.core.locale

import java.util.Locale

/**
 * BCP 47 normalization and display helpers.
 *
 * This is deliberately separate from [com.reed.fcmguard.LocaleTag]: that one narrows a
 * device locale down to the eleven languages Mamori ships UI resources for, while this one
 * validates and canonicalizes arbitrary BCP 47 tags for the language manager. Conflating
 * them would make "vi-VN is a valid tag" and "vi is a supported app language" the same
 * statement, and they are not.
 */
/** The subtags of a canonical tag: `zh-Hans-CN` → (`zh`, `Hans`, `CN`). */
data class LocaleParts(val language: String, val script: String, val region: String)

object LocaleCode {

    /** Long enough for language-script-region-variants, short enough to reject junk. */
    const val MAX_LENGTH = 35

    // language (2-3 alpha), optional script (4 alpha), optional region (2 alpha | 3 digit),
    // then variants (5-8 alphanumeric or digit + 3 alphanumeric). Extensions are rejected:
    // Android's LocaleList does not need them and silently dropping them would be worse.
    private val STRUCTURE = Regex(
        "^[A-Za-z]{2,3}(?:-[A-Za-z]{4})?(?:-(?:[A-Za-z]{2}|[0-9]{3}))?" +
            "(?:-(?:[A-Za-z0-9]{5,8}|[0-9][A-Za-z0-9]{3}))*$"
    )

    /**
     * Canonicalizes a tag (`en-us` → `en-US`, `zh-hans-cn` → `zh-Hans-CN`), or returns
     * null when the input is not a structurally valid BCP 47 tag. `und` is rejected
     * because it means "unknown", which is not a language anyone asked for.
     */
    fun normalize(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed.length > MAX_LENGTH) return null
        if (!STRUCTURE.matches(trimmed)) return null

        val locale = try {
            Locale.forLanguageTag(trimmed)
        } catch (_: Throwable) {
            return null
        }
        if (locale.language.isNullOrEmpty() || locale.language.equals("und", ignoreCase = true)) {
            return null
        }
        val tag = locale.toLanguageTag()
        return tag.takeIf { it.isNotEmpty() && it != "und" }
    }

    fun isValid(raw: String?): Boolean = normalize(raw) != null

    /** Splits a tag into language, script and region; null when it is not a valid tag. */
    fun parse(raw: String?): LocaleParts? {
        val normalized = normalize(raw) ?: return null
        val locale = try {
            Locale.forLanguageTag(normalized)
        } catch (_: Throwable) {
            return null
        }
        return LocaleParts(
            language = locale.language.orEmpty(),
            script = locale.script.orEmpty(),
            region = locale.country.orEmpty()
        )
    }

    /** True when two tags describe the same language and script, ignoring region. */
    fun sameLanguage(first: String?, second: String?): Boolean {
        val a = normalize(first)?.let(Locale::forLanguageTag) ?: return false
        val b = normalize(second)?.let(Locale::forLanguageTag) ?: return false
        return a.language == b.language &&
            (a.script.orEmpty().equals(b.script.orEmpty(), ignoreCase = true))
    }

    /**
     * Human-readable name in [inLocale], falling back to the tag itself when Android has
     * no display name for it.
     */
    fun displayName(tag: String, inLocale: Locale = Locale.getDefault()): String {
        val normalized = normalize(tag) ?: return tag
        return try {
            Locale.forLanguageTag(normalized).getDisplayName(inLocale).ifBlank { normalized }
        } catch (_: Throwable) {
            normalized
        }
    }

    /** Name of the language in that language, e.g. "Tiếng Việt" for `vi`. */
    fun nativeName(tag: String): String {
        val normalized = normalize(tag) ?: return tag
        return try {
            val locale = Locale.forLanguageTag(normalized)
            locale.getDisplayName(locale).ifBlank { normalized }
        } catch (_: Throwable) {
            normalized
        }
    }
}
