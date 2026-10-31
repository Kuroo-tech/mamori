package com.reed.fcmguard.data

import android.app.LocaleManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.LocaleList
import android.os.SystemClock
import android.provider.Settings
import com.reed.fcmguard.LocaleHelper
import com.reed.fcmguard.LocaleTag
import com.reed.fcmguard.core.locale.LocaleCapabilities
import com.reed.fcmguard.core.locale.LocaleCapabilityPolicy
import com.reed.fcmguard.core.locale.LocaleCode
import com.reed.fcmguard.core.locale.SupportedAppLocales
import com.reed.fcmguard.core.locale.SystemLocaleSnapshot
import com.reed.fcmguard.core.locale.SystemLocaleSnapshotStore
import com.reed.fcmguard.core.locale.SystemLocaleSnapshots
import com.reed.fcmguard.core.log.MamoriLog
import com.reed.fcmguard.execution.PreferencesKeyValueStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Android implementation of [LocaleRepository].
 *
 * Reads:
 * - system locales: `LocaleManager.getSystemLocales()` on 33+, `LocaleList.getDefault()`
 *   before that.
 * - Mamori's own override: `LocaleManager.getApplicationLocales()` through
 *   [LocaleHelper.getLanguage], with the legacy preference on older versions.
 * - another app's override: `LocaleManager.getApplicationLocales(packageName)` on API 35+,
 *   attempted and reported honestly when the platform refuses.
 *
 * Writes:
 * - Mamori's own locale through `LocaleManager.setApplicationLocales` (API 33+) or the
 *   established legacy preference path, then read back.
 * - The system locale list through `Settings.System` key `system_locales`, which is where
 *   the framework keeps it, using the user-granted WRITE_SETTINGS access. There is no
 *   public `setSystemLocales`, so this store is the mechanism; the write is verified twice
 *   (the store keeps the value, and `LocaleManager.getSystemLocales()` reflects it) and a
 *   store-only change is reported as pending restart instead of success.
 */
class AndroidLocaleRepository(context: Context) : LocaleRepository {

    private val app: Context = context.applicationContext

    private val snapshotStore = SystemLocaleSnapshotStore(
        PreferencesKeyValueStore(
            app.getSharedPreferences(PREFS_LOCALE_STATE, Context.MODE_PRIVATE)
        )
    )

