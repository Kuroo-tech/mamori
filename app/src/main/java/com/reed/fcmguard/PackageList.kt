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
     * removed, the required item is appended when missing, and [emptySeeds] are used
     * only when both the live value and the last-known-good snapshot are empty.
     *
     * [emptySeeds] exists to preserve upstream behavior exactly. Inventing packages
     * for an empty whitelist is unsafe and is scheduled for replacement in Phase 2;
     * callers must pass an explicit list rather than relying on hidden defaults.
     */
    fun buildRepaired(
        current: String?,
        lastGood: String?,
        required: String?,
        emptySeeds: List<String>
    ): LinkedHashSet<String> {
        val packages = parse(current)
        if (packages.isEmpty()) packages.addAll(parse(lastGood))
        if (packages.isEmpty()) packages.addAll(emptySeeds)
        if (!required.isNullOrBlank()) packages.add(required.trim())
        return packages
    }
}
