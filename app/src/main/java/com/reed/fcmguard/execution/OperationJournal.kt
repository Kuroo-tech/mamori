package com.reed.fcmguard.execution

import android.content.SharedPreferences
import java.net.URLDecoder
import java.net.URLEncoder

enum class OperationState {
    IDLE,
    VALIDATING,
    SNAPSHOTTING,
    WRITING,
    VERIFYING,
    ROLLING_BACK,
    COMPLETED,
    PARTIALLY_COMPLETED,
    FAILED,
    CANCELLED,
    INTERRUPTED;

    val isTerminal: Boolean
        get() = when (this) {
            COMPLETED, PARTIALLY_COMPLETED, FAILED, CANCELLED, INTERRUPTED -> true
            else -> false
        }
}

data class OperationRecord(
    val id: String,
    val state: OperationState,
    val key: String,
    val startedAt: Long,
    val updatedAt: Long,
    val detail: String? = null,
    val snapshotId: String? = null
)

/**
 * Persisted operation log.
 *
 * An operation is written *before* any privileged call, so a process death in the
 * middle leaves a non-terminal record behind. That record is the recovery signal: on
 * the next start Mamori refuses to repair automatically and asks the user instead.
 */
interface OperationJournal {
    fun active(): OperationRecord?
    fun start(id: String, key: String, at: Long): OperationRecord
    fun advance(
        record: OperationRecord,
        state: OperationState,
        at: Long,
        detail: String? = null,
        snapshotId: String? = null
    ): OperationRecord
    fun complete(
        record: OperationRecord,
        state: OperationState,
        at: Long,
        detail: String? = null,
        snapshotId: String? = null
    ): OperationRecord
    /** Turns a leftover active record into INTERRUPTED in history and clears it. */
    fun abandonInterrupted(at: Long, detail: String? = null): OperationRecord?
    fun history(): List<OperationRecord>
}

class SharedPreferencesOperationJournal(
    private val store: KeyValueStore,
    private val historyLimit: Int = DEFAULT_HISTORY_LIMIT
) : OperationJournal {

    override fun active(): OperationRecord? {
        val raw = store.get(KEY_ACTIVE) ?: return null
        return OperationRecordCodec.decode(raw)
    }

    override fun start(id: String, key: String, at: Long): OperationRecord {
        val record = OperationRecord(
            id = id,
            state = OperationState.VALIDATING,
            key = key,
            startedAt = at,
            updatedAt = at
        )
        store.put(KEY_ACTIVE, OperationRecordCodec.encode(record))
        return record
    }

    override fun advance(
        record: OperationRecord,
        state: OperationState,
        at: Long,
        detail: String?,
        snapshotId: String?
    ): OperationRecord {
        val next = record.copy(
            state = state,
            detail = detail,
            updatedAt = at,
            snapshotId = snapshotId ?: record.snapshotId
        )
        store.put(KEY_ACTIVE, OperationRecordCodec.encode(next))
        return next
    }

    override fun complete(
        record: OperationRecord,
        state: OperationState,
        at: Long,
        detail: String?,
        snapshotId: String?
    ): OperationRecord {
        require(state.isTerminal) { "complete() needs a terminal state, got $state" }
        val finished = record.copy(
            state = state,
            detail = detail,
            snapshotId = snapshotId ?: record.snapshotId,
            updatedAt = at
        )
        store.put(KEY_ACTIVE, null)
        store.put(KEY_HISTORY, OperationRecordCodec.encodeList((listOf(finished) + history()).take(historyLimit)))
        return finished
    }

    override fun abandonInterrupted(at: Long, detail: String?): OperationRecord? {
        val pending = active() ?: return null
        return complete(
            pending,
            OperationState.INTERRUPTED,
            at,
            detail ?: pending.detail
        )
    }

    override fun history(): List<OperationRecord> {
        val raw = store.get(KEY_HISTORY) ?: return emptyList()
        return OperationRecordCodec.decodeList(raw)
    }

    private companion object {
        const val KEY_ACTIVE = "operation_active"
        const val KEY_HISTORY = "operation_history"
        const val DEFAULT_HISTORY_LIMIT = 50
    }
}

/** Minimal storage seam so the journal is testable without Android. */
interface KeyValueStore {
    fun get(key: String): String?
    fun put(key: String, value: String?)
}

class PreferencesKeyValueStore(private val prefs: SharedPreferences) : KeyValueStore {
    override fun get(key: String): String? = prefs.getString(key, null)

    override fun put(key: String, value: String?) {
        val editor = prefs.edit()
        if (value == null) editor.remove(key) else editor.putString(key, value)
        editor.apply()
    }
}

class MemoryKeyValueStore : KeyValueStore {
    private val values = HashMap<String, String>()

    override fun get(key: String): String? = values[key]

    override fun put(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
}

internal object OperationRecordCodec {
    private const val FIELD = '\u0001'
    private const val RECORD = '\u0002'

    fun encode(record: OperationRecord): String = listOf(
        record.id,
        record.state.name,
        record.key,
        record.startedAt.toString(),
        record.updatedAt.toString(),
        record.detail ?: "",
        record.snapshotId ?: ""
    ).joinToString(FIELD.toString()) { escape(it) }

    fun decode(raw: String): OperationRecord? {
        val parts = raw.split(FIELD)
        if (parts.size < 5) return null
        val state = OperationState.entries.firstOrNull { it.name == parts[1] } ?: return null
        val startedAt = parts[3].toLongOrNull() ?: return null
        val updatedAt = parts[4].toLongOrNull() ?: return null
        return OperationRecord(
            id = unescape(parts[0]),
            state = state,
            key = unescape(parts[2]),
            startedAt = startedAt,
            updatedAt = updatedAt,
            detail = parts.getOrNull(5)?.let { unescape(it) }?.ifEmpty { null },
            snapshotId = parts.getOrNull(6)?.let { unescape(it) }?.ifEmpty { null }
        )
    }

    fun encodeList(records: List<OperationRecord>): String =
        records.joinToString(RECORD.toString()) { encode(it) }

    fun decodeList(raw: String): List<OperationRecord> =
        raw.split(RECORD).mapNotNull { decode(it) }

    // URL encoding never emits the field/record separators, so joining stays unambiguous.
    private fun escape(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun unescape(value: String): String =
        try {
            URLDecoder.decode(value, "UTF-8")
        } catch (_: Throwable) {
            value
        }
}
