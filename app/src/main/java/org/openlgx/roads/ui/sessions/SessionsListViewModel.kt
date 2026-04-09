package org.openlgx.roads.ui.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import org.openlgx.roads.data.local.db.model.SessionListStats
import org.openlgx.roads.data.repo.session.SessionInspectionRepository

@HiltViewModel
class SessionsListViewModel
@Inject
constructor(
    sessionInspectionRepository: SessionInspectionRepository,
) : ViewModel() {

    val sessions: StateFlow<List<SessionListStats>> =
        sessionInspectionRepository.observeSessionList().stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = emptyList(),
        )
}
