package org.openlgx.roads.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import org.openlgx.roads.data.local.db.entity.RecordingSessionEntity
import org.openlgx.roads.data.local.db.model.SessionListStats
import org.openlgx.roads.data.local.db.model.SessionState

@Dao
interface RecordingSessionDao {

    @Insert
    suspend fun insert(session: RecordingSessionEntity): Long

    @Query("SELECT COUNT(*) FROM recording_sessions")
    suspend fun count(): Long

    @Query("SELECT COUNT(*) FROM recording_sessions")
    fun observeRecordingSessionCount(): Flow<Long>

    @Query(
        "SELECT * FROM recording_sessions WHERE endedAtEpochMs IS NULL ORDER BY id DESC LIMIT 1",
    )
    suspend fun findOpenSession(): RecordingSessionEntity?

    @Query("SELECT * FROM recording_sessions WHERE id = :sessionId LIMIT 1")
    suspend fun getById(sessionId: Long): RecordingSessionEntity?

    @Query(
        """
        SELECT 
          s.id AS id,
          s.uuid AS uuid,
          s.startedAtEpochMs AS startedAtEpochMs,
          s.endedAtEpochMs AS endedAtEpochMs,
          s.state AS state,
          s.recordingSource AS recordingSource,
          IFNULL(
            (SELECT COUNT(*) FROM location_samples l WHERE l.sessionId = s.id),
            0
          ) AS locationSampleCount,
          IFNULL(
            (SELECT COUNT(*) FROM sensor_samples x WHERE x.sessionId = s.id),
            0
          ) AS sensorSampleCount
        FROM recording_sessions s
        ORDER BY s.startedAtEpochMs DESC
        """,
    )
    fun observeSessionListStats(): Flow<List<SessionListStats>>

    @Query(
        "UPDATE recording_sessions SET endedAtEpochMs = :endedAtEpochMs, state = :state WHERE id = :sessionId",
    )
    suspend fun endSession(sessionId: Long, endedAtEpochMs: Long, state: SessionState)

    @Query(
        "UPDATE recording_sessions SET sensorCaptureSnapshotJson = :json WHERE id = :sessionId",
    )
    suspend fun updateSensorCaptureSnapshot(sessionId: Long, json: String?)
}
