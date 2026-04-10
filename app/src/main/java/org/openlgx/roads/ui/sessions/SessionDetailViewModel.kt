package org.openlgx.roads.ui.sessions

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.openlgx.roads.data.local.settings.AppSettingsRepository
import org.openlgx.roads.data.repo.session.SessionDetail
import org.openlgx.roads.data.repo.session.SessionInspectionRepository
import org.openlgx.roads.export.SessionExporter
import org.openlgx.roads.processing.ondevice.SessionProcessingScheduler
import org.openlgx.roads.upload.HostedPipelineDisplayMapper
import org.openlgx.roads.upload.HostedPipelineDisplayState

@HiltViewModel
class SessionDetailViewModel
@Inject
constructor(
    private val sessionInspectionRepository: SessionInspectionRepository,
    private val sessionExporter: SessionExporter,
    private val sessionProcessingScheduler: SessionProcessingScheduler,
    private val appSettingsRepository: AppSettingsRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val sessionId: Long = savedStateHandle.get<Long>("sessionId") ?: -1L

    private val _detail = MutableStateFlow<SessionDetail?>(null)
    val detail: StateFlow<SessionDetail?> = _detail.asStateFlow()

    private val _hostedPipelineDisplay = MutableStateFlow<HostedPipelineDisplayState?>(null)
    val hostedPipelineDisplay: StateFlow<HostedPipelineDisplayState?> =
        _hostedPipelineDisplay.asStateFlow()

    private val _exportMessage = MutableStateFlow<String?>(null)
    val exportMessage: StateFlow<String?> = _exportMessage.asStateFlow()

    private val _reprocessMessage = MutableStateFlow<String?>(null)
    val reprocessMessage: StateFlow<String?> = _reprocessMessage.asStateFlow()

    init {
        if (sessionId > 0) {
            refresh()
        }
    }

    fun refresh() {
        if (sessionId <= 0) return
        viewModelScope.launch {
            val d = sessionInspectionRepository.getSessionDetail(sessionId)
            _detail.value = d
            if (d != null) {
                val settings = appSettingsRepository.settings.first()
                _hostedPipelineDisplay.value =
                    HostedPipelineDisplayMapper.forSession(
                        d.session,
                        d.latestUploadBatch,
                        settings.uploadEnabled,
                        settings.uploadAutoAfterSessionEnabled,
                    )
            } else {
                _hostedPipelineDisplay.value = null
            }
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

    fun requestReprocess() {
        if (sessionId <= 0) return
        sessionProcessingScheduler.requestReprocess(sessionId)
        _reprocessMessage.value =
            "Reprocess requested. If processing is already running, this session will run again when it finishes."
    }
}
