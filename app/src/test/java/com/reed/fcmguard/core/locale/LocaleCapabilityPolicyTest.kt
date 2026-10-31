package com.reed.fcmguard.core.locale

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocaleCapabilityPolicyTest {

    @Test
    fun `system and package reads are always available`() {
        val old = LocaleCapabilityPolicy.evaluate(sdkInt = 21, holdsChangeConfigurationPermission = false)

        assertTrue(old.can(LocaleCapability.READ_SYSTEM_LOCALES))
        assertTrue(old.can(LocaleCapability.ENUMERATE_INSTALLED_APPS))
    }

    @Test
    fun `own app locale needs API 33`() {
        val old = LocaleCapabilityPolicy.evaluate(sdkInt = 32, holdsChangeConfigurationPermission = false)
        val modern = LocaleCapabilityPolicy.evaluate(sdkInt = 33, holdsChangeConfigurationPermission = false)

        assertFalse(old.can(LocaleCapability.READ_OWN_APP_LOCALES))
        assertFalse(old.can(LocaleCapability.WRITE_OWN_APP_LOCALES))
        assertTrue(modern.can(LocaleCapability.READ_OWN_APP_LOCALES))
        assertTrue(modern.can(LocaleCapability.WRITE_OWN_APP_LOCALES))
    }

    @Test
    fun `reading another app locale requires API 35`() {
        assertFalse(
            LocaleCapabilityPolicy.evaluate(sdkInt = 34, holdsChangeConfigurationPermission = false)
                .can(LocaleCapability.READ_OTHER_APP_LOCALES)
        )
        assertTrue(
            LocaleCapabilityPolicy.evaluate(sdkInt = 35, holdsChangeConfigurationPermission = false)
                .can(LocaleCapability.READ_OTHER_APP_LOCALES)
        )
    }

    /**
     * Writing another app's language has no public API and no settings store the framework
     * would honour, so no privilege combination grants it here.
     */
    @Test
    fun `other-app writes are never granted`() {
        for (sdk in listOf(21, 33, 35, 36)) {
            for (changeConfiguration in listOf(false, true)) {
                for (writeSettings in listOf(false, true)) {
                    val capabilities = LocaleCapabilityPolicy.evaluate(
                        sdkInt = sdk,
                        holdsChangeConfigurationPermission = changeConfiguration,
                        holdsWriteSettings = writeSettings
                    )
                    assertFalse(
                        "sdk=$sdk changeConfiguration=$changeConfiguration writeSettings=$writeSettings",
                        capabilities.can(LocaleCapability.WRITE_OTHER_APP_LOCALES)
                    )
                }
            }
        }
    }

    /**
     * The system locale is written through the platform's own store, which needs the
     * user-granted WRITE_SETTINGS access, and is only verifiable from API 33 (where
     * `LocaleManager.getSystemLocales()` is available). Without the grant it never appears.
     */
    @Test
    fun `system locale writes need API 33 and modify-system-settings access`() {
        assertFalse(
            LocaleCapabilityPolicy.evaluate(35, false, holdsWriteSettings = false)
                .can(LocaleCapability.WRITE_SYSTEM_LOCALES)
        )
        assertFalse(
            LocaleCapabilityPolicy.evaluate(32, false, holdsWriteSettings = true)
                .can(LocaleCapability.WRITE_SYSTEM_LOCALES)
        )
        assertTrue(
            LocaleCapabilityPolicy.evaluate(33, false, holdsWriteSettings = true)
                .can(LocaleCapability.WRITE_SYSTEM_LOCALES)
        )
        assertTrue(
            LocaleCapabilityPolicy.evaluate(35, false, holdsWriteSettings = true)
                .can(LocaleCapability.WRITE_SYSTEM_LOCALES)
        )
    }

    @Test
    fun `change configuration permission is reported but grants nothing by itself`() {
        val capabilities = LocaleCapabilityPolicy.evaluate(
            sdkInt = 35,
            holdsChangeConfigurationPermission = true
        )

        assertTrue(capabilities.holdsChangeConfigurationPermission)
        assertFalse(capabilities.holdsWriteSettings)
        assertFalse(capabilities.can(LocaleCapability.WRITE_SYSTEM_LOCALES))
        assertFalse(capabilities.can(LocaleCapability.WRITE_OTHER_APP_LOCALES))
    }
}
