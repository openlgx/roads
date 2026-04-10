package org.openlgx.roads.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import org.openlgx.roads.data.local.db.entity.AnomalyCandidateEntity

@Dao
interface AnomalyCandidateDao {

    @Insert
    suspend fun insert(row: AnomalyCandidateEntity): Long

    @Insert
    suspend fun insertAll(rows: List<AnomalyCandidateEntity>): List<Long>

    @Query("SELECT COUNT(*) FROM anomaly_candidates")
    suspend fun count(): Long

    @Query("SELECT COUNT(*) FROM anomaly_candidates WHERE sessionId = :sessionId")
    suspend fun countForSession(sessionId: Long): Long

    @Query(
        "SELECT * FROM anomaly_candidates WHERE sessionId = :sessionId " +
            "ORDER BY wallClockUtcEpochMs ASC, id ASC",
    )
    suspend fun listForSessionOrdered(sessionId: Long): List<AnomalyCandidateEntity>

    @Query(
        "SELECT * FROM anomaly_candidates WHERE sessionId = :sessionId " +
            "ORDER BY wallClockUtcEpochMs ASC, id ASC",
    )
    fun observeForSessionOrdered(sessionId: Long): Flow<List<AnomalyCandidateEntity>>

    @Query("DELETE FROM anomaly_candidates WHERE sessionId = :sessionId")
    suspend fun deleteForSession(sessionId: Long): Int
}
