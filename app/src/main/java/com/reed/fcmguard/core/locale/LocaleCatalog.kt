package com.reed.fcmguard.core.locale

import java.util.Locale

/** One selectable entry: the canonical tag plus the names the picker shows. */
data class LocaleChoice(
    val tag: String,
    val displayName: String,
    val nativeName: String
)

/**
 * The list of languages the system picker offers.
 *
 * It is derived from the device's own locale table rather than a hand-written list, so the
 * picker can offer language+region combinations (`en-GB`, `pt-BR`, `zh-Hans-CN`) instead of
 * only the eleven languages Mamori translates itself. The common choices are ordered first;
 * everything else follows alphabetically. Pure and JVM-testable.
 */
object LocaleCatalog {

    /**
     * Ordered first, because these are what this app's own UI and its Vietnamese-first
     * audience use most.
     */
    private val preferred = listOf(
        "vi-VN",
        "en-US",
        "en-GB",
        "zh-Hans-CN",
        "zh-Hant-TW",
        "ja-JP",
        "ko-KR",
        "fr-FR",
        "de-DE",
        "es-ES",
        "pt-BR",
        "ru-RU",
        "th-TH",
        "id-ID"
    )

    /** Normalized, de-duplicated, preferred first, then by display name. */
    fun systemChoices(inLocale: Locale = Locale.getDefault()): List<LocaleChoice> {
        val seen = LinkedHashMap<String, LocaleChoice>()

        fun add(tag: String): LocaleChoice? {
            val normalized = LocaleCode.normalize(tag) ?: return null
            seen[normalized]?.let { return it }
            val choice = LocaleChoice(
                tag = normalized,
                displayName = LocaleCode.displayName(normalized, inLocale),
                nativeName = LocaleCode.nativeName(normalized)
            )
            seen[normalized] = choice
            return choice
        }

        preferred.forEach(::add)

        // `getAvailableLocales` lists both `ja-JP` and `ja-Jpan-JP`. The script variant adds
        // nothing (it is already implied) but would appear twice in the picker, so a script
        // tag is dropped when the same language and region exist without it. Scripts that
        // do carry meaning (`zh-Hans`, `sr-Latn`) survive because their plain counterpart
        // has a different language or no region.
        val available = Locale.getAvailableLocales()
            .asSequence()
            .filter { !it.language.isNullOrEmpty() }
            .mapNotNull { LocaleCode.normalize(it.toLanguageTag()) }
            .distinct()
            .toList()

        val plainByLanguageAndRegion = available
            .mapNotNull { tag -> LocaleCode.parse(tag) }
            .filter { it.script.isEmpty() }
            .map { (it.language to it.region) }
            .toSet()

        val rest = available
            .filterNot { tag ->
                val parts = LocaleCode.parse(tag) ?: return@filterNot false
                parts.script.isNotEmpty() &&
                    (parts.language to parts.region) in plainByLanguageAndRegion
            }
            .mapNotNull(::add)
            .sortedWith(compareBy({ it.displayName.lowercase(Locale.ROOT) }, { it.tag }))

        val head = preferred.mapNotNull { seen[it] }
        val emitted = head.map { it.tag }.toMutableSet()
        val tail = rest.filter { choice -> emitted.add(choice.tag) }
        return head + tail
    }

    /** Search over display name, native name and tag. */
    fun filter(
        choices: List<LocaleChoice>,
        query: String
    ): List<LocaleChoice> {
        val needle = query.trim().lowercase(Locale.ROOT)
        if (needle.isEmpty()) return choices
        return choices.filter { choice ->
            choice.tag.lowercase(Locale.ROOT).contains(needle) ||
                choice.displayName.lowercase(Locale.ROOT).contains(needle) ||
                choice.nativeName.lowercase(Locale.ROOT).contains(needle)
        }
    }
}
