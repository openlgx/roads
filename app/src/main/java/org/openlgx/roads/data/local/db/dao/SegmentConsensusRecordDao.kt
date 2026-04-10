package org.openlgx.roads.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import org.openlgx.roads.data.local.db.entity.SegmentConsensusRecordEntity

@Dao
interface SegmentConsensusRecordDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(row: SegmentConsensusRecordEntity): Long

    @Query("SELECT COUNT(*) FROM segment_consensus_records")
    suspend fun count(): Long

    @Query("SELECT * FROM segment_consensus_records ORDER BY lastUpdatedEpochMs DESC")
    suspend fun listAllOrderByUpdated(): List<SegmentConsensusRecordEntity>

    @Query("SELECT * FROM segment_consensus_records ORDER BY lastUpdatedEpochMs DESC")
    fun observeAllOrderByUpdated(): Flow<List<SegmentConsensusRecordEntity>>

    @Query("SELECT * FROM segment_consensus_records WHERE segmentKey = :key LIMIT 1")
    suspend fun getByKey(key: String): SegmentConsensusRecordEntity?

    @Query(
        """
        UPDATE segment_consensus_records SET
          observedCount = :observedCount,
          consensusRoughnessProxy = :proxy,
          lastUpdatedEpochMs = :updatedAt
        WHERE segmentKey = :key
        """,
    )
    suspend fun updateConsensusStats(
        key: String,
        observedCount: Int,
        proxy: Double,
        updatedAt: Long,
    ): Int
}
