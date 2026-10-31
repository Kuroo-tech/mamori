package com.reed.fcmguard

import android.content.res.Configuration
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies that `values-vi` is actually packaged and resolves, independent of the device
 * language. Locale-tag mapping is covered by `LocaleTagTest` on the JVM; this only checks
 * the resources.
 */
@RunWith(AndroidJUnit4::class)
class VietnameseResourcesTest {

    private fun localized(locale: Locale) = with(
        InstrumentationRegistry.getInstrumentation().targetContext
    ) {
        val config = Configuration(resources.configuration)
        config.setLocale(locale)
        createConfigurationContext(config)
    }

    @Test
    fun vietnameseStringsResolveWithCorrectDiacritics() {
        val vi = localized(Locale.forLanguageTag("vi"))

        assertEquals("Trang chủ", vi.getString(R.string.nav_home))
        assertEquals("Lịch sử thao tác", vi.getString(R.string.operation_history))
        assertEquals("Chẩn đoán", vi.getString(R.string.diagnostics_title))
        assertEquals("Bản sao lưu đã xác minh", vi.getString(R.string.diagnostics_snapshots))
    }

    @Test
    fun vietnameseAndEnglishDiffer() {
        val vi = localized(Locale.forLanguageTag("vi"))
        val en = localized(Locale.forLanguageTag("en"))

        assertNotEquals(vi.getString(R.string.nav_settings), en.getString(R.string.nav_settings))
        assertEquals("Settings", en.getString(R.string.nav_settings))
    }
}
