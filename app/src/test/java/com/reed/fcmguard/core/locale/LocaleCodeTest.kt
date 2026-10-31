package com.reed.fcmguard.core.locale

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocaleCodeTest {

    @Test
    fun `language and region are canonicalized`() {
        assertEquals("en-US", LocaleCode.normalize("en-us"))
        assertEquals("en-US", LocaleCode.normalize("EN-US"))
        assertEquals("vi-VN", LocaleCode.normalize("vi-vn"))
        assertEquals("ja-JP", LocaleCode.normalize("ja-jp"))
    }

    @Test
    fun `language-only and region-only forms stay as given`() {
        assertEquals("vi", LocaleCode.normalize("vi"))
        assertEquals("zh-TW", LocaleCode.normalize("zh-tw"))
        assertEquals("es-419", LocaleCode.normalize("es-419"))
    }

    @Test
    fun `script subtags are preserved and canonicalized`() {
        assertEquals("zh-Hans-CN", LocaleCode.normalize("zh-hans-cn"))
        assertEquals("zh-Hant-TW", LocaleCode.normalize("ZH-HANT-TW"))
        assertEquals("sr-Latn-RS", LocaleCode.normalize("sr-latn-rs"))
    }

    @Test
    fun `malformed input is rejected rather than guessed`() {
        assertNull(LocaleCode.normalize(null))
        assertNull(LocaleCode.normalize(""))
        assertNull(LocaleCode.normalize("   "))
        assertNull(LocaleCode.normalize("e"))
        assertNull(LocaleCode.normalize("123"))
        assertNull(LocaleCode.normalize("en--US"))
        assertNull(LocaleCode.normalize("en_US"), )
        assertNull(LocaleCode.normalize("en-US-"))
        assertNull(LocaleCode.normalize("und"))
        assertNull(LocaleCode.normalize("!!!"))
        assertNull(LocaleCode.normalize("a".repeat(LocaleCode.MAX_LENGTH + 1)))
    }

    @Test
    fun `validity matches normalization`() {
        assertTrue(LocaleCode.isValid("vi-VN"))
        assertFalse(LocaleCode.isValid("vi_VN"))
    }

    @Test
    fun `same language ignores region but respects script`() {
        assertTrue(LocaleCode.sameLanguage("en-US", "en-GB"))
        assertTrue(LocaleCode.sameLanguage("zh-Hans-CN", "zh-Hans-SG"))
        assertFalse(LocaleCode.sameLanguage("zh-Hans-CN", "zh-Hant-TW"))
        assertFalse(LocaleCode.sameLanguage("en", "vi"))
        assertFalse(LocaleCode.sameLanguage(null, "en"))
    }

    @Test
    fun `display names fall back to the tag`() {
        assertEquals("Tiếng Việt", LocaleCode.nativeName("vi"))
        assertTrue(LocaleCode.displayName("vi-VN").isNotEmpty())
        assertEquals("not a tag", LocaleCode.displayName("not a tag"))
    }
}
