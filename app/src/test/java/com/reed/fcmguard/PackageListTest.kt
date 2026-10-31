package com.reed.fcmguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks down the preserve/append rules for the HyperOS whitelist value. These are the
 * rules that must never silently change: existing packages keep their order, nothing
 * is removed, the required item is appended only when missing, and nothing is invented.
 */
class PackageListTest {

    @Test
    fun `parse trims, drops blanks and de-duplicates while preserving order`() {
        val parsed = PackageList.parse(" com.a , ,com.b,com.a,, com.c ")
        assertEquals(listOf("com.a", "com.b", "com.c"), parsed.toList())
    }

    @Test
    fun `parse handles null and empty values`() {
        assertTrue(PackageList.parse(null).isEmpty())
        assertTrue(PackageList.parse("   ").isEmpty())
        assertTrue(PackageList.parse(",,,").isEmpty())
    }

    @Test
    fun `join produces the single comma separated wire format`() {
        assertEquals("com.a,com.b", PackageList.join(listOf("com.a", "com.b")))
        assertEquals("", PackageList.join(emptyList()))
    }

    @Test
    fun `contains requires a complete package entry`() {
        assertTrue(PackageList.contains("com.a,com.google.android.gms", "com.google.android.gms"))
        assertTrue(PackageList.contains("com.a, com.google.android.gms", "com.google.android.gms"))
        assertFalse(PackageList.contains("com.google.android.gms.backup", "com.google.android.gms"))
        assertFalse(PackageList.contains("com.a", "com.google.android.gms"))
    }

    @Test
    fun `contains treats blank inputs as absent`() {
        assertFalse(PackageList.contains(null, "com.google.android.gms"))
        assertFalse(PackageList.contains("   ", "com.google.android.gms"))
        assertFalse(PackageList.contains("com.google.android.gms", null))
        assertFalse(PackageList.contains("com.google.android.gms", "  "))
    }

    @Test
    fun `buildRepaired preserves every existing package and appends the required item`() {
        val repaired = PackageList.buildRepaired(
            current = "com.vendor.one,com.vendor.two",
            lastGood = null,
            required = "com.google.android.gms"
        )
        assertEquals(
            listOf("com.vendor.one", "com.vendor.two", "com.google.android.gms"),
            repaired.toList()
        )
    }

    @Test
    fun `buildRepaired does not duplicate an already present required item`() {
        val repaired = PackageList.buildRepaired(
            current = "com.a,com.google.android.gms,com.b",
            lastGood = null,
            required = "com.google.android.gms"
        )
        assertEquals(listOf("com.a", "com.google.android.gms", "com.b"), repaired.toList())
    }

    @Test
    fun `buildRepaired falls back to the last known good value only when current is empty`() {
        val fromLastGood = PackageList.buildRepaired(
            current = null,
            lastGood = "com.last.good",
            required = "com.google.android.gms"
        )
        assertEquals(listOf("com.last.good", "com.google.android.gms"), fromLastGood.toList())

        val fromCurrent = PackageList.buildRepaired(
            current = "com.live",
            lastGood = "com.last.good",
            required = "com.google.android.gms"
        )
        assertEquals(listOf("com.live", "com.google.android.gms"), fromCurrent.toList())
    }

    @Test
    fun `buildRepaired invents nothing when both sources are empty`() {
        val repaired = PackageList.buildRepaired(
            current = null,
            lastGood = null,
            required = "com.google.android.gms"
        )
        assertEquals(listOf("com.google.android.gms"), repaired.toList())
    }

    @Test
    fun `buildRepaired trims the configured required item`() {
        val repaired = PackageList.buildRepaired(
            current = "com.a",
            lastGood = null,
            required = "  com.google.android.gms  "
        )
        assertEquals(listOf("com.a", "com.google.android.gms"), repaired.toList())
    }

    @Test
    fun `buildRepaired skips a blank required item`() {
        val repaired = PackageList.buildRepaired(
            current = "com.a",
            lastGood = null,
            required = "   "
        )
        assertEquals(listOf("com.a"), repaired.toList())
    }

    @Test
    fun `repair output round trips through parse and join unchanged`() {
        val repaired = PackageList.buildRepaired(
            current = "com.b,com.a",
            lastGood = null,
            required = "com.google.android.gms"
        )
        val joined = PackageList.join(repaired)
        assertEquals(joined, PackageList.join(PackageList.parse(joined)))
        assertEquals("com.b,com.a,com.google.android.gms", joined)
    }
}
