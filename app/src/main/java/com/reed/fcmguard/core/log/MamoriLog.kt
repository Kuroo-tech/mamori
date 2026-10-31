package com.reed.fcmguard.core.log

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

enum class LogLevel { INFO, WARN, ERROR }

data class LogEntry(
    val timestamp: Long,
    val level: LogLevel,
    val tag: String,
    val message: String
) {
    /** One line, stable format: used for the file, the screen and the shared export. */
    fun format(): String {
        // SimpleDateFormat is not thread-safe and this is called from the UI, the writer
        // thread and the diagnostics export.
        val time = synchronized(timeFormat) { timeFormat.format(Date(timestamp)) }
        return "$time ${level.name.padEnd(5)} $tag: $message"
    }

    private companion object {
        val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT)
    }
}

/**
 * Bounded, insertion-ordered ring. Kept separate from [MamoriLog] so the eviction rules
 * are testable without touching a file or an executor.
 */
internal class LogRing(private val capacity: Int) {
    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    private val entries = ArrayDeque<LogEntry>(capacity)

    @Synchronized
    fun add(entry: LogEntry) {
        if (entries.size == capacity) entries.removeFirst()
        entries.addLast(entry)
    }

    @Synchronized
    fun snapshot(): List<LogEntry> = entries.toList()

    @Synchronized
    fun clear() = entries.clear()

    @Synchronized
    fun size(): Int = entries.size
}

/**
 * Process-wide diagnostic log.
 *
 * Two properties matter for a utility that performs privileged writes:
 *
 * 1. It must never block the caller or the UI thread. Entries go into an in-memory ring
 *    immediately; file persistence happens on a dedicated daemon thread.
 * 2. It must never grow without bound. The ring holds the most recent
 *    [RING_CAPACITY] entries for this process, and the on-disk file is trimmed to
 *    [FILE_KEEP_BYTES] at a line boundary once it passes [FILE_MAX_BYTES].
 *
 * Logging is best-effort by construction: if the file cannot be written, the in-memory
 * ring keeps working and no exception ever escapes [info]/[warn]/[error].
 */
object MamoriLog {
    const val RING_CAPACITY = 300
    const val FILE_MAX_BYTES = 128 * 1024
    const val FILE_KEEP_BYTES = 64 * 1024

    private val ring = LogRing(RING_CAPACITY)
    private val onceKeys = Collections.synchronizedSet(HashSet<String>())

    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "mamori-log").apply { isDaemon = true }
    }

    @Volatile
    private var logFile: File? = null

    @Volatile
    private var fileBytes: Long = 0L

    /**
     * Points the log at an app-private file. Safe to call from multiple processes and
     * multiple times; only the first call wins.
     */
    @Synchronized
    fun attach(context: Context) {
        if (logFile != null) return
        // No directory or file I/O here: callers attach from onCreate on the main thread.
        val file = File(File(context.applicationContext.filesDir, "logs"), "mamori.log")
        logFile = file
        writer.execute {
            try {
                file.parentFile?.mkdirs()
                fileBytes = file.length()
                trimIfNeeded(file)
            } catch (_: Throwable) {
                // Persistence is optional; the in-memory ring still works.
            }
        }
    }

    fun info(tag: String, message: String) = record(LogLevel.INFO, tag, message, null)

    fun warn(tag: String, message: String, error: Throwable? = null) =
        record(LogLevel.WARN, tag, message, error)

    fun error(tag: String, message: String, error: Throwable? = null) =
        record(LogLevel.ERROR, tag, message, error)

    /**
     * Logs a systematic, repeated failure only once per process. Used for probes that are
     * attempted for every app (vendor AppOps reflection, package queries), where logging
     * each attempt would bury everything else without adding information.
     */
    fun warnOnce(key: String, tag: String, message: String, error: Throwable? = null) {
        if (onceKeys.add(key)) warn(tag, message, error)
    }

    /** Entries from this process, oldest first. */
    fun recent(): List<LogEntry> = ring.snapshot()

    fun clear() {
        ring.clear()
        onceKeys.clear()
        val file = logFile ?: return
        writer.execute {
            try {
                if (file.exists() && file.delete()) {
                    fileBytes = 0L
                }
            } catch (_: Throwable) {
                // The ring is already cleared; a stale file is a diagnostic nuisance only.
            }
        }
    }

    /** Full persisted log, oldest first. Returns an empty string when nothing was written. */
    suspend fun readPersisted(context: Context): String = withContext(Dispatchers.IO) {
        val file = logFile ?: File(File(context.applicationContext.filesDir, "logs"), "mamori.log")
        try {
            if (!file.exists()) "" else file.readText()
        } catch (_: Throwable) {
            ""
        }
    }

    private fun record(level: LogLevel, tag: String, message: String, error: Throwable?) {
        val detail = if (error == null) {
            message
        } else {
            "$message (${error.javaClass.simpleName}${error.message?.let { ": $it" }.orEmpty()})"
        }
        val entry = LogEntry(System.currentTimeMillis(), level, tag, detail)
        ring.add(entry)

        val file = logFile ?: return
        val line = entry.format()
        writer.execute {
            try {
                file.parentFile?.mkdirs()
                FileOutputStream(file, true).bufferedWriter().use { it.appendLine(line) }
                fileBytes += line.length + 1
                trimIfNeeded(file)
            } catch (_: Throwable) {
                // Never let diagnostics break the operation being diagnosed.
            }
        }
    }

    /** Keeps the tail of the file, aligned to a line boundary, so lines stay readable. */
    private fun trimIfNeeded(file: File) {
        if (fileBytes <= FILE_MAX_BYTES) return
        try {
            val text = file.readText()
            val tailStart = (text.length - FILE_KEEP_BYTES).coerceAtLeast(0)
            val boundary = text.indexOf('\n', tailStart)
            val tail = if (boundary >= 0) text.substring(boundary + 1) else text
            file.writeText(tail)
            fileBytes = tail.length.toLong()
        } catch (_: Throwable) {
            // A corrupt or unwritable file is reported by size only; the ring is unaffected.
        }
    }
}
