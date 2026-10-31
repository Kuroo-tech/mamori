package com.reed.fcmguard.core.locale

import com.reed.fcmguard.execution.MemoryKeyValueStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemLocaleSnapshotTest {

    @Test
    fun `create normalizes tags and keeps their order`() {
        val snapshot = SystemLocaleSnapshots.create(listOf("vi-vn", "en-us", "vi-VN"), 1_000L)

        assertNotNull(snapshot)
        assertEquals(listOf("vi-VN", "en-US"), snapshot!!.tags)
        assertEquals("vi-VN", snapshot.primary)
        assertTrue(SystemLocaleSnapshots.verify(snapshot))
    }

    @Test
    fun `create rejects input with nothing usable`() {
        assertNull(SystemLocaleSnapshots.create(emptyList(), 1_000L))
        assertNull(SystemLocaleSnapshots.create(listOf("", "und", "not a tag!", "--"), 1_000L))
    }

    @Test
    fun `encode decode round trips`() {
        val snapshot = SystemLocaleSnapshots.create(listOf("ja-JP", "en-US"), 42L)!!
        val decoded = SystemLocaleSnapshots.decode(SystemLocaleSnapshots.encode(snapshot))

        assertEquals(snapshot, decoded)
    }

    @Test
    fun `tampered payload does not decode`() {
        val snapshot = SystemLocaleSnapshots.create(listOf("ja-JP", "en-US"), 42L)!!
        val encoded = SystemLocaleSnapshots.encode(snapshot)
        val tampered = encoded.replace("ja-JP", "ko-KR")

        assertNull(SystemLocaleSnapshots.decode(tampered))
        assertNull(SystemLocaleSnapshots.decode(encoded.replace("|42|", "|43|")))
    }

    @Test
    fun `garbage does not decode`() {
        assertNull(SystemLocaleSnapshots.decode(null))
        assertNull(SystemLocaleSnapshots.decode(""))
        assertNull(SystemLocaleSnapshots.decode("1|42"))
        assertNull(SystemLocaleSnapshots.decode("9|42|ja-JP|deadbeef"))
    }

    @Test
    fun `verify rejects an empty or non canonical tag list`() {
        val snapshot = SystemLocaleSnapshots.create(listOf("vi-VN"), 7L)!!
        assertFalse(SystemLocaleSnapshots.verify(snapshot.copy(tags = emptyList())))
        assertFalse(SystemLocaleSnapshots.verify(snapshot.copy(tags = listOf("vi-vn"))))
    }

    @Test
    fun `store keeps the first valid snapshot and never overwrites it`() {
        val store = SystemLocaleSnapshotStore(MemoryKeyValueStore())

        val first = store.capture(listOf("vi-VN", "en-US"), 100L)
        val second = store.capture(listOf("en-US"), 200L)

        assertEquals(first, second)
        assertEquals(listOf("vi-VN", "en-US"), store.load()?.tags)
    }

    @Test
    fun `store replaces an unreadable record`() {
        val backing = MemoryKeyValueStore()
        backing.put("original_system_locales", "garbage")
        val store = SystemLocaleSnapshotStore(backing)

        assertNull(store.load())
        assertEquals(listOf("vi-VN"), store.capture(listOf("vi-VN"), 100L)?.tags)
    }

    @Test
    fun `store captures nothing when the input is unusable`() {
        val store = SystemLocaleSnapshotStore(MemoryKeyValueStore())

        assertNull(store.capture(listOf("und"), 100L))
        assertNull(store.load())
    }
}
