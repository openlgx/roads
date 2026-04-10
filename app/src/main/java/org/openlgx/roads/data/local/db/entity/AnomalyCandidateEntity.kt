package org.openlgx.roads.data.local.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import org.openlgx.roads.data.local.db.model.AnomalyType

@Entity(
    tableName = "anomaly_candidates",
    foreignKeys = [
        ForeignKey(
            entity = RecordingSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("sessionId"),
        Index(value = ["sessionId", "wallClockUtcEpochMs"]),
    ],
)
data class AnomalyCandidateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val timeElapsedRealtimeNanos: Long? = null,
    val anomalyType: AnomalyType,
    val score: Double? = null,
    val confidence: Float? = null,
    val methodVersion: String? = null,
    val qualityFlags: Int = 0,
    val detailsJson: String? = null,
    val wallClockUtcEpochMs: Long? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val speedMps: Double? = null,
    val headingDeg: Double? = null,
    val severityBucket: String? = null,
    val derivedWindowId: Long? = null,
)
