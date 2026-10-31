package com.reed.fcmguard.ui.fcm

import com.reed.fcmguard.AutostartStatusReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scan itself touches Android services and is covered by the fake-backend work; the
 * filtering that decides what the user sees is pure and lives here.
 */
class FcmUiStateTest {

    private fun app(
        packageName: String,
        label: String,
        status: AutostartStatusReader.Status
    ) = FcmAppRow(packageName, label, status)

    private val apps = listOf(
        app("com.example.chat", "Chat", AutostartStatusReader.Status.ENABLED),
        app("com.example.mail", "Mail", AutostartStatusReader.Status.PARTIAL),
        app("com.example.bank", "Bank", AutostartStatusReader.Status.DISABLED),
        app("com.example.unknown", "Mystery", AutostartStatusReader.Status.UNKNOWN)
    )

    @Test
    fun `query matches label case-insensitively`() {
        val visible = FcmUiState(apps = apps, query = "chAt").visible
        assertEquals(listOf("com.example.chat"), visible.map { it.packageName })
    }

    @Test
    fun `query matches package name`() {
        val visible = FcmUiState(apps = apps, query = "example.bank").visible
        assertEquals(listOf("com.example.bank"), visible.map { it.packageName })
    }

    @Test
    fun `attention filter keeps partial and disabled only`() {
        val visible = FcmUiState(apps = apps, filter = FcmFilter.ATTENTION).visible
        assertEquals(
            listOf("com.example.mail", "com.example.bank"),
            visible.map { it.packageName }
        )
    }

    @Test
    fun `unknown filter excludes readable states`() {
        val visible = FcmUiState(apps = apps, filter = FcmFilter.UNKNOWN).visible
        assertEquals(listOf("com.example.unknown"), visible.map { it.packageName })
    }

    @Test
    fun `counts describe the detected list`() {
        val state = FcmUiState(apps = apps)
        assertEquals(1, state.enabledCount)
        assertEquals(2, state.attentionCount)
        assertEquals(1, state.unknownCount)
        assertEquals(false, state.autostartUnreadable)
    }

    @Test
    fun `all-unknown is reported as unreadable rather than as disabled`() {
        val state = FcmUiState(
            apps = apps.map { it.copy(autostart = AutostartStatusReader.Status.UNKNOWN) }
        )
        assertTrue(state.autostartUnreadable)
        assertEquals(apps.size, state.unknownCount)
        assertEquals(0, state.attentionCount)
    }
}
