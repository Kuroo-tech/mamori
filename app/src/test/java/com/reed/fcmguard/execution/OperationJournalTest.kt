package com.reed.fcmguard.execution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OperationJournalTest {

    private val key = "MILLET_NO_RESTRICT_APP"

    private fun journal(store: KeyValueStore = MemoryKeyValueStore()) =
        SharedPreferencesOperationJournal(store)

    @Test
    fun `a started operation is active and not terminal`() {
        val journal = journal()

        val operation = journal.start("op-1", key, 10L)

        assertEquals(OperationState.VALIDATING, operation.state)
        assertEquals("op-1", journal.active()!!.id)
        assertTrue(journal.history().isEmpty())
    }

    @Test
    fun `advancing persists the state and snapshot reference`() {
        val journal = journal()
        val operation = journal.start("op-1", key, 10L)

        journal.advance(operation, OperationState.WRITING, 20L, "detail", "snap-1")

        val active = journal.active()!!
        assertEquals(OperationState.WRITING, active.state)
        assertEquals(20L, active.updatedAt)
        assertEquals("snap-1", active.snapshotId)
        assertEquals("detail", active.detail)
    }

    @Test
    fun `completing clears the active record and appends history`() {
        val journal = journal()
        val operation = journal.start("op-1", key, 10L)

        journal.complete(operation, OperationState.COMPLETED, 30L, null, "snap-1")

        assertNull(journal.active())
        val record = journal.history().single()
        assertEquals(OperationState.COMPLETED, record.state)
        assertEquals("snap-1", record.snapshotId)
    }

    @Test
    fun `completion requires a terminal state`() {
        val journal = journal()
        val operation = journal.start("op-1", key, 10L)

        val failed = runCatching { journal.complete(operation, OperationState.WRITING, 20L) }

        assertTrue(failed.isFailure)
        assertNotNull(journal.active())
    }

    @Test
    fun `newest history entries come first and the list is bounded`() {
        val bounded = SharedPreferencesOperationJournal(MemoryKeyValueStore(), historyLimit = 3)

        repeat(5) { index ->
            val operation = bounded.start("op-$index", key, index.toLong())
            bounded.complete(operation, OperationState.COMPLETED, index.toLong())
        }

        assertEquals(listOf("op-4", "op-3", "op-2"), bounded.history().map { it.id })
    }

    @Test
    fun `an abandoned operation becomes INTERRUPTED and stops blocking`() {
        val journal = journal()
        journal.start("op-1", key, 10L)

        val interrupted = journal.abandonInterrupted(99L)

        assertNotNull(interrupted)
        assertEquals(OperationState.INTERRUPTED, interrupted!!.state)
        assertNull(journal.active())
        assertEquals(OperationState.INTERRUPTED, journal.history().single().state)
    }

    @Test
    fun `abandoning with nothing pending is a no-op`() {
        val journal = journal()

        assertNull(journal.abandonInterrupted(99L))
        assertTrue(journal.history().isEmpty())
    }

    @Test
    fun `state survives a new journal instance over the same store`() {
        val store = MemoryKeyValueStore()
        journal(store).start("op-1", key, 10L)

        val reopened = journal(store)

        assertEquals("op-1", reopened.active()!!.id)
    }

    @Test
    fun `records with awkward text round trip through the codec`() {
        val store = MemoryKeyValueStore()
        val journal = journal(store)
        val operation = journal.start("op-1", "KEY\u0001with,commas and %percent", 10L)

        journal.complete(operation, OperationState.FAILED, 20L, "detail=with\u0002separators %20 ✓")

        val record = journal.history().single()
        assertEquals("KEY\u0001with,commas and %percent", record.key)
        assertEquals("detail=with\u0002separators %20 ✓", record.detail)
    }
}
