package com.reed.fcmguard.execution

/** Stands in for Settings.System so the pipeline can be tested without a device. */
class FakeGateway : SystemSettingsGateway {
    val values = mutableMapOf<String, String>()

    var writable = true
    var rejectWrites = false
    var acceptWithoutStoring = false
    var throwOnRead = false
    var throwOnWrite: Throwable? = null
    var writeCount = 0

    override fun canWrite(): Boolean = writable

    override fun read(key: String): String? {
        if (throwOnRead) throw IllegalStateException("read unavailable")
        return values[key]
    }

    override fun write(key: String, value: String): Boolean {
        writeCount++
        throwOnWrite?.let { throw it }
        if (rejectWrites) return false
        if (!acceptWithoutStoring) values[key] = value
        return true
    }
}
