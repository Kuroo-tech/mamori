package com.reed.fcmguard.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.reed.fcmguard.LocaleHelper
import com.reed.fcmguard.R
import com.reed.fcmguard.data.DeviceStatus
import com.reed.fcmguard.data.MamoriRepository
import com.reed.fcmguard.execution.OperationRecord
import com.reed.fcmguard.execution.OperationState
import com.reed.fcmguard.execution.OutcomeExplanation
import com.reed.fcmguard.execution.OutcomeExplanations
import com.reed.fcmguard.execution.RepairOutcome
import com.reed.fcmguard.execution.RestoreOutcome
import com.reed.fcmguard.execution.Snapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * UI state for the dashboard.
 *
 * [status] is null until a real read has completed; the UI shows a skeleton until then
 * instead of pretending the device is in a particular state.
 */
data class HomeUiState(
    val loading: Boolean = true,
    val status: DeviceStatus? = null,
    val history: List<OperationRecord> = emptyList(),
    val busy: Boolean = false,
    val stage: OperationState? = null,
    val emptyWhitelistPrompt: RepairOutcome.ConfirmationRequired? = null,
    val restorePrompt: Snapshot? = null,
    val recoveryPrompt: OperationRecord? = null,
    val resultPrompt: OutcomeExplanation? = null
) {
    val latestSnapshot: Snapshot? get() = status?.latestSnapshot
}

