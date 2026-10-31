package com.reed.fcmguard.execution

import android.content.Context
import android.provider.Settings

/**
 * The only surface allowed to touch the privileged setting.
 *
 * Writes report what the platform's API returned; they never mean "it worked".
 * Only a read-back confirms that, and that confirmation belongs to [RepairEngine],
 * not to the gateway.
 */
interface SystemSettingsGateway {
    fun canWrite(): Boolean
    fun read(key: String): String?
    /** Returns false when the platform rejected the write instead of throwing. */
    fun write(key: String, value: String): Boolean
}

class AndroidSystemSettingsGateway(context: Context) : SystemSettingsGateway {
    private val context = context.applicationContext

    override fun canWrite(): Boolean = Settings.System.canWrite(context)

    override fun read(key: String): String? =
        Settings.System.getString(context.contentResolver, key)

    override fun write(key: String, value: String): Boolean =
        Settings.System.putString(context.contentResolver, key, value)
}
