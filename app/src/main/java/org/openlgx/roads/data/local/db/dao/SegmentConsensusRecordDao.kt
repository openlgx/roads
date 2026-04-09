package org.openlgx.roads.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import org.openlgx.roads.data.local.db.entity.SegmentConsensusRecordEntity

@Dao
interface SegmentConsensusRecordDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(row: SegmentConsensusRecordEntity): Long

    @Query("SELECT COUNT(*) FROM segment_consensus_records")
    suspend fun count(): Long
}
