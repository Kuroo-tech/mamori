package com.reed.fcmguard.ui.language

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.reed.fcmguard.core.locale.LocaleCapabilities
import com.reed.fcmguard.core.locale.LocaleCapability
import com.reed.fcmguard.core.locale.SystemLocaleSnapshot
import com.reed.fcmguard.data.AndroidLocaleRepository
import com.reed.fcmguard.data.AppLocaleState
import com.reed.fcmguard.data.InstalledAppLocale
import com.reed.fcmguard.data.LocaleRepository
import com.reed.fcmguard.data.LocaleWriteResult
import com.reed.fcmguard.data.SystemLocaleState
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface LanguageMessage {
    data class Applied(val tag: String) : LanguageMessage
    data object Cleared : LanguageMessage
    data class SystemApplied(val tag: String) : LanguageMessage
    /** Stored but only active after a restart: reported separately from success. */
    data class PendingRestart(val tag: String) : LanguageMessage
    data class SystemRestored(val tag: String) : LanguageMessage
    data class Failed(val reason: LocaleFailure) : LanguageMessage
}

sealed interface LocaleFailure {
    data class Unsupported(val detail: String) : LocaleFailure
    data class Rejected(val detail: String) : LocaleFailure
    data class VerificationFailed(val expected: String?, val actual: String?) : LocaleFailure
}

/** One-shot actions that need an Activity. */
sealed interface LanguageEvent {
    data object OpenSystemLanguageSettings : LanguageEvent
    data class OpenAppSettings(val packageName: String) : LanguageEvent
    /** The app's own locale changed; the Activity must re-attach its context. */
    data object LocaleChanged : LanguageEvent
}

enum class AppLocaleFilter { ALL, OVERRIDDEN, SYSTEM_DEFAULT }

data class LanguageUiState(
    val loading: Boolean = true,
    val capabilities: LocaleCapabilities? = null,
    val system: SystemLocaleState? = null,
    val snapshot: SystemLocaleSnapshot? = null,
    val ownOverride: String? = null,
    val apps: List<InstalledAppLocale> = emptyList(),
    val query: String = "",
    val filter: AppLocaleFilter = AppLocaleFilter.ALL,
    val discoveryRestricted: Boolean = false,
    val otherAppLocalesReadable: Boolean = false,
    val applying: Boolean = false,
    val message: LanguageMessage? = null
) {
    val canWriteSystemLocale: Boolean
        get() = capabilities?.can(LocaleCapability.WRITE_SYSTEM_LOCALES) == true

    val canWriteOwnLocale: Boolean
        get() = capabilities?.can(LocaleCapability.WRITE_OWN_APP_LOCALES) == true

    /** Pure filter so search and filtering are unit-testable without Compose. */
    val visibleApps: List<InstalledAppLocale>
        get() {
            val filtered = when (filter) {
                AppLocaleFilter.ALL -> apps
                AppLocaleFilter.OVERRIDDEN -> apps.filter {
                    it.state is AppLocaleState.Override
                }
                AppLocaleFilter.SYSTEM_DEFAULT -> apps.filter {
                    it.state == AppLocaleState.SystemDefault
                }
            }
            val needle = query.trim().lowercase()
            if (needle.isEmpty()) return filtered
            return filtered.filter { app ->
                app.label.lowercase().contains(needle) ||
                    app.packageName.lowercase().contains(needle)
            }
        }
}

/**
 * Language Manager state holder.
 *
 * It is a plain [ViewModel] with an injected repository so the state machine can be tested
 * on the JVM with a fake. It performs no privileged work: it requests either Mamori's own
 * application locale or the system locale list, and reports success only after the
 * repository read the result back. A system-locale change the running configuration did not
 * pick up is surfaced as [LanguageMessage.PendingRestart], not as success.
 */
