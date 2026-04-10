package org.openlgx.roads.data.local.db

import androidx.room.TypeConverter
import org.openlgx.roads.data.local.db.model.AnomalyType
import org.openlgx.roads.data.local.db.model.BatchUploadState
import org.openlgx.roads.data.local.db.model.RecordingSource
import org.openlgx.roads.data.local.db.model.RoadEligibilityDisposition
import org.openlgx.roads.data.local.db.model.SessionHostedPipelineState
import org.openlgx.roads.data.local.db.model.SessionProcessingState
import org.openlgx.roads.data.local.db.model.SessionState
import org.openlgx.roads.data.local.db.model.SessionUploadState

class Converters {

    @TypeConverter
    fun fromSessionState(value: SessionState): String = value.name

    @TypeConverter
    fun toSessionState(value: String): SessionState = SessionState.valueOf(value)

    @TypeConverter
    fun fromRecordingSource(value: RecordingSource): String = value.name

    @TypeConverter
    fun toRecordingSource(value: String): RecordingSource = RecordingSource.valueOf(value)

    @TypeConverter
    fun fromSessionUploadState(value: SessionUploadState): String = value.name

    @TypeConverter
    fun toSessionUploadState(value: String): SessionUploadState = SessionUploadState.valueOf(value)

    @TypeConverter
    fun fromBatchUploadState(value: BatchUploadState): String = value.name

    @TypeConverter
    fun toBatchUploadState(value: String): BatchUploadState = BatchUploadState.valueOf(value)

    @TypeConverter
    fun fromRoadEligibilityDisposition(value: RoadEligibilityDisposition): String = value.name

    @TypeConverter
    fun toRoadEligibilityDisposition(value: String): RoadEligibilityDisposition =
        try {
            RoadEligibilityDisposition.valueOf(value)
        } catch (_: IllegalArgumentException) {
            RoadEligibilityDisposition.UNKNOWN
        }

    @TypeConverter
    fun fromAnomalyType(value: AnomalyType): String = value.name

    @TypeConverter
    fun toAnomalyType(value: String): AnomalyType = AnomalyType.valueOf(value)

    @TypeConverter
    fun fromSessionProcessingState(value: SessionProcessingState): String = value.name

    @TypeConverter
    fun toSessionProcessingState(value: String): SessionProcessingState =
        SessionProcessingState.valueOf(value)

    @TypeConverter
    fun fromSessionHostedPipelineState(value: SessionHostedPipelineState): String = value.name

    @TypeConverter
    fun toSessionHostedPipelineState(value: String): SessionHostedPipelineState =
        try {
            SessionHostedPipelineState.valueOf(value)
        } catch (_: IllegalArgumentException) {
            SessionHostedPipelineState.NOT_STARTED
        }
}
