package org.openlgx.roads.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import org.openlgx.roads.data.local.db.entity.RecordingSessionEntity
import org.openlgx.roads.data.local.db.model.SessionListStats
import org.openlgx.roads.data.local.db.model.SessionHostedPipelineState
import org.openlgx.roads.data.local.db.model.SessionProcessingState
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

    @Query("SELECT * FROM recording_sessions WHERE id = :sessionId LIMIT 1")
    fun observeById(sessionId: Long): Flow<RecordingSessionEntity?>

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
          ) AS sensorSampleCount,
          s.processingState AS processingState,
          IFNULL(
            (SELECT COUNT(*) FROM derived_window_features d WHERE d.sessionId = s.id),
            0
          ) AS derivedWindowCount,
          IFNULL(
            (SELECT COUNT(*) FROM anomaly_candidates a WHERE a.sessionId = s.id),
            0
          ) AS anomalyCount,
          s.roughnessProxyScore AS roughnessProxyScore,
          s.processingLastError AS processingLastError,
          s.hostedPipelineState AS hostedPipelineState
        FROM recording_sessions s
        ORDER BY s.startedAtEpochMs DESC
        """,
    )
    fun observeSessionListStats(): Flow<List<SessionListStats>>

    /**
     * Completed sessions eligible for hosted upload (never attempted, failed, skipped,
     * or stuck mid-upload). Used by manual backfill; oldest first.
     *
     * `UPLOADING` is included because a crash/kill between setting UPLOADING and
     * writing the batch row leaves the session in a dead-end state that no automatic
     * retry will ever recover.
     */
    @Query(
        """
        SELECT id FROM recording_sessions
        WHERE state = 'COMPLETED'
          AND hostedPipelineState IN ('NOT_STARTED', 'FAILED', 'UPLOAD_SKIPPED', 'UPLOADING')
        ORDER BY startedAtEpochMs ASC
        """,
    )
    suspend fun listSessionIdsEligibleForUpload(): List<Long>

    @Query("SELECT * FROM recording_sessions ORDER BY startedAtEpochMs DESC")
    suspend fun listAllSessionsOrdered(): List<RecordingSessionEntity>

    /**
     * Completed drives that should get on-device roughness windows:
     * never ran, or failed (e.g. old bug) and can be retried automatically.
     */
    @Query(
        """
        SELECT id FROM recording_sessions
        WHERE state = 'COMPLETED'
          AND processingState IN ('NOT_STARTED', 'FAILED')
          AND (
            SELECT COUNT(*) FROM location_samples l WHERE l.sessionId = recording_sessions.id
          ) >= 2
        ORDER BY startedAtEpochMs DESC
        LIMIT 80
        """,
    )
    suspend fun listCompletedSessionIdsPendingProcessing(): List<Long>

    @Query(
        "UPDATE recording_sessions SET endedAtEpochMs = :endedAtEpochMs, state = :state WHERE id = :sessionId",
    )
    suspend fun endSession(sessionId: Long, endedAtEpochMs: Long, state: SessionState)

    @Query(
        "UPDATE recording_sessions SET sensorCaptureSnapshotJson = :json WHERE id = :sessionId",
    )
    suspend fun updateSensorCaptureSnapshot(sessionId: Long, json: String?)

    @Query(
        """
        UPDATE recording_sessions SET
          processingState = :processingState,
          processingStartedAtEpochMs = :processingStartedAtEpochMs,
          processingCompletedAtEpochMs = :processingCompletedAtEpochMs,
          processingLastError = :processingLastError,
          processingSummaryJson = :processingSummaryJson
        WHERE id = :sessionId
        """,
    )
    suspend fun updateProcessingFields(
        sessionId: Long,
        processingState: SessionProcessingState,
        processingStartedAtEpochMs: Long?,
        processingCompletedAtEpochMs: Long?,
        processingLastError: String?,
        processingSummaryJson: String?,
    )

    @Query(
        """
        UPDATE recording_sessions SET
          roughnessProxyScore = :roughnessProxy,
          roughnessMethodVersion = :roughnessMethodVersion,
          roadResponseScore = :roadResponse,
          roadResponseMethodVersion = :roadResponseMethodVersion
        WHERE id = :sessionId
        """,
    )
    suspend fun updateAggregateScores(
        sessionId: Long,
        roughnessProxy: Double?,
        roughnessMethodVersion: String?,
        roadResponse: Double?,
        roadResponseMethodVersion: String?,
    )

    @Query(
        "UPDATE recording_sessions SET hostedPipelineState = :state WHERE id = :sessionId",
    )
    suspend fun updateHostedPipelineState(
        sessionId: Long,
        state: SessionHostedPipelineState,
    )
}
