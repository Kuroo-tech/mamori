package com.reed.fcmguard.ui.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.reed.fcmguard.data.MamoriRepository
import com.reed.fcmguard.execution.OperationRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HistoryUiState(
    val loading: Boolean = true,
    val records: List<OperationRecord> = emptyList()
)

/**
 * Reads the persisted operation journal. The screen therefore shows exactly the records
 * the engine wrote — no reconstruction from guesses, and no record that was never made.
 */
class HistoryViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MamoriRepository(application)
    private val _state = MutableStateFlow(HistoryUiState())
    val state: StateFlow<HistoryUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val records = repository.history()
            _state.update { it.copy(loading = false, records = records) }
        }
    }
}
