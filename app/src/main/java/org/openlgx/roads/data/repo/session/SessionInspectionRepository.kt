package org.openlgx.roads.data.repo.session

import kotlinx.coroutines.flow.Flow
import org.openlgx.roads.data.local.db.entity.RecordingSessionEntity
import org.openlgx.roads.data.local.db.model.SessionListStats
import org.openlgx.roads.validation.SessionCaptureValidationSummary

data class SessionDetail(
    val session: RecordingSessionEntity,
    val locationSampleCount: Long,
    val sensorSampleCount: Long,
    val lastLocationWallClockMs: Long?,
    val lastSensorWallClockMs: Long?,
    /** End time for duration: [RecordingSessionEntity.endedAtEpochMs] or now if still active. */
    val durationMs: Long,
    val validation: SessionCaptureValidationSummary,
)

interface SessionInspectionRepository {
    fun observeSessionList(): Flow<List<SessionListStats>>

    suspend fun getSessionDetail(sessionId: Long): SessionDetail?
}
