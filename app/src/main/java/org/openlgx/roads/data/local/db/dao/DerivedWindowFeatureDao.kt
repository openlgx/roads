package org.openlgx.roads.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import org.openlgx.roads.data.local.db.entity.DerivedWindowFeatureEntity

@Dao
interface DerivedWindowFeatureDao {

    @Insert
    suspend fun insert(row: DerivedWindowFeatureEntity): Long

    @Insert
    suspend fun insertAll(rows: List<DerivedWindowFeatureEntity>): List<Long>

    @Query("SELECT COUNT(*) FROM derived_window_features")
    suspend fun count(): Long

    @Query("SELECT COUNT(*) FROM derived_window_features WHERE sessionId = :sessionId")
    suspend fun countForSession(sessionId: Long): Long

    @Query(
        "SELECT * FROM derived_window_features WHERE sessionId = :sessionId ORDER BY windowIndex ASC, id ASC",
    )
    suspend fun listForSessionOrdered(sessionId: Long): List<DerivedWindowFeatureEntity>

    @Query(
        "SELECT * FROM derived_window_features WHERE sessionId = :sessionId ORDER BY windowIndex ASC, id ASC",
    )
    fun observeForSessionOrdered(sessionId: Long): Flow<List<DerivedWindowFeatureEntity>>

    @Query("DELETE FROM derived_window_features WHERE sessionId = :sessionId")
    suspend fun deleteForSession(sessionId: Long): Int
}
