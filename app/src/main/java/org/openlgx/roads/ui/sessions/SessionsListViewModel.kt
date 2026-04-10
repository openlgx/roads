package org.openlgx.roads.ui.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.openlgx.roads.data.local.db.model.SessionListStats
import org.openlgx.roads.data.repo.session.SessionInspectionRepository
import org.openlgx.roads.processing.ondevice.SessionProcessingScheduler
import org.openlgx.roads.ui.review.ReviewPayloadBuilder

@HiltViewModel
class SessionsListViewModel
@Inject
constructor(
    sessionInspectionRepository: SessionInspectionRepository,
    private val reviewPayloadBuilder: ReviewPayloadBuilder,
    private val sessionProcessingScheduler: SessionProcessingScheduler,
) : ViewModel() {

    val sessions: StateFlow<List<SessionListStats>> =
        sessionInspectionRepository.observeSessionList().stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = emptyList(),
        )

    /**
     * Sessions **not** drawn on the overview map. Empty = show every trip with enough GPS.
     */
    private val _excludedFromMapSessionIds = MutableStateFlow<Set<Long>>(emptySet())
    val excludedFromMapSessionIds: StateFlow<Set<Long>> = _excludedFromMapSessionIds.asStateFlow()

    private val _mapPayloadBase64 = MutableStateFlow<String?>(null)
    val mapPayloadBase64: StateFlow<String?> = _mapPayloadBase64.asStateFlow()

    private val _mapError = MutableStateFlow<String?>(null)
    val mapError: StateFlow<String?> = _mapError.asStateFlow()

    init {
        sessionProcessingScheduler.requestBackfillPendingProcessing()
    }

    fun toggleTripOnMap(sessionId: Long) {
        _excludedFromMapSessionIds.update { cur ->
            if (sessionId in cur) cur - sessionId else cur + sessionId
        }
    }

    fun showAllTripsOnMap() {
        _excludedFromMapSessionIds.value = emptySet()
    }

    /** Show exactly one trip’s route and roughness on the map. */
    fun showOnlyTripOnMap(sessionId: Long) {
        val others = sessions.value.map { it.id }.filter { it != sessionId }.toSet()
        _excludedFromMapSessionIds.value = others
    }

    fun refreshMap() {
        viewModelScope.launch {
            val excluded = _excludedFromMapSessionIds.value
            runCatching {
                val json = reviewPayloadBuilder.buildSessionsOverviewMapPayload(excluded)
                _mapPayloadBase64.value = reviewPayloadBuilder.encodeForWebView(json)
                _mapError.value = null
            }.onFailure { _mapError.value = it.message }
        }
    }
}
