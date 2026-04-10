package org.openlgx.roads.data.repo.session

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.openlgx.roads.data.local.db.model.SessionListStats

class FakeSessionInspectionRepository(
    initialSessions: List<SessionListStats> = emptyList(),
) : SessionInspectionRepository {

    private val sessions = MutableStateFlow(initialSessions)

    override fun observeSessionList(): Flow<List<SessionListStats>> = sessions.asStateFlow()

    override suspend fun getSessionDetail(sessionId: Long): SessionDetail? = null

    fun setSessions(list: List<SessionListStats>) {
        sessions.value = list
    }
}
