package org.openlgx.roads.ui.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.openlgx.roads.data.repo.DiagnosticsRepository
import org.openlgx.roads.data.repo.DiagnosticsSnapshot
import javax.inject.Inject

sealed interface DiagnosticsUiState {
    data object Loading : DiagnosticsUiState

    data class Ready(
        val snapshot: DiagnosticsSnapshot,
    ) : DiagnosticsUiState
}

@HiltViewModel
class DiagnosticsViewModel
@Inject
constructor(
    private val diagnosticsRepository: DiagnosticsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<DiagnosticsUiState>(DiagnosticsUiState.Loading)
    val uiState: StateFlow<DiagnosticsUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = DiagnosticsUiState.Loading
            _uiState.value = DiagnosticsUiState.Ready(diagnosticsRepository.loadSnapshot())
        }
    }
}
