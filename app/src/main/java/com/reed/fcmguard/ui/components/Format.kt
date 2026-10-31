package com.reed.fcmguard.ui.components

import java.text.DateFormat
import java.util.Date

/**
 * One date format for every timestamp the UI shows (records, backups, logs). Uses the
 * system locale and time zone, which is what users compare against their own clock.
 */
fun formatTime(epochMillis: Long): String =
    DateFormat.getDateTimeInstance().format(Date(epochMillis))
