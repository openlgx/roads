package org.openlgx.roads.data.local.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import org.openlgx.roads.data.local.db.model.RoadEligibilityDisposition

@Entity(
    tableName = "sensor_samples",
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
data class SensorSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val elapsedRealtimeNanos: Long,
    val wallClockUtcEpochMs: Long,
    val sensorType: Int,
    val x: Float,
    val y: Float,
    val z: Float,
    val w: Float? = null,
    val accuracy: Int? = null,
    val roadEligibilityDisposition: RoadEligibilityDisposition = RoadEligibilityDisposition.UNKNOWN,
    val eligibilityConfidence: Float? = null,
)
