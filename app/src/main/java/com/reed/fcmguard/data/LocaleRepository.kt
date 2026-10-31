package com.reed.fcmguard.data

import android.graphics.drawable.Drawable
import com.reed.fcmguard.core.locale.LocaleCapabilities
import com.reed.fcmguard.core.locale.SystemLocaleSnapshot

/** Where the reported system locale list came from. Never inferred. */
enum class SystemLocaleSource { LOCALE_MANAGER, PLATFORM_DEFAULT, UNAVAILABLE }

data class SystemLocaleState(
    val primary: String?,
    val all: List<String>,
    val source: SystemLocaleSource
)

/**
 * What is actually known about another app's language. [SystemDefault] means the platform
 * confirmed an empty override; [NotReadable] means the platform refused to tell us, which
 * is not the same thing and must never be displayed as if it were.
 */
sealed interface AppLocaleState {
    data class Override(val tag: String) : AppLocaleState
    data object SystemDefault : AppLocaleState
    data object NotReadable : AppLocaleState
    data object Unsupported : AppLocaleState
}

data class InstalledAppLocale(
    val packageName: String,
    val label: String,
    val isSelf: Boolean,
    val state: AppLocaleState
)

data class AppDiscovery(
    val apps: List<InstalledAppLocale>,
    val restricted: Boolean,
    val otherAppLocalesReadable: Boolean
)

sealed interface LocaleWriteResult {
    data class Applied(val tag: String?) : LocaleWriteResult

    /**
     * The system-locale store accepted the value but the running configuration did not
     * change within the verification window. Some builds only apply a stored system
     * locale on the next boot, so this is reported separately and never as success.
     */
    data class PendingRestart(val tag: String) : LocaleWriteResult
    data class Unsupported(val reason: String) : LocaleWriteResult
    data class Rejected(val reason: String) : LocaleWriteResult
    data class VerificationFailed(val expected: String?, val actual: String?) : LocaleWriteResult
}

/**
 * Locale data seam.
 *
 * Writes here are of two kinds, both verified by reading the value back:
 *
 * - Mamori's own application locale, through `LocaleManager` (API 33+) or the legacy
 *   preference path.
 * - The system locale list, through the platform's system-locale store with the
 *   user-granted WRITE_SETTINGS access, then checked against
 *   `LocaleManager.getSystemLocales()`. There is no public `setSystemLocales` for apps, so
 *   the store is the only real mechanism; a write that the running configuration does not
 *   pick up is reported as [LocaleWriteResult.PendingRestart], never as success.
 *
 * Other apps' locales are read-only: Android has no public API to write them.
 */
interface LocaleRepository {
    suspend fun capabilities(): LocaleCapabilities
    suspend fun readSystemLocales(): SystemLocaleState
    /** The saved pre-change system locale list, if one was captured. */
    suspend fun systemLocaleSnapshot(): SystemLocaleSnapshot?
    suspend fun setSystemLocale(tag: String): LocaleWriteResult
    suspend fun restoreSystemLocale(): LocaleWriteResult
    suspend fun readOwnLocaleOverride(): String?
    suspend fun setOwnLocale(tag: String): LocaleWriteResult
    suspend fun clearOwnLocale(): LocaleWriteResult
    suspend fun discoverApps(): AppDiscovery
    suspend fun icon(packageName: String): Drawable?
}
