package com.reed.fcmguard.ui.fcm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.reed.fcmguard.AutostartStatusReader
import com.reed.fcmguard.FcmAppScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One detected FCM app and the Autostart state the ROM was willing to reveal. */
data class FcmAppRow(
    val packageName: String,
    val label: String,
    val autostart: AutostartStatusReader.Status
)

enum class FcmFilter { ALL, ENABLED, ATTENTION, UNKNOWN }

data class FcmUiState(
    val scanning: Boolean = false,
    val scanned: Boolean = false,
    val apps: List<FcmAppRow> = emptyList(),
    val query: String = "",
    val filter: FcmFilter = FcmFilter.ALL
) {
    val visible: List<FcmAppRow>
        get() {
            val needle = query.trim().lowercase()
            return apps.filter { app ->
                val matchesQuery = needle.isEmpty() ||
                    app.label.lowercase().contains(needle) ||
                    app.packageName.lowercase().contains(needle)
                val matchesFilter = when (filter) {
                    FcmFilter.ALL -> true
                    FcmFilter.ENABLED -> app.autostart == AutostartStatusReader.Status.ENABLED
                    FcmFilter.ATTENTION ->
                        app.autostart == AutostartStatusReader.Status.PARTIAL ||
                            app.autostart == AutostartStatusReader.Status.DISABLED
                    FcmFilter.UNKNOWN -> app.autostart == AutostartStatusReader.Status.UNKNOWN
                }
                matchesQuery && matchesFilter
            }
        }

    val enabledCount: Int
        get() = apps.count { it.autostart == AutostartStatusReader.Status.ENABLED }

    val attentionCount: Int
        get() = apps.count {
            it.autostart == AutostartStatusReader.Status.PARTIAL ||
                it.autostart == AutostartStatusReader.Status.DISABLED
        }

    val unknownCount: Int
        get() = apps.count { it.autostart == AutostartStatusReader.Status.UNKNOWN }

    /** True when the ROM blocked every vendor probe: the list is shown, the states are not. */
    val autostartUnreadable: Boolean
        get() = apps.isNotEmpty() && unknownCount == apps.size
}

/**
 * FCM app discovery.
 *
 * Both steps — resolving FCM/GCM declarants and probing the vendor Autostart AppOps —
 * touch the PackageManager and the AppOps service, so they run on [Dispatchers.IO]. The
 * UI thread only ever receives an immutable result.
 */
class FcmViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(FcmUiState())
    val state: StateFlow<FcmUiState> = _state.asStateFlow()

    fun scan() {
        if (_state.value.scanning) return
        _state.update { it.copy(scanning = true) }
        viewModelScope.launch {
            val app = getApplication<Application>()
            val rows = withContext(Dispatchers.IO) {
                FcmAppScanner.scan(app).map { entry ->
                    FcmAppRow(
                        packageName = entry.packageName,
                        label = entry.label,
                        autostart = AutostartStatusReader.check(app, entry.packageName)
                    )
                }
            }
            _state.update { it.copy(scanning = false, scanned = true, apps = rows) }
        }
    }

    fun setQuery(query: String) = _state.update { it.copy(query = query) }

    fun setFilter(filter: FcmFilter) = _state.update { it.copy(filter = filter) }
}
