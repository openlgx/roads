package org.openlgx.roads.data.local.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import org.openlgx.roads.data.local.db.model.RoadEligibilityDisposition

@Entity(
    tableName = "location_samples",
    foreignKeys = [
        ForeignKey(
            entity = RecordingSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId")],
)
data class LocationSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val elapsedRealtimeNanos: Long,
    val wallClockUtcEpochMs: Long,
    val latitude: Double,
    val longitude: Double,
    val speedMps: Float? = null,
    val bearingDegrees: Float? = null,
    val horizontalAccuracyMeters: Float? = null,
    val altitudeMeters: Double? = null,
    val roadEligibilityDisposition: RoadEligibilityDisposition = RoadEligibilityDisposition.UNKNOWN,
    val eligibilityConfidence: Float? = null,
    /**
     * Phase 1: always true in practice — marks architecture intent to retain uncertain samples
     * until later processing/export discards.
     */
    val retainedRegardlessOfEligibility: Boolean = true,
)
