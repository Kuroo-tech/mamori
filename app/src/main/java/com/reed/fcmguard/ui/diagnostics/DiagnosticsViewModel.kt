package com.reed.fcmguard.ui.diagnostics

import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.reed.fcmguard.core.log.LogEntry
import com.reed.fcmguard.core.log.MamoriLog
import com.reed.fcmguard.data.DiagnosticsData
import com.reed.fcmguard.data.MamoriRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Facts only: everything on this screen comes from a real read, the persisted journal, the
 * snapshot store, or the log. The device fields are static `Build` values, not guesses
 * about the ROM.
 */
data class DeviceInfo(
    val manufacturer: String,
    val model: String,
    val androidRelease: String,
    val sdkInt: Int,
    val buildDisplay: String,
    val securityCenterInstalled: Boolean,
    val appVersion: String
)

data class DiagnosticsUiState(
    val loading: Boolean = true,
    val data: DiagnosticsData? = null,
    val log: List<LogEntry> = emptyList(),
    val persistedLines: Int = 0,
    val device: DeviceInfo
)

class DiagnosticsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MamoriRepository(application)
    private val _state = MutableStateFlow(DiagnosticsUiState(device = readDeviceInfo(application)))
    val state: StateFlow<DiagnosticsUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val data = repository.diagnostics()
            val persisted = MamoriLog.readPersisted(getApplication())
            _state.update {
                it.copy(
                    loading = false,
                    data = data,
                    log = MamoriLog.recent(),
                    persistedLines = persisted.lineSequence().count { line -> line.isNotBlank() }
                )
            }
        }
    }

    fun clearLog() {
        MamoriLog.clear()
        _state.update { it.copy(log = emptyList(), persistedLines = 0) }
    }

    /** Export text for the share sheet. Persisted entries first, this-session tail after. */
    suspend fun exportText(): String {
        val persisted = MamoriLog.readPersisted(getApplication())
        val recent = MamoriLog.recent()
        val persistedLines = persisted.lineSequence().filter { it.isNotBlank() }.toSet()
        val missing = recent.filter { it.format() !in persistedLines }
        return buildString {
            if (persisted.isNotBlank()) append(persisted.trimEnd()).append('\n')
            if (missing.isNotEmpty()) {
                append("---- this session (not yet flushed) ----\n")
                missing.forEach { append(it.format()).append('\n') }
            }
        }
    }

    private fun readDeviceInfo(app: Application): DeviceInfo {
        val securityCenter = try {
            app.packageManager.getPackageInfo("com.miui.securitycenter", 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (_: Throwable) {
            false
        }
        val version = try {
            app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: ""
        } catch (_: Throwable) {
            ""
        }
        return DeviceInfo(
            manufacturer = Build.MANUFACTURER.orEmpty(),
            model = Build.MODEL.orEmpty(),
            androidRelease = Build.VERSION.RELEASE.orEmpty(),
            sdkInt = Build.VERSION.SDK_INT,
            buildDisplay = Build.DISPLAY.orEmpty(),
            securityCenterInstalled = securityCenter,
            appVersion = version
        )
    }
}