class LanguageManagerViewModel(
    private val repository: LocaleRepository
) : ViewModel() {

    private val _state = MutableStateFlow(LanguageUiState())
    val state: StateFlow<LanguageUiState> = _state.asStateFlow()

    private val eventChannel = Channel<LanguageEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()

    private var loadJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            val capabilities = repository.capabilities()
            val system = repository.readSystemLocales()
            val snapshot = repository.systemLocaleSnapshot()
            val own = repository.readOwnLocaleOverride()
            val discovery = repository.discoverApps()
            _state.update {
                it.copy(
                    loading = false,
                    capabilities = capabilities,
                    system = system,
                    snapshot = snapshot,
                    ownOverride = own,
                    apps = discovery.apps,
                    discoveryRestricted = discovery.restricted,
                    otherAppLocalesReadable = discovery.otherAppLocalesReadable
                )
            }
        }
    }

    /**
     * Re-reads only what can change outside the app, without flashing the loading state.
     * Capabilities are re-read too, because "Modify system settings" may have just been
     * granted on the system screen.
     */
    fun onResume() {
        viewModelScope.launch {
            val capabilities = repository.capabilities()
            val system = repository.readSystemLocales()
            val snapshot = repository.systemLocaleSnapshot()
            val own = repository.readOwnLocaleOverride()
            _state.update {
                it.copy(
                    capabilities = capabilities,
                    system = system,
                    snapshot = snapshot,
                    ownOverride = own
                )
            }
        }
    }

    fun setQuery(query: String) = _state.update { it.copy(query = query) }

    fun setFilter(filter: AppLocaleFilter) = _state.update { it.copy(filter = filter) }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    /** Icons are loaded lazily per row so opening the screen does not decode every app. */
    suspend fun loadIcon(packageName: String): Drawable? = repository.icon(packageName)

    fun openSystemLanguageSettings() {
        eventChannel.trySend(LanguageEvent.OpenSystemLanguageSettings)
    }

    fun openAppSettings(packageName: String) {
        eventChannel.trySend(LanguageEvent.OpenAppSettings(packageName))
    }

    fun changeSystemLocale(tag: String) {
        if (_state.value.applying) return
        _state.update { it.copy(applying = true) }
        viewModelScope.launch {
            val result = repository.setSystemLocale(tag)
            finishSystemWrite(result, restoring = false)
        }
    }

    fun restoreSystemLocale() {
        if (_state.value.applying) return
        _state.update { it.copy(applying = true) }
        viewModelScope.launch {
            val result = repository.restoreSystemLocale()
            finishSystemWrite(result, restoring = true)
        }
    }

    fun applyOwnLocale(tag: String) {
        if (_state.value.applying) return
        _state.update { it.copy(applying = true) }
        viewModelScope.launch {
            val result = repository.setOwnLocale(tag)
            finishWrite(result)
        }
    }

    fun clearOwnLocale() {
        if (_state.value.applying) return
        _state.update { it.copy(applying = true) }
        viewModelScope.launch {
            val result = repository.clearOwnLocale()
            finishWrite(result)
        }
    }

    /**
     * Applies an operation result. Success is the repository's verified result; a failed
     * verification is surfaced as a failure, never as a success with a stale label.
     */
    private fun finishWrite(result: LocaleWriteResult) {
        val appliedTag = (result as? LocaleWriteResult.Applied)?.tag
        val applied = result is LocaleWriteResult.Applied
        _state.update { current ->
            current.copy(
                applying = false,
                ownOverride = if (applied) appliedTag else current.ownOverride,
                apps = if (applied) {
                    current.apps.map { app ->
                        if (app.isSelf) {
                            app.copy(
                                state = appliedTag?.let { AppLocaleState.Override(it) }
                                    ?: AppLocaleState.SystemDefault
                            )
                        } else {
                            app
                        }
                    }
                } else {
                    current.apps
                },
                message = messageFor(result)
            )
        }
        if (applied) eventChannel.trySend(LanguageEvent.LocaleChanged)
    }

    /**
     * Applies a system-locale result. The system state is re-read either way, because a
     * rejected write can still leave the platform (or an external change) in a new state.
     */
    private fun finishSystemWrite(result: LocaleWriteResult, restoring: Boolean) {
        viewModelScope.launch {
            val system = repository.readSystemLocales()
            val snapshot = repository.systemLocaleSnapshot()
            val message = when (result) {
                is LocaleWriteResult.Applied -> when {
                    result.tag == null -> null
                    restoring -> LanguageMessage.SystemRestored(result.tag)
                    else -> LanguageMessage.SystemApplied(result.tag)
                }
                is LocaleWriteResult.PendingRestart -> LanguageMessage.PendingRestart(result.tag)
                is LocaleWriteResult.Unsupported ->
                    LanguageMessage.Failed(LocaleFailure.Unsupported(result.reason))
                is LocaleWriteResult.Rejected ->
                    LanguageMessage.Failed(LocaleFailure.Rejected(result.reason))
                is LocaleWriteResult.VerificationFailed ->
                    LanguageMessage.Failed(
                        LocaleFailure.VerificationFailed(result.expected, result.actual)
                    )
            }
            _state.update {
                it.copy(
                    applying = false,
                    system = system,
                    snapshot = snapshot,
                    message = message
                )
            }
        }
    }

    private fun messageFor(result: LocaleWriteResult): LanguageMessage = when (result) {
        is LocaleWriteResult.Applied ->
            result.tag?.let { LanguageMessage.Applied(it) } ?: LanguageMessage.Cleared
        is LocaleWriteResult.PendingRestart -> LanguageMessage.PendingRestart(result.tag)
        is LocaleWriteResult.Unsupported ->
            LanguageMessage.Failed(LocaleFailure.Unsupported(result.reason))
        is LocaleWriteResult.Rejected ->
            LanguageMessage.Failed(LocaleFailure.Rejected(result.reason))
        is LocaleWriteResult.VerificationFailed ->
            LanguageMessage.Failed(
                LocaleFailure.VerificationFailed(result.expected, result.actual)
            )
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                LanguageManagerViewModel(
                    AndroidLocaleRepository(context.applicationContext)
                )
            }
        }
    }
}