/** One-shot platform actions the screen must perform (they need an Activity). */
sealed interface HomeEvent {
    data object OpenWriteSettings : HomeEvent
    data object OpenNotificationSettings : HomeEvent
}

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MamoriRepository(application)

    /**
     * Activity-equivalent context for user-facing text: on API < 33 the localized
     * configuration lives here, not on the bare application context.
     */
    private val uiContext = LocaleHelper.apply(application)

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    private val eventChannel = Channel<HomeEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()

    private val messageChannel = Channel<String>(Channel.BUFFERED)
    val messages = messageChannel.receiveAsFlow()

    private var recoveryOffered = false
    private var refreshJob = 0

    init {
        refresh()
    }

    /**
     * Called when the screen resumes: re-read the world, and make sure an enabled
     * protection service is actually running.
     */
    fun onResume() {
        repository.ensureProtectionServiceRunning()
        refresh()
    }

    fun refresh() {
        val token = ++refreshJob
        viewModelScope.launch {
            val status = repository.readStatus()
            val history = repository.history()
            if (token != refreshJob) return@launch
            _state.update { it.copy(loading = false, status = status, history = history.take(3)) }
            if (!recoveryOffered) {
                status.pendingInterrupted?.let { pending ->
                    recoveryOffered = true
                    _state.update { it.copy(recoveryPrompt = pending) }
                }
            }
        }
    }

    fun setProtectionEnabled(enabled: Boolean) {
        if (enabled && _state.value.status?.canWrite != true) {
            // A missing permission is a precondition, never an attempted operation.
            say(R.string.permission_missing)
            emit(HomeEvent.OpenWriteSettings)
            return
        }
        repository.setProtectionEnabled(enabled)
        say(if (enabled) R.string.service_started else R.string.service_stopped)
        if (enabled && repository.canShowPersistentNotification().not()) {
            offerNotificationAccess()
        }
        refresh()
    }

    fun setPersistentNotification(enabled: Boolean) {
        repository.setPersistentNotification(enabled)
        if (enabled) {
            if (repository.ensureNotificationChannel() && !repository.canShowPersistentNotification()) {
                offerNotificationAccess(delayMillis = 900L)
            } else if (!repository.canShowPersistentNotification()) {
                emit(HomeEvent.OpenNotificationSettings)
            }
        }
        refresh()
    }

    /**
     * Android only decides notification usability after the channel exists, and the
     * permission dialog is asynchronous. Re-checking before opening settings avoids
     * sending the user to a screen they no longer need.
     */
    private fun offerNotificationAccess(delayMillis: Long = 0L) {
        viewModelScope.launch {
            if (delayMillis > 0L) delay(delayMillis)
            if (!repository.canShowPersistentNotification()) {
                emit(HomeEvent.OpenNotificationSettings)
            }
            refresh()
        }
    }

    fun repair(confirmEmptyWhitelist: Boolean = false) {
        if (_state.value.busy) return
        startOperation {
            val outcome = repository.repair(confirmEmptyWhitelist)
            when (outcome) {
                is RepairOutcome.Applied -> {
                    repository.wakeFcm()
                    say(R.string.result_applied)
                }
                is RepairOutcome.ConfirmationRequired ->
                    _state.update { it.copy(emptyWhitelistPrompt = outcome) }
                is RepairOutcome.RecoveryRequired ->
                    _state.update { it.copy(recoveryPrompt = outcome.pending) }
                // Outcomes that need an explanation open the result dialog: it carries the
                // suggested recovery action and the captured technical detail.
                else -> _state.update {
                    it.copy(resultPrompt = OutcomeExplanations.repair(outcome))
                }
            }
        }
    }

    fun requestRestore() {
        val snapshot = _state.value.status?.latestSnapshot
        if (snapshot == null) {
            say(R.string.restore_none)
            return
        }
        _state.update { it.copy(restorePrompt = snapshot) }
    }

    fun confirmRestore(snapshot: Snapshot) {
        _state.update { it.copy(restorePrompt = null) }
        restoreNow(snapshot.id)
    }

    fun dismissRestorePrompt() = _state.update { it.copy(restorePrompt = null) }

    fun dismissEmptyWhitelistPrompt() = _state.update { it.copy(emptyWhitelistPrompt = null) }

    fun dismissResultPrompt() = _state.update { it.copy(resultPrompt = null) }

    fun confirmEmptyWhitelist() {
        if (_state.value.emptyWhitelistPrompt == null) return
        _state.update { it.copy(emptyWhitelistPrompt = null) }
        // A confirmed repair writes exactly the required item the engine reported back.
        repair(confirmEmptyWhitelist = true)
    }

    fun dismissRecovery() {
        recoveryOffered = true
        viewModelScope.launch {
            repository.dismissInterrupted()
            _state.update { it.copy(recoveryPrompt = null) }
            refresh()
        }
    }

    /** Recovery is never automatic: the user chose to restore the saved value. */
    fun restoreFromRecovery() {
        val pending = _state.value.recoveryPrompt ?: return
        val snapshotId = pending.snapshotId
        _state.update { it.copy(recoveryPrompt = null) }
        viewModelScope.launch {
            repository.dismissInterrupted()
            if (snapshotId.isNullOrEmpty()) {
                say(R.string.restore_none)
                refresh()
                return@launch
            }
            restoreNow(snapshotId)
        }
    }

    fun wakeFcm() {
        val sent = repository.wakeFcm()
        say(if (sent) R.string.wake_sent else R.string.wake_unavailable)
    }

    private fun restoreNow(snapshotId: String) {
        if (_state.value.busy) return
        startOperation {
            val outcome = repository.restore(snapshotId)
            if (outcome is RestoreOutcome.Restored) {
                repository.wakeFcm()
                say(R.string.restore_done)
            } else {
                _state.update {
                    it.copy(resultPrompt = OutcomeExplanations.restore(outcome))
                }
            }
        }
    }

    /**
     * Runs one verified operation and mirrors its journaled stage while it is in flight.
     * The stage shown is always a state the engine itself wrote; when it cannot be read
     * the UI falls back to "in progress" rather than guessing.
     */
    private fun startOperation(block: suspend () -> Unit) {
        _state.update { it.copy(busy = true, stage = null) }
        viewModelScope.launch {
            val stagePoll = launch {
                while (isActive) {
                    // The journal is persisted in SharedPreferences; never read it on the
                    // frame thread while an operation is writing it.
                    val stage = withContext(Dispatchers.IO) { repository.currentStage() }
                    _state.update { it.copy(stage = stage) }
                    delay(STAGE_POLL_MS)
                }
            }
            try {
                block()
            } finally {
                stagePoll.cancel()
                _state.update { it.copy(busy = false, stage = null) }
                refresh()
            }
        }
    }

    private fun say(resId: Int) {
        messageChannel.trySend(uiContext.getString(resId))
    }

    private fun emit(event: HomeEvent) {
        eventChannel.trySend(event)
    }

    private companion object {
        const val STAGE_POLL_MS = 120L
    }
}
