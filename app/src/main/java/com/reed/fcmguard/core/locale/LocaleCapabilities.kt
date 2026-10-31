package com.reed.fcmguard.core.locale

/** A single thing the language manager might be able to do on this device. */
enum class LocaleCapability {
    READ_SYSTEM_LOCALES,
    WRITE_SYSTEM_LOCALES,
    READ_OWN_APP_LOCALES,
    WRITE_OWN_APP_LOCALES,
    READ_OTHER_APP_LOCALES,
    WRITE_OTHER_APP_LOCALES,
    ENUMERATE_INSTALLED_APPS
}

data class LocaleCapabilities(
    val sdkInt: Int,
    val held: Set<LocaleCapability>,
    /**
     * Whether Mamori holds the signature-level CHANGE_CONFIGURATION permission. Kept for
     * the report and diagnostics only: holding it would still not expose a public API to
     * write the system locale, so it never grants [LocaleCapability.WRITE_SYSTEM_LOCALES].
     */
    val holdsChangeConfigurationPermission: Boolean,
    /** The user-granted "Modify system settings" access the system-locale path needs. */
    val holdsWriteSettings: Boolean = false
) {
    fun can(capability: LocaleCapability): Boolean = capability in held
}

/**
 * The capability model, kept pure so the decision table is unit-tested.
 *
 * It encodes platform facts, not hopes:
 *
 * - Reading the system locale is always possible (`LocaleManager.getSystemLocales()` on
 *   API 33+, `LocaleList.getDefault()` before that).
 * - Writing it has no public API: Android 15 offers no `setSystemLocales` to apps. The one
 *   mechanism available to Mamori is the platform's own system-locale store
 *   (`Settings.System` key `system_locales`, which the framework reads), written through
 *   the user-granted WRITE_SETTINGS access and then verified against the running
 *   configuration. That is why this capability tracks WRITE_SETTINGS and API 33 (where
 *   `LocaleManager.getSystemLocales()` becomes readable for verification) rather than
 *   CHANGE_CONFIGURATION.
 * - Mamori's own app locale is readable and writable from API 33 (`LocaleManager`).
 * - Another app's locale can only be *read* from API 35, and only if the caller holds the
 *   signature permission `READ_APP_SPECIFIC_LOCALES`; the call is attempted and its
 *   SecurityException is reported per app.
 * - Writing another app's locale has no public API and no settings-store equivalent the
 *   framework would honour, so it is never granted.
 * - Package enumeration is best-effort and may be filtered by the platform.
 */
object LocaleCapabilityPolicy {

    private const val API_TIRAMISU = 33
    private const val API_VANILLA_ICE_CREAM = 35

    fun evaluate(
        sdkInt: Int,
        holdsChangeConfigurationPermission: Boolean,
        holdsWriteSettings: Boolean = false
    ): LocaleCapabilities {
        val held = buildSet {
            add(LocaleCapability.READ_SYSTEM_LOCALES)
            add(LocaleCapability.ENUMERATE_INSTALLED_APPS)

            if (sdkInt >= API_TIRAMISU) {
                add(LocaleCapability.READ_OWN_APP_LOCALES)
                add(LocaleCapability.WRITE_OWN_APP_LOCALES)
                // The system-locale store is only verifiable through the API 33 reader.
                if (holdsWriteSettings) add(LocaleCapability.WRITE_SYSTEM_LOCALES)
            }
            if (sdkInt >= API_VANILLA_ICE_CREAM) {
                // The API exists; whether the caller may use it is checked per call.
                add(LocaleCapability.READ_OTHER_APP_LOCALES)
            }

            // WRITE_OTHER_APP_LOCALES is intentionally absent regardless of permissions or
            // API level: Android has no public API for it and no settings store the
            // framework would honour.
        }
        return LocaleCapabilities(
            sdkInt = sdkInt,
            held = held,
            holdsChangeConfigurationPermission = holdsChangeConfigurationPermission,
            holdsWriteSettings = holdsWriteSettings
        )
    }
}
