package org.openlgx.roads.ui.sessions

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.openlgx.roads.data.repo.session.SessionDetail
import org.openlgx.roads.data.repo.session.SessionInspectionRepository
import org.openlgx.roads.export.SessionExporter

@HiltViewModel
class SessionDetailViewModel
@Inject
constructor(
    private val sessionInspectionRepository: SessionInspectionRepository,
    private val sessionExporter: SessionExporter,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val sessionId: Long = savedStateHandle.get<Long>("sessionId") ?: -1L

    private val _detail = MutableStateFlow<SessionDetail?>(null)
    val detail: StateFlow<SessionDetail?> = _detail.asStateFlow()

    private val _exportMessage = MutableStateFlow<String?>(null)
    val exportMessage: StateFlow<String?> = _exportMessage.asStateFlow()

    init {
        if (sessionId > 0) {
            refresh()
        }
    }

    fun refresh() {
        if (sessionId <= 0) return
        viewModelScope.launch {
            _detail.value = sessionInspectionRepository.getSessionDetail(sessionId)
        }
    }

    fun exportSession() {
        if (sessionId <= 0) return
        viewModelScope.launch {
            _exportMessage.value = "Exporting…"
            val result = sessionExporter.exportSession(sessionId)
            _exportMessage.value =
                result.fold(
                    onSuccess = { r ->
                        "Exported to:\n${r.directory.absolutePath}\nZip:\n${r.zipFile.absolutePath}"
                    },
                    onFailure = { e ->
                        "Export failed: ${e.message}"
                    },
                )
        }
    }
}
