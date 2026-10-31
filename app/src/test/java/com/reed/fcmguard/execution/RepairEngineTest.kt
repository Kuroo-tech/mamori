package com.reed.fcmguard.execution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RepairEngineTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val key = "MILLET_NO_RESTRICT_APP"
    private val required = "com.google.android.gms"

    private lateinit var gateway: FakeGateway
    private lateinit var snapshots: SnapshotStore
    private lateinit var journal: OperationJournal
    private lateinit var engine: RepairEngine
    private var ids = 0
    private var clock = 1_700_000_000_000L

    @Before
    fun setUp() {
        gateway = FakeGateway()
        snapshots = SnapshotStore(temp.newFolder("snapshots"))
        journal = SharedPreferencesOperationJournal(MemoryKeyValueStore())
        engine = RepairEngine(
            gateway = gateway,
            snapshots = snapshots,
            journal = journal,
            now = { clock },
            newId = { "op-${++ids}" }
        )
    }

    @Test
    fun `appends the required item without dropping existing packages and completes`() {
        gateway.values[key] = "com.bank.app,com.chat.app"

        val outcome = engine.repair(key, required, fallbackValue = null)

        assertTrue(outcome is RepairOutcome.Applied)
        val applied = outcome as RepairOutcome.Applied
        assertEquals("com.bank.app,com.chat.app,$required", applied.value)
        assertEquals("com.bank.app,com.chat.app", applied.previousValue)
        assertEquals(applied.value, gateway.values[key])

        val record = journal.history().first()
        assertEquals(OperationState.COMPLETED, record.state)
        assertNull(journal.active())
    }

    @Test
    fun `snapshots the previous value before writing`() {
        gateway.values[key] = "com.bank.app"

        val outcome = engine.repair(key, required, fallbackValue = null) as RepairOutcome.Applied

        val snapshotId = outcome.snapshotId
        assertNotNull(snapshotId)
        val snapshot = snapshots.find(snapshotId!!)!!
        assertEquals("com.bank.app", snapshot.value)
        assertTrue(snapshots.verify(snapshot))
    }

    @Test
    fun `does nothing when the required item is already present`() {
        gateway.values[key] = "com.bank.app,$required"

        val outcome = engine.repair(key, required, fallbackValue = null)

        assertTrue(outcome is RepairOutcome.AlreadyCorrect)
        assertEquals(0, gateway.writeCount)
        assertEquals(OperationState.COMPLETED, journal.history().first().state)
    }

    @Test
    fun `a second repair of the same value is idempotent`() {
        gateway.values[key] = "com.bank.app"
        engine.repair(key, required, fallbackValue = null)

        val second = engine.repair(key, required, fallbackValue = null)

        assertTrue(second is RepairOutcome.AlreadyCorrect)
        assertEquals(1, gateway.writeCount)
        assertEquals("com.bank.app,$required", gateway.values[key])
    }

    @Test
    fun `never writes without the write-settings permission`() {
        gateway.writable = false
        gateway.values[key] = "com.bank.app"

        val outcome = engine.repair(key, required, fallbackValue = null)

        assertTrue(outcome is RepairOutcome.PermissionMissing)
        assertEquals(0, gateway.writeCount)
        assertEquals("com.bank.app", gateway.values[key])
        assertTrue(journal.history().isEmpty())
        assertNull(journal.active())
    }

    @Test
    fun `reports a rejected write as a failure`() {
        gateway.values[key] = "com.bank.app"
        gateway.rejectWrites = true

        val outcome = engine.repair(key, required, fallbackValue = null)

        assertTrue(outcome is RepairOutcome.WriteRejected)
        assertEquals("com.bank.app", gateway.values[key])
        assertEquals(OperationState.FAILED, journal.history().first().state)
    }

    @Test
    fun `reports a throwing write as a failure with its cause`() {
        gateway.values[key] = "com.bank.app"
        gateway.throwOnWrite = SecurityException("denied")

        val outcome = engine.repair(key, required, fallbackValue = null)

        assertTrue(outcome is RepairOutcome.WriteFailed)
        assertEquals("SecurityException", (outcome as RepairOutcome.WriteFailed).cause)
        assertEquals(OperationState.FAILED, journal.history().first().state)
    }

    @Test
    fun `never reports success when the write does not read back`() {
        gateway.values[key] = "com.bank.app"
        gateway.acceptWithoutStoring = true

        val outcome = engine.repair(key, required, fallbackValue = null)

        assertTrue(outcome is RepairOutcome.VerificationFailed)
        val failed = outcome as RepairOutcome.VerificationFailed
        assertEquals("com.bank.app,$required", failed.expected)
        assertEquals("com.bank.app", failed.actual)
        assertEquals(OperationState.PARTIALLY_COMPLETED, journal.history().first().state)
    }

    @Test
    fun `refuses to invent packages for an empty whitelist`() {
        gateway.values[key] = ""

        val outcome = engine.repair(key, required, fallbackValue = null)

        assertTrue(outcome is RepairOutcome.ConfirmationRequired)
        assertEquals(required, (outcome as RepairOutcome.ConfirmationRequired).proposedValue)
        assertEquals(0, gateway.writeCount)
        assertEquals("", gateway.values[key])
        assertEquals(OperationState.CANCELLED, journal.history().first().state)
    }

    @Test
    fun `writes only the required item once the user confirms an empty whitelist`() {
        gateway.values[key] = ""

        val outcome = engine.repair(key, required, fallbackValue = null, confirmEmptyWhitelist = true)

        assertTrue(outcome is RepairOutcome.Applied)
        assertEquals(required, gateway.values[key])
    }

    @Test
    fun `falls back to the last known good value instead of inventing one`() {
        gateway.values.remove(key)

        val outcome = engine.repair(key, required, fallbackValue = "com.bank.app")

        assertTrue(outcome is RepairOutcome.Applied)
        assertEquals("com.bank.app,$required", gateway.values[key])
    }

    @Test
    fun `an interrupted operation blocks repair until the user resolves it`() {
        journal.start("op-dead", key, clock)

        val outcome = engine.repair(key, required, fallbackValue = null)

        assertTrue(outcome is RepairOutcome.RecoveryRequired)
        assertEquals("op-dead", (outcome as RepairOutcome.RecoveryRequired).pending.id)
        assertEquals(0, gateway.writeCount)
    }

    @Test
    fun `restores a snapshot and verifies the restored value`() {
        gateway.values[key] = "com.bank.app"
        val applied = engine.repair(key, required, fallbackValue = null) as RepairOutcome.Applied
        gateway.values[key] = "com.bank.app,$required,com.extra"

        val outcome = engine.restore(applied.snapshotId!!)

        assertTrue(outcome is RestoreOutcome.Restored)
        assertEquals("com.bank.app", gateway.values[key])
        assertEquals(OperationState.COMPLETED, journal.history().first().state)
    }

    @Test
    fun `a restore snapshots the current value so it can be undone`() {
        gateway.values[key] = "com.bank.app"
        val applied = engine.repair(key, required, fallbackValue = null) as RepairOutcome.Applied
        val current = gateway.values[key]!!
        gateway.values[key] = "com.bank.app,$required"

        engine.restore(applied.snapshotId!!)

        val latest = snapshots.latestFor(key)!!
        assertEquals(current, latest.value)
    }

    @Test
    fun `refuses to restore a snapshot whose content was altered`() {
        gateway.values[key] = "com.bank.app"
        val applied = engine.repair(key, required, fallbackValue = null) as RepairOutcome.Applied
        val id = applied.snapshotId!!
        val writesBefore = gateway.writeCount

        val file = File(temp.root, "snapshots/$id.snapshot")
        file.writeText(file.readText().replace("com.bank.app", "com.evil.app"))

        val outcome = engine.restore(id)

        assertTrue(outcome is RestoreOutcome.SnapshotCorrupt)
        assertEquals(writesBefore, gateway.writeCount)
    }

    @Test
    fun `restoring again after the value was restored is a no-op`() {
        gateway.values[key] = "com.bank.app"
        val applied = engine.repair(key, required, fallbackValue = null) as RepairOutcome.Applied
        gateway.values[key] = "com.bank.app,$required"
        engine.restore(applied.snapshotId!!)
        val writesBefore = gateway.writeCount

        val outcome = engine.restore(applied.snapshotId!!)

        assertTrue(outcome is RestoreOutcome.Restored)
        assertEquals(writesBefore, gateway.writeCount)
    }

    @Test
    fun `restore needs the write-settings permission`() {
        gateway.values[key] = "com.bank.app"
        val applied = engine.repair(key, required, fallbackValue = null) as RepairOutcome.Applied
        gateway.writable = false

        val outcome = engine.restore(applied.snapshotId!!)

        assertTrue(outcome is RestoreOutcome.PermissionMissing)
        assertEquals(1, gateway.writeCount)
        assertNull(journal.active())
    }

    @Test
    fun `an unknown snapshot id is reported instead of attempted`() {
        val outcome = engine.restore("does-not-exist")

        assertTrue(outcome is RestoreOutcome.NoSnapshot)
        assertEquals(0, gateway.writeCount)
    }

    @Test
    fun `a failed write leaves no success record`() {
        gateway.values[key] = "com.bank.app"
        gateway.rejectWrites = true

        engine.repair(key, required, fallbackValue = null)

        val record = journal.history().first()
        assertFalse(record.state == OperationState.COMPLETED)
        assertEquals("write_rejected", record.detail)
    }
}
