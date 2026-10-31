package com.reed.fcmguard.core.locale

import com.reed.fcmguard.execution.KeyValueStore
import java.security.MessageDigest

/**
 * The system locale list as it looked before Mamori changed it.
 *
 * [tags] is ordered: the first entry is the primary locale, the rest are the fallbacks the
 * user had configured. [sha256] covers the canonical payload, so a hand-edited preference
 * cannot turn into a "restore" that writes something the user never had.
 */
data class SystemLocaleSnapshot(
    val tags: List<String>,
    val createdAt: Long,
    val version: Int,
    val sha256: String
) {
    val primary: String get() = tags.first()
}

/**
 * Creation, encoding and verification of [SystemLocaleSnapshot].
 *
 * Pure on purpose: the "never overwrite the original snapshot" rule and the integrity
 * check are the parts that must not depend on Android, so they are unit-tested directly.
 */
object SystemLocaleSnapshots {
    const val VERSION = 1

    private const val FIELD = '|'

    /**
     * Normalizes [rawTags] and drops duplicates and blanks. Returns null when nothing
     * usable is left: an empty snapshot cannot restore anything and must never be stored.
     */
    fun create(rawTags: List<String>, createdAt: Long): SystemLocaleSnapshot? {
        val tags = rawTags.mapNotNull { LocaleCode.normalize(it) }.distinct()
        if (tags.isEmpty()) return null

        val canonical = canonical(tags, createdAt)
        return SystemLocaleSnapshot(
            tags = tags,
            createdAt = createdAt,
            version = VERSION,
            sha256 = sha256(canonical)
        )
    }

    /** True only when the record is intact and self-consistent. */
    fun verify(snapshot: SystemLocaleSnapshot): Boolean {
        if (snapshot.tags.isEmpty()) return false
        if (snapshot.tags != snapshot.tags.mapNotNull { LocaleCode.normalize(it) }.distinct()) {
            return false
        }
        val expected = sha256(canonical(snapshot.tags, snapshot.createdAt))
        return expected == snapshot.sha256
    }

    fun encode(snapshot: SystemLocaleSnapshot): String = listOf(
        snapshot.version.toString(),
        snapshot.createdAt.toString(),
        snapshot.tags.joinToString(","),
        snapshot.sha256
    ).joinToString(FIELD.toString())

    /** Returns null for malformed, foreign-version or tampered records. */
    fun decode(raw: String?): SystemLocaleSnapshot? {
        val parts = raw?.split(FIELD) ?: return null
        if (parts.size != 4) return null
        val version = parts[0].toIntOrNull() ?: return null
        if (version != VERSION) return null
        val createdAt = parts[1].toLongOrNull() ?: return null
        val tags = parts[2].split(',').filter { it.isNotBlank() }
        if (tags.isEmpty()) return null
        val snapshot = SystemLocaleSnapshot(
            tags = tags,
            createdAt = createdAt,
            version = version,
            sha256 = parts[3]
        )
        return snapshot.takeIf { verify(it) }
    }

    private fun canonical(tags: List<String>, createdAt: Long): String =
        (listOf(VERSION.toString(), createdAt.toString(), tags.joinToString(",")))
            .joinToString("\n")

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }
}

/**
 * Persists the original system locale snapshot.
 *
 * [capture] refuses to overwrite an existing valid snapshot: changing the system language
 * again must not silently replace the only record of what the device started with. A
 * corrupt record is not "protected" — it cannot restore anything, so a fresh capture takes
 * its place.
 */
class SystemLocaleSnapshotStore(private val store: KeyValueStore) {

    fun load(): SystemLocaleSnapshot? =
        SystemLocaleSnapshots.decode(store.get(KEY)).takeIf { it != null }

    /**
     * Stores [rawTags] only when no valid snapshot exists yet. Returns the snapshot that is
     * now stored, or null when [rawTags] could not be normalized into one.
     */
    fun capture(rawTags: List<String>, createdAt: Long): SystemLocaleSnapshot? {
        load()?.let { return it }
        val snapshot = SystemLocaleSnapshots.create(rawTags, createdAt) ?: return null
        store.put(KEY, SystemLocaleSnapshots.encode(snapshot))
        return load()
    }

    private companion object {
        const val KEY = "original_system_locales"
    }
}
