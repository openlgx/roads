package org.openlgx.roads.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import org.openlgx.roads.data.local.db.entity.AnomalyCandidateEntity

@Dao
interface AnomalyCandidateDao {

    @Insert
    suspend fun insert(row: AnomalyCandidateEntity): Long

    @Query("SELECT COUNT(*) FROM anomaly_candidates")
    suspend fun count(): Long
}
