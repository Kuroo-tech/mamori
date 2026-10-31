package com.reed.fcmguard.ui.settings

import android.app.Application
import android.content.pm.PackageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.reed.fcmguard.LocaleHelper
import com.reed.fcmguard.R
import com.reed.fcmguard.SettingsGuard
import com.reed.fcmguard.ThemeHelper
import com.reed.fcmguard.data.MamoriRepository
import com.reed.fcmguard.ui.theme.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SettingsUiState(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val dynamicColorSupported: Boolean = false,
    val language: String = "system",
    val key: String = "",
    val requiredItem: String = "",
    val canWrite: Boolean = false,
    val versionName: String = ""
)

/**
 * Settings state. Preferences keep their historical names and defaults; only the
 * dynamic-color flag is new, and it defaults to ON because the platform palette is the
 * intended default.
 */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MamoriRepository(application)
    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    private val messageChannel = Channel<String>(Channel.BUFFERED)
    val messages = messageChannel.receiveAsFlow()

    init {
        val app = getApplication<Application>()
        _state.update {
            it.copy(
                themeMode = ThemeMode.fromPreference(ThemeHelper.getMode(app)),
                dynamicColor = ThemeHelper.isDynamicColorEnabled(app),
                dynamicColorSupported = android.os.Build.VERSION.SDK_INT >=
                    android.os.Build.VERSION_CODES.S,
                language = LocaleHelper.getLanguage(app),
                key = SettingsGuard.getConfiguredKey(app),
                requiredItem = SettingsGuard.getConfiguredRequiredItem(app),
                versionName = versionName(app)
            )
        }
        refreshPermission()
    }

    fun refreshPermission() {
        viewModelScope.launch {
            val canWrite = withContext(Dispatchers.IO) {
                try {
                    android.provider.Settings.System.canWrite(getApplication())
                } catch (_: Throwable) {
                    false
                }
            }
            _state.update { it.copy(canWrite = canWrite) }
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        ThemeHelper.setMode(getApplication(), mode.preferenceValue)
        _state.update { it.copy(themeMode = mode) }
    }

    fun setDynamicColor(enabled: Boolean) {
        ThemeHelper.setDynamicColorEnabled(getApplication(), enabled)
        _state.update { it.copy(dynamicColor = enabled) }
    }

    /** Locale changes need the Activity to re-attach its context; the screen does that. */
    fun setLanguage(code: String) {
        LocaleHelper.setLanguage(getApplication(), code)
        _state.update { it.copy(language = code) }
    }

    fun setKey(value: String) = _state.update { it.copy(key = value) }

    fun setRequiredItem(value: String) = _state.update { it.copy(requiredItem = value) }

    fun saveConfiguration() {
        val app = getApplication<Application>()
        repository.saveConfiguration(_state.value.key, _state.value.requiredItem)
        // Read back what was actually persisted, so the fields cannot drift from storage.
        val key = SettingsGuard.getConfiguredKey(app)
        val item = SettingsGuard.getConfiguredRequiredItem(app)
        _state.update { it.copy(key = key, requiredItem = item) }
        messageChannel.trySend(app.getString(R.string.saved))
    }

    private fun versionName(app: Application): String = try {
        app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: ""
    } catch (_: PackageManager.NameNotFoundException) {
        ""
    } catch (_: Throwable) {
        ""
    }
}
