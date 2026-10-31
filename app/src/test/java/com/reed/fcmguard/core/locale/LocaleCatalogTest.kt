package com.reed.fcmguard.core.locale

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocaleCatalogTest {

    private val choices = LocaleCatalog.systemChoices(Locale.US)

    @Test
    fun `preferred languages come first and are normalized`() {
        assertTrue(choices.isNotEmpty())
        assertEquals("vi-VN", choices.first().tag)
        assertEquals("en-US", choices[1].tag)
        assertEquals(choices.size, choices.map { it.tag }.distinct().size)
    }

    @Test
    fun `every catalog tag is a valid normalized BCP 47 tag`() {
        for (choice in choices) {
            assertEquals(choice.tag, LocaleCode.normalize(choice.tag))
            assertTrue(choice.displayName.isNotBlank())
            assertTrue(choice.nativeName.isNotBlank())
        }
    }

    @Test
    fun `catalog offers language-region combinations`() {
        val tags = choices.map { it.tag }
        assertTrue("en-GB" in tags)
        assertTrue("pt-BR" in tags)
        assertTrue("ja-JP" in tags)
    }

    @Test
    fun `search matches tag, display name and native name`() {
        assertEquals(
            listOf("ja-JP"),
            LocaleCatalog.filter(choices, "ja-jp").map { it.tag }
        )
        assertTrue(LocaleCatalog.filter(choices, "Tiếng Việt").any { it.tag == "vi-VN" })
        assertTrue(LocaleCatalog.filter(choices, "  ").size == choices.size)
        assertTrue(LocaleCatalog.filter(choices, "zzzz-nothing").isEmpty())
    }
}
