package com.reed.fcmguard.execution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SnapshotStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val key = "MILLET_NO_RESTRICT_APP"

    @Test
    fun `stores and reads back a snapshot with a valid digest`() {
        val store = SnapshotStore(temp.newFolder("snapshots"))

        val saved = store.save("op-1", key, "com.a,com.b", 1_700_000_000_000L)
        val loaded = store.find(saved.id)

        assertNotNull(loaded)
        assertEquals("com.a,com.b", loaded!!.value)
        assertEquals(key, loaded.key)
        assertEquals("op-1", loaded.operationId)
        assertTrue(store.verify(loaded))
    }

    @Test
    fun `a modified value fails verification`() {
        val store = SnapshotStore(temp.newFolder("snapshots"))
        val saved = store.save("op-1", key, "com.a", 1L)

        val tampered = saved.copy(value = "com.evil")

        assertFalse(store.verify(tampered))
    }

    @Test
    fun `an edited file is rejected as corrupt`() {
        val directory = temp.newFolder("snapshots")
        val store = SnapshotStore(directory)
        val saved = store.save("op-1", key, "com.a", 1L)

        val file = File(directory, "${saved.id}.snapshot")
        file.writeText(file.readText().replace("com.a", "com.evil"))

        val loaded = store.find(saved.id)
        assertNotNull(loaded)
        assertFalse(store.verify(loaded!!))
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun `latestFor returns the newest intact snapshot for a key`() {
        val store = SnapshotStore(temp.newFolder("snapshots"))
        store.save("op-1", key, "com.a", 100L)
        store.save("op-2", key, "com.a,com.b", 200L)
        store.save("op-3", "OTHER_KEY", "com.other", 300L)

        assertEquals("com.a,com.b", store.latestFor(key)!!.value)
        assertNull(store.latestFor("MISSING"))
    }

    @Test
    fun `only the most recent snapshots are kept`() {
        val store = SnapshotStore(temp.newFolder("snapshots"), limit = 2)

        repeat(4) { index ->
            store.save("op-$index", key, "com.a$index", 1_000L + index)
        }

        val remaining = store.all()
        assertEquals(2, remaining.size)
        assertEquals(listOf("com.a3", "com.a2"), remaining.map { it.value })
    }

    @Test
    fun `delete removes a snapshot`() {
        val store = SnapshotStore(temp.newFolder("snapshots"))
        val saved = store.save("op-1", key, "com.a", 1L)

        assertTrue(store.delete(saved.id))
        assertNull(store.find(saved.id))
        assertTrue(store.all().isEmpty())
    }
}
