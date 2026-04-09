package org.openlgx.roads.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import org.openlgx.roads.data.local.db.entity.RecordingSessionEntity
import org.openlgx.roads.data.local.db.model.SessionState

@Dao
interface RecordingSessionDao {

    @Insert
    suspend fun insert(session: RecordingSessionEntity): Long

    @Query("SELECT COUNT(*) FROM recording_sessions")
    suspend fun count(): Long

    @Query(
        "SELECT * FROM recording_sessions WHERE endedAtEpochMs IS NULL ORDER BY id DESC LIMIT 1",
    )
    suspend fun findOpenSession(): RecordingSessionEntity?

    @Query(
        "UPDATE recording_sessions SET endedAtEpochMs = :endedAtEpochMs, state = :state WHERE id = :sessionId",
    )
    suspend fun endSession(sessionId: Long, endedAtEpochMs: Long, state: SessionState)
}
