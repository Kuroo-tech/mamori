package com.reed.fcmguard.core.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogRingTest {

    private fun entry(index: Int) = LogEntry(
        timestamp = index.toLong(),
        level = LogLevel.INFO,
        tag = "t",
        message = "m$index"
    )

    @Test
    fun `keeps insertion order`() {
        val ring = LogRing(3)
        ring.add(entry(1))
        ring.add(entry(2))
        ring.add(entry(3))

        assertEquals(listOf("m1", "m2", "m3"), ring.snapshot().map { it.message })
    }

    @Test
    fun `evicts the oldest entry at capacity`() {
        val ring = LogRing(3)
        repeat(5) { ring.add(entry(it)) }

        assertEquals(listOf("m2", "m3", "m4"), ring.snapshot().map { it.message })
        assertEquals(3, ring.size())
    }

    @Test
    fun `clear empties the ring`() {
        val ring = LogRing(2)
        ring.add(entry(1))
        ring.clear()

        assertTrue(ring.snapshot().isEmpty())
        assertEquals(0, ring.size())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects a non-positive capacity`() {
        LogRing(0)
    }

    @Test
    fun `format is one line and carries level, tag and message`() {
        val line = entry(0).format()

        assertTrue(line.contains("INFO"))
        assertTrue(line.contains("t:"))
        assertTrue(line.endsWith("m0"))
        assertEquals(1, line.lines().size)
    }
}
