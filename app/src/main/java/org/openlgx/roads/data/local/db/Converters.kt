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
    fun toSessionState(value: String): SessionState =
        try {
            SessionState.valueOf(value)
        } catch (_: IllegalArgumentException) {
            SessionState.COMPLETED
        }

    @TypeConverter
    fun fromRecordingSource(value: RecordingSource): String = value.name

    @TypeConverter
    fun toRecordingSource(value: String): RecordingSource =
        try {
            RecordingSource.valueOf(value)
        } catch (_: IllegalArgumentException) {
            RecordingSource.AUTO
        }

    @TypeConverter
    fun fromSessionUploadState(value: SessionUploadState): String = value.name

    @TypeConverter
    fun toSessionUploadState(value: String): SessionUploadState =
        try {
            SessionUploadState.valueOf(value)
        } catch (_: IllegalArgumentException) {
            SessionUploadState.NOT_QUEUED
        }

    @TypeConverter
    fun fromBatchUploadState(value: BatchUploadState): String = value.name

    @TypeConverter
    fun toBatchUploadState(value: String): BatchUploadState =
        try {
            BatchUploadState.valueOf(value)
        } catch (_: IllegalArgumentException) {
            BatchUploadState.PENDING_POLICY
        }

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
    fun toAnomalyType(value: String): AnomalyType =
        try {
            AnomalyType.valueOf(value)
        } catch (_: IllegalArgumentException) {
            AnomalyType.UNKNOWN_EXPERIMENTAL
        }

    @TypeConverter
    fun fromSessionProcessingState(value: SessionProcessingState): String = value.name

    @TypeConverter
    fun toSessionProcessingState(value: String): SessionProcessingState =
        try {
            SessionProcessingState.valueOf(value)
        } catch (_: IllegalArgumentException) {
            SessionProcessingState.NOT_STARTED
        }

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
