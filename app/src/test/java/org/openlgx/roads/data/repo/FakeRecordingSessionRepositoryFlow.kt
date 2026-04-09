package org.openlgx.roads.data.repo

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.openlgx.roads.data.local.db.model.SessionState

/**
 * Test double for HomeViewModel tests; [setSessionCount] drives the observe flow.
 */
class FakeRecordingSessionRepositoryFlow(
    initialSessionCount: Long = 0L,
) : RecordingSessionRepository {
    private val count = MutableStateFlow(initialSessionCount)

    fun setSessionCount(value: Long) {
        count.value = value
    }

    override suspend fun beginAutoRecordingSession(params: AutoRecordingSessionStartParams): Long = 1L

    override suspend fun updateSensorCaptureSnapshot(
        sessionId: Long,
        json: String?,
    ) = Unit

    override suspend fun endRecordingSession(
        sessionId: Long,
        endedAtEpochMs: Long,
        endState: SessionState,
    ) = Unit

    override suspend fun countSessions(): Long = count.value

    override suspend fun findOpenSessionId(): Long? = null

    override fun observeRecordingSessionCount(): Flow<Long> = count.asStateFlow()
}
