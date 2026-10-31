package com.reed.fcmguard

/**
 * Pure, Android-free helpers for the comma-separated HyperOS whitelist value
 * (for example the `MILLET_NO_RESTRICT_APP` system setting).
 *
 * Kept free of framework types so the preserve/append rules — the part of the app
 * that must never silently change behavior — are unit-testable on the JVM.
 */
internal object PackageList {

    /** Splits on commas, trims each entry, drops blanks, preserves order and de-duplicates. */
    fun parse(value: String?): LinkedHashSet<String> {
        val out = LinkedHashSet<String>()
        if (value == null) return out
        for (part in value.split(",")) {
            val trimmed = part.trim()
            if (trimmed.isNotEmpty()) out.add(trimmed)
        }
        return out
    }

    fun join(items: Collection<String>): String = items.joinToString(",")

    /** True when [value] already contains [required] as a complete package entry. */
    fun contains(value: String?, required: String?): Boolean {
        if (required.isNullOrBlank() || value.isNullOrBlank()) return false
        for (part in value.split(",")) {
            if (required == part.trim()) return true
        }
        return false
    }

    /**
     * Builds the value a repair should write.
     *
     * Preserve-then-append only: existing packages keep their order and are never
     * removed, the required item is appended when missing, and the last known-good
     * value is used only when the live value has no packages at all.
     *
     * Nothing is invented. If both sources are empty, the result is just the required
     * item, and the caller is responsible for asking the user first.
     */
    fun buildRepaired(
        current: String?,
        lastGood: String?,
        required: String?
    ): LinkedHashSet<String> {
        val packages = parse(current)
        if (packages.isEmpty()) packages.addAll(parse(lastGood))
        if (!required.isNullOrBlank()) packages.add(required.trim())
        return packages
    }
}
