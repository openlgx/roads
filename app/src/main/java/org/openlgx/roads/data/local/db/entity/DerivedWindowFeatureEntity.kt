package org.openlgx.roads.data.local.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import org.openlgx.roads.data.local.db.model.RoadEligibilityDisposition

@Entity(
    tableName = "derived_window_features",
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
data class DerivedWindowFeatureEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val windowStartElapsedNanos: Long,
    val windowEndElapsedNanos: Long,
    val windowLengthMetersApprox: Double? = null,
    val methodVersion: String? = null,
    val isExperimental: Boolean = true,
    val roughnessProxyScore: Double? = null,
    val roadResponseScore: Double? = null,
    val qualityFlags: Int = 0,
    val roadEligibilityDisposition: RoadEligibilityDisposition = RoadEligibilityDisposition.UNKNOWN,
    val eligibilityConfidence: Float? = null,
    val featureBundleJson: String? = null,
)
