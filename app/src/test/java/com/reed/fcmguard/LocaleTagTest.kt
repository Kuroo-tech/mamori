package com.reed.fcmguard

import org.junit.Assert.assertEquals
import org.junit.Test

class LocaleTagTest {

    @Test
    fun `null blank and system all resolve to system`() {
        assertEquals(LocaleTag.SYSTEM, LocaleTag.normalize(null))
        assertEquals(LocaleTag.SYSTEM, LocaleTag.normalize(""))
        assertEquals(LocaleTag.SYSTEM, LocaleTag.normalize("   "))
        assertEquals(LocaleTag.SYSTEM, LocaleTag.normalize("system"))
        assertEquals(LocaleTag.SYSTEM, LocaleTag.normalize("SYSTEM"))
    }

    @Test
    fun `traditional chinese variants collapse to zh-TW`() {
        assertEquals("zh-TW", LocaleTag.normalize("zh-TW"))
        assertEquals("zh-TW", LocaleTag.normalize("zh-HK"))
        assertEquals("zh-TW", LocaleTag.normalize("zh-MO"))
        assertEquals("zh-TW", LocaleTag.normalize("zh-Hant-TW"))
    }

    @Test
    fun `simplified chinese and regional variants collapse to zh-CN`() {
        assertEquals("zh-CN", LocaleTag.normalize("zh-CN"))
        assertEquals("zh-CN", LocaleTag.normalize("zh"))
        assertEquals("zh-CN", LocaleTag.normalize("zh-Hans-CN"))
    }

    @Test
    fun `supported primary languages are preserved`() {
        assertEquals("en", LocaleTag.normalize("en-US"))
        assertEquals("fr", LocaleTag.normalize("fr-CA"))
        assertEquals("ja", LocaleTag.normalize("ja-JP"))
        assertEquals("ko", LocaleTag.normalize("ko-KR"))
        assertEquals("es", LocaleTag.normalize("es-ES"))
        assertEquals("pt", LocaleTag.normalize("pt-BR"))
        assertEquals("de", LocaleTag.normalize("de-AT"))
        assertEquals("ru", LocaleTag.normalize("ru-RU"))
    }

    @Test
    fun `unsupported languages fall back to system rather than a wrong locale`() {
        assertEquals(LocaleTag.SYSTEM, LocaleTag.normalize("tlh"))
        assertEquals(LocaleTag.SYSTEM, LocaleTag.normalize("ar-EG"))
    }

    /**
     * Documents current upstream behavior. Vietnamese becomes a supported locale in
     * Phase 4 (values-vi + locales_config + this mapping); this assertion is expected
     * to change then, deliberately and not silently.
     */
    @Test
    fun `vietnamese is not yet a supported locale`() {
        assertEquals(LocaleTag.SYSTEM, LocaleTag.normalize("vi-VN"))
    }
}
