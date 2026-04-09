package org.openlgx.roads.data.local.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "segment_consensus_records",
    indices = [Index(value = ["segmentKey"], unique = true)],
)
data class SegmentConsensusRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val segmentKey: String,
    val observedCount: Int = 0,
    val distinctDeviceCount: Int = 0,
    val consensusRoughnessProxy: Double? = null,
    val stabilityScore: Float? = null,
    val methodVersion: String? = null,
    val lastUpdatedEpochMs: Long? = null,
)