    private val iconCache = object : LinkedHashMap<String, Drawable?>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Drawable?>?): Boolean =
            size > ICON_CACHE_LIMIT
    }

    override suspend fun capabilities(): LocaleCapabilities = withContext(Dispatchers.IO) {
        val holdsChangeConfiguration = try {
            app.packageManager.checkPermission(
                PERMISSION_CHANGE_CONFIGURATION,
                app.packageName
            ) == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
        LocaleCapabilityPolicy.evaluate(
            sdkInt = Build.VERSION.SDK_INT,
            holdsChangeConfigurationPermission = holdsChangeConfiguration,
            holdsWriteSettings = canWriteSettings()
        )
    }

    override suspend fun readSystemLocales(): SystemLocaleState = withContext(Dispatchers.IO) {
        val fromManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                app.getSystemService(LocaleManager::class.java)?.systemLocales?.let(::tagsOf)
            } catch (t: Throwable) {
                MamoriLog.warn(TAG, "reading system locales through LocaleManager failed", t)
                null
            }
        } else {
            null
        }

        val state = if (!fromManager.isNullOrEmpty()) {
            SystemLocaleState(fromManager.first(), fromManager, SystemLocaleSource.LOCALE_MANAGER)
        } else {
            val fallback = tagsOf(LocaleList.getDefault())
            if (fallback.isEmpty()) {
                SystemLocaleState(null, emptyList(), SystemLocaleSource.UNAVAILABLE)
            } else {
                SystemLocaleState(
                    fallback.first(),
                    fallback,
                    SystemLocaleSource.PLATFORM_DEFAULT
                )
            }
        }

        if (state.primary == null) {
            MamoriLog.warn(TAG, "system locale list is unavailable")
        } else {
            MamoriLog.info(TAG, "system locales: ${state.all.joinToString(",")} (${state.source})")
        }
        state
    }

    override suspend fun systemLocaleSnapshot(): SystemLocaleSnapshot? = withContext(Dispatchers.IO) {
        snapshotStore.load()
    }

    override suspend fun setSystemLocale(tag: String): LocaleWriteResult =
        withContext(Dispatchers.IO) {
            val normalized = LocaleCode.normalize(tag)
                ?: return@withContext LocaleWriteResult.Unsupported("invalid BCP 47 tag: $tag")
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                // Without LocaleManager.getSystemLocales() there is no way to verify, and an
                // unverifiable write is not one Mamori is willing to perform.
                return@withContext LocaleWriteResult.Unsupported("system locale writes need API 33")
            }
            if (!canWriteSettings()) {
                return@withContext LocaleWriteResult.Rejected("WRITE_SETTINGS not granted")
            }

            val current = readSystemLocales()
            val currentTags = current.all

            // Snapshot the pre-change list before the very first write. The store refuses to
            // overwrite an existing snapshot, so later changes cannot destroy the original.
            snapshotStore.capture(currentTags, System.currentTimeMillis())?.let { saved ->
                MamoriLog.info(TAG, "original system locale snapshot: ${saved.tags.joinToString(",")}")
            }

            // The new language becomes primary; the user's existing fallbacks are kept, in
            // order, so a partial match still resolves the way it did before.
            val target = listOf(normalized) + currentTags.filterNot { it == normalized }
            MamoriLog.info(
                TAG,
                "system locale change requested: ${currentTags.joinToString(",")} -> " +
                    target.joinToString(",")
            )
            writeSystemLocales(target, normalized)
        }

    override suspend fun restoreSystemLocale(): LocaleWriteResult = withContext(Dispatchers.IO) {
        val snapshot = snapshotStore.load()
        if (snapshot == null) {
            MamoriLog.warn(TAG, "system locale restore requested without a snapshot")
            return@withContext LocaleWriteResult.Unsupported("no original system locale snapshot")
        }
        if (!SystemLocaleSnapshots.verify(snapshot)) {
            MamoriLog.warn(TAG, "system locale restore refused: snapshot failed verification")
            return@withContext LocaleWriteResult.Unsupported("snapshot integrity check failed")
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return@withContext LocaleWriteResult.Unsupported("system locale writes need API 33")
        }
        if (!canWriteSettings()) {
            return@withContext LocaleWriteResult.Rejected("WRITE_SETTINGS not granted")
        }

        MamoriLog.info(
            TAG,
            "system locale restore requested: ${readSystemLocales().all.joinToString(",")} -> " +
                snapshot.tags.joinToString(",")
        )
        writeSystemLocales(snapshot.tags, snapshot.primary)
    }

    override suspend fun readOwnLocaleOverride(): String? = withContext(Dispatchers.IO) {
        readOwnLocaleOverrideBlocking()
    }

    override suspend fun setOwnLocale(tag: String): LocaleWriteResult = withContext(Dispatchers.IO) {
        val normalized = LocaleCode.normalize(tag)
            ?: return@withContext LocaleWriteResult.Unsupported("invalid BCP 47 tag: $tag")
        if (!SupportedAppLocales.isSupported(normalized)) {
            return@withContext LocaleWriteResult.Unsupported(
                "not declared in locales_config: $normalized"
            )
        }

        MamoriLog.info(TAG, "app locale override requested: $normalized")
        val result = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            writeLegacyPreference(normalized)
        } else {
            writeLocaleManager(normalized)
        }
        logResult("app locale override", result)
        result
    }

    override suspend fun clearOwnLocale(): LocaleWriteResult = withContext(Dispatchers.IO) {
        MamoriLog.info(TAG, "app locale reset requested: SYSTEM_DEFAULT")
        val result = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            LocaleHelper.setLanguage(app, LocaleTag.SYSTEM)
            verifyOwn(null)
        } else {
            try {
                val manager = app.getSystemService(LocaleManager::class.java)
                    ?: return@withContext LocaleWriteResult.Unsupported("LocaleManager unavailable")
                manager.applicationLocales = LocaleList.getEmptyLocaleList()
                verifyOwn(null)
            } catch (t: Throwable) {
                reject(t)
            }
        }
        logResult("app locale reset", result)
        result
    }

    override suspend fun discoverApps(): AppDiscovery = withContext(Dispatchers.IO) {
        val packageManager = app.packageManager
        val infos = try {
            packageManager.getInstalledApplications(0)
        } catch (t: Throwable) {
            MamoriLog.warn(TAG, "installed application query failed", t)
            return@withContext AppDiscovery(emptyList(), restricted = true, otherAppLocalesReadable = false)
        }

        val canProbeOtherApps = Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM
        var probeBlocked = false
        var readable = false

        val apps = ArrayList<InstalledAppLocale>(infos.size)
        for (info in infos) {
            val packageName = info.packageName
            val isSelf = packageName == app.packageName
            if (!isSelf && !isUserVisible(info)) continue

            val label = try {
                packageManager.getApplicationLabel(info)?.toString()?.trim()
                    ?.takeIf { it.isNotEmpty() } ?: packageName
            } catch (_: Throwable) {
                packageName
            }

            val state = when {
                isSelf -> readOwnLocaleOverrideBlocking()
                    ?.let { AppLocaleState.Override(it) }
                    ?: AppLocaleState.SystemDefault

                !canProbeOtherApps -> AppLocaleState.Unsupported

                probeBlocked -> AppLocaleState.NotReadable

                else -> when (val probe = probeOtherApp(packageName)) {
                    is Probe.Readable -> {
                        readable = true
                        probe.state
                    }
                    Probe.Denied -> {
                        probeBlocked = true
                        AppLocaleState.NotReadable
                    }
                    Probe.Failed -> AppLocaleState.NotReadable
                }
            }

            apps.add(InstalledAppLocale(packageName, label, isSelf, state))
        }

        val sorted = apps.sortedWith(
            compareBy<InstalledAppLocale> { it.label.lowercase() }.thenBy { it.packageName }
        )
        MamoriLog.info(
            TAG,
            "package discovery: ${sorted.size} apps, other-app locales readable=$readable"
        )
        AppDiscovery(sorted, restricted = false, otherAppLocalesReadable = readable)
    }

    override suspend fun icon(packageName: String): Drawable? = withContext(Dispatchers.IO) {
        synchronized(iconCache) {
            if (iconCache.containsKey(packageName)) return@synchronized iconCache[packageName]
        }
        val loaded = try {
            app.packageManager.getApplicationIcon(packageName)
        } catch (_: Throwable) {
            null
        }
        synchronized(iconCache) { iconCache[packageName] = loaded }
        loaded
    }

    // --- system locale ------------------------------------------------------

    private fun canWriteSettings(): Boolean = try {
        Settings.System.canWrite(app)
    } catch (_: Throwable) {
        false
    }

    /**
     * Writes the system locale list through the platform's store and verifies it twice.
     *
     * Level 1: the store keeps what was written (otherwise the platform refused it).
     * Level 2: the running configuration reports the new primary locale. When level 2 never
     * happens the result is [LocaleWriteResult.PendingRestart], because several builds only
     * pick a stored system locale up at the next boot. Storing is not applying, so it is
     * never reported as applied.
     */
    private suspend fun writeSystemLocales(target: List<String>, expectedPrimary: String): LocaleWriteResult {
        val value = target.joinToString(",")
        val accepted = try {
            Settings.System.putString(app.contentResolver, KEY_SYSTEM_LOCALES, value)
        } catch (t: Throwable) {
            MamoriLog.warn(TAG, "system locale write failed", t)
            return reject(t)
        }
        if (!accepted) {
            MamoriLog.warn(TAG, "system locale write rejected by the settings provider")
            return LocaleWriteResult.Rejected("settings provider rejected the write")
        }

        val stored = try {
            Settings.System.getString(app.contentResolver, KEY_SYSTEM_LOCALES)
        } catch (_: Throwable) {
            null
        }
        val storedTags = parseTags(stored)
        if (storedTags != target) {
            MamoriLog.warn(TAG, "system locale store did not keep the value: ${stored ?: "null"}")
            return LocaleWriteResult.VerificationFailed(expectedPrimary, storedTags.firstOrNull())
        }

        val applied = awaitSystemPrimary(expectedPrimary)
        return if (applied) {
            MamoriLog.info(TAG, "system locale change verified: $expectedPrimary")
            LocaleWriteResult.Applied(expectedPrimary)
        } else {
            MamoriLog.warn(
                TAG,
                "system locale stored but not active yet (build may need a restart): $expectedPrimary"
            )
            LocaleWriteResult.PendingRestart(expectedPrimary)
        }
    }

    /** Polls the running configuration so a slow apply is not mistaken for a failure. */
    private suspend fun awaitSystemPrimary(expected: String): Boolean {
        val deadline = SystemClock.elapsedRealtime() + VERIFY_TIMEOUT_MS
        while (true) {
            val primary = currentPrimaryTag()
            if (primary != null && LocaleCode.normalize(primary) == expected) return true
            if (SystemClock.elapsedRealtime() >= deadline) return false
            delay(VERIFY_POLL_MS)
        }
    }

    /** Quiet read used by the polling loop: one operation must not log eight times. */
    private fun currentPrimaryTag(): String? = try {
        val manager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            app.getSystemService(LocaleManager::class.java)
        } else {
            null
        }
        tagsOf(manager?.systemLocales ?: LocaleList.getDefault()).firstOrNull()
    } catch (_: Throwable) {
        null
    }

    private fun parseTags(raw: String?): List<String> =
        raw.orEmpty()
            .split(',')
            .mapNotNull { LocaleCode.normalize(it) }
            .filter { it.isNotEmpty() }

    // --- internal -----------------------------------------------------------

    private fun readOwnLocaleOverrideBlocking(): String? {
        val code = LocaleHelper.getLanguage(app)
        return if (code == LocaleTag.SYSTEM) null else code
    }

    private fun writeLocaleManager(tag: String): LocaleWriteResult = try {
        val manager = app.getSystemService(LocaleManager::class.java)
            ?: return LocaleWriteResult.Unsupported("LocaleManager unavailable")
        manager.applicationLocales = LocaleList.forLanguageTags(tag)
        verifyOwn(tag)
    } catch (t: Throwable) {
        reject(t)
    }

    /** Legacy path: the preference read back is the same value `attachBaseContext` uses. */
    private fun writeLegacyPreference(tag: String): LocaleWriteResult {
        LocaleHelper.setLanguage(app, tag)
        return verifyOwn(tag)
    }

    private fun verifyOwn(expected: String?): LocaleWriteResult {
        val actual = readOwnLocaleOverrideBlocking()
        return if (actual == expected) {
            LocaleWriteResult.Applied(expected)
        } else {
            LocaleWriteResult.VerificationFailed(expected, actual)
        }
    }

    private fun reject(t: Throwable): LocaleWriteResult {
        MamoriLog.warn(TAG, "locale write failed", t)
        return when (t) {
            is SecurityException -> LocaleWriteResult.Rejected("permission denied")
            is IllegalArgumentException -> LocaleWriteResult.Rejected("unsupported locale")
            else -> LocaleWriteResult.Rejected(t.javaClass.simpleName)
        }
    }

    private fun logResult(what: String, result: LocaleWriteResult) {
        when (result) {
            is LocaleWriteResult.Applied ->
                MamoriLog.info(TAG, "$what succeeded: ${result.tag ?: "SYSTEM_DEFAULT"}")
            is LocaleWriteResult.PendingRestart ->
                MamoriLog.warn(TAG, "$what stored, pending restart: ${result.tag}")
            is LocaleWriteResult.Unsupported ->
                MamoriLog.warn(TAG, "$what unsupported: ${result.reason}")
            is LocaleWriteResult.Rejected ->
                MamoriLog.warn(TAG, "$what rejected: ${result.reason}")
            is LocaleWriteResult.VerificationFailed ->
                MamoriLog.warn(
                    TAG,
                    "$what unverified: expected=${result.expected ?: "SYSTEM_DEFAULT"} " +
                        "actual=${result.actual ?: "SYSTEM_DEFAULT"}"
                )
        }
    }

    private fun probeOtherApp(packageName: String): Probe {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return Probe.Failed
        return try {
            val manager = app.getSystemService(LocaleManager::class.java)
                ?: return Probe.Failed
            val locales = manager.getApplicationLocales(packageName)
            val tags = tagsOf(locales)
            Probe.Readable(
                if (tags.isEmpty()) AppLocaleState.SystemDefault
                else AppLocaleState.Override(tags.first())
            )
        } catch (e: SecurityException) {
            MamoriLog.warnOnce(
                "read-other-app-locales",
                TAG,
                "reading other apps' locales requires READ_APP_SPECIFIC_LOCALES",
                e
            )
            Probe.Denied
        } catch (_: Throwable) {
            Probe.Failed
        }
    }

    private fun isUserVisible(info: ApplicationInfo): Boolean {
        val flags = info.flags
        val system = (flags and ApplicationInfo.FLAG_SYSTEM) != 0
        val updatedSystem = (flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        return !system || updatedSystem
    }

    private fun tagsOf(locales: LocaleList): List<String> =
        locales.toLanguageTags()
            .split(',')
            .mapNotNull { raw ->
                val trimmed = raw.trim()
                if (trimmed.isEmpty()) null else LocaleCode.normalize(trimmed) ?: trimmed
            }
            .distinct()

    private sealed interface Probe {
        data class Readable(val state: AppLocaleState) : Probe
        data object Denied : Probe
        data object Failed : Probe
    }

    private companion object {
        const val TAG = "LocaleRepository"
        const val ICON_CACHE_LIMIT = 256
        const val PERMISSION_CHANGE_CONFIGURATION = "android.permission.CHANGE_CONFIGURATION"
        const val PREFS_LOCALE_STATE = "locale_state"
        /** The framework's own system-locale store; there is no public setter for it. */
        const val KEY_SYSTEM_LOCALES = "system_locales"
        const val VERIFY_TIMEOUT_MS = 8_000L
        const val VERIFY_POLL_MS = 400L
    }
}
