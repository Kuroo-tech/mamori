package com.reed.fcmguard.execution

import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.UUID

/**
 * A pre-write copy of one setting value.
 *
 * A snapshot always holds a value that existed. "The setting was absent" is not a
 * snapshot, because Mamori has no verified way to delete a system setting; pretending
 * otherwise would make restore look safe when it is not.
 */
data class Snapshot(
    val id: String,
    val operationId: String,
    val key: String,
    val value: String,
    val createdAt: Long,
    /** SHA-256 over the canonical payload; any edit makes the snapshot unusable. */
    val sha256: String
)

/**
 * File-backed snapshot storage with integrity verification.
 *
 * One text file per snapshot, written to a temporary file and then renamed so a crash
 * mid-write cannot leave a half-written snapshot in place. Bundling and history are
 * deliberately not here: this class only answers "what did this key look like, and can
 * I prove the copy is intact?".
 */
class SnapshotStore(
    private val directory: File,
    private val limit: Int = DEFAULT_LIMIT
) {
    init {
        require(limit > 0) { "limit must be positive" }
    }

    fun save(operationId: String, key: String, value: String, createdAt: Long): Snapshot {
        val id = UUID.randomUUID().toString()
        val snapshot = Snapshot(
            id = id,
            operationId = operationId,
            key = key,
            value = value,
            createdAt = createdAt,
            sha256 = digest(id, operationId, key, createdAt, value)
        )

        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            throw java.io.IOException("snapshot directory unavailable")
        }

        val target = fileFor(id)
        val temp = File(directory, "$id.tmp")
        temp.writeText(serialize(snapshot), Charsets.UTF_8)
        if (!temp.renameTo(target)) {
            temp.delete()
            throw java.io.IOException("snapshot could not be stored")
        }

        prune()
        return snapshot
    }

    fun find(id: String): Snapshot? {
        val file = fileFor(id)
        if (!file.isFile) return null
        return try {
            parse(file.readText(Charsets.UTF_8))
        } catch (_: Throwable) {
            null
        }
    }

    /** Newest first. Corrupt files are skipped rather than surfaced as usable copies. */
    fun all(): List<Snapshot> =
        files()
            .mapNotNull { file ->
                try {
                    parse(file.readText(Charsets.UTF_8))
                } catch (_: Throwable) {
                    null
                }
            }
            .filter { verify(it) }
            .sortedByDescending { it.createdAt }

    fun latestFor(key: String): Snapshot? = all().firstOrNull { it.key == key }

    /** Recomputes the digest from the stored fields. False means: do not restore this. */
    fun verify(snapshot: Snapshot): Boolean =
        snapshot.sha256.equals(
            digest(snapshot.id, snapshot.operationId, snapshot.key, snapshot.createdAt, snapshot.value),
            ignoreCase = true
        )

    fun delete(id: String): Boolean = fileFor(id).delete()

    private fun files(): List<File> =
        directory.listFiles { file -> file.isFile && file.name.endsWith(SUFFIX) }?.toList()
            ?: emptyList()

    private fun fileFor(id: String): File = File(directory, "$id$SUFFIX")

    private fun prune() {
        val expired = files()
            .mapNotNull { file ->
                try {
                    parse(file.readText(Charsets.UTF_8))
                } catch (_: Throwable) {
                    null
                }
            }
            .sortedByDescending { it.createdAt }
            .drop(limit)
        for (snapshot in expired) fileFor(snapshot.id).delete()
    }

    private fun serialize(snapshot: Snapshot): String = buildString {
        append(HEADER).append('\n')
        append("id=").append(encode(snapshot.id)).append('\n')
        append("operationId=").append(encode(snapshot.operationId)).append('\n')
        append("key=").append(encode(snapshot.key)).append('\n')
        append("createdAt=").append(snapshot.createdAt).append('\n')
        append("value=").append(encode(snapshot.value)).append('\n')
        append("sha256=").append(snapshot.sha256).append('\n')
    }

    private fun parse(raw: String): Snapshot? {
        val lines = raw.split('\n')
        if (lines.isEmpty() || lines[0].trim() != HEADER) return null

        val fields = HashMap<String, String>()
        for (line in lines.drop(1)) {
            val separator = line.indexOf('=')
            if (separator <= 0) continue
            fields[line.substring(0, separator)] = line.substring(separator + 1)
        }

        val id = fields["id"]?.let { decode(it) } ?: return null
        val operationId = fields["operationId"]?.let { decode(it) } ?: return null
        val key = fields["key"]?.let { decode(it) } ?: return null
        val value = fields["value"]?.let { decode(it) } ?: return null
        val createdAt = fields["createdAt"]?.toLongOrNull() ?: return null
        val sha256 = fields["sha256"] ?: return null

        return Snapshot(id, operationId, key, value, createdAt, sha256)
    }

    private fun digest(
        id: String,
        operationId: String,
        key: String,
        createdAt: Long,
        value: String
    ): String {
        val payload = "$HEADER\n$id\n$operationId\n$key\n$createdAt\n$value"
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(payload.toByteArray(Charsets.UTF_8))
        val hex = StringBuilder(bytes.size * 2)
        for (byte in bytes) hex.append(String.format("%02x", byte))
        return hex.toString()
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun decode(value: String): String = URLDecoder.decode(value, "UTF-8")

    companion object {
        private const val HEADER = "mamori-snapshot/1"
        private const val SUFFIX = ".snapshot"
        private const val DEFAULT_LIMIT = 20
    }
}
