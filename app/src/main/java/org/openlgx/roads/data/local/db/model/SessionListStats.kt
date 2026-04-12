package org.openlgx.roads.data.local.db.model

import androidx.room.ColumnInfo

/**
 * Denormalized list row for session browser (Phase 2C).
 */
data class SessionListStats(
    @ColumnInfo(name = "id") val id: Long,
    @ColumnInfo(name = "uuid") val uuid: String,
    @ColumnInfo(name = "startedAtEpochMs") val startedAtEpochMs: Long,
    @ColumnInfo(name = "endedAtEpochMs") val endedAtEpochMs: Long?,
    @ColumnInfo(name = "state") val state: SessionState,
    @ColumnInfo(name = "recordingSource") val recordingSource: RecordingSource,
    @ColumnInfo(name = "locationSampleCount") val locationSampleCount: Long,
    @ColumnInfo(name = "sensorSampleCount") val sensorSampleCount: Long,
    @ColumnInfo(name = "processingState") val processingState: SessionProcessingState,
    @ColumnInfo(name = "derivedWindowCount") val derivedWindowCount: Long,
    @ColumnInfo(name = "anomalyCount") val anomalyCount: Long,
    @ColumnInfo(name = "roughnessProxyScore") val roughnessProxyScore: Double?,
    @ColumnInfo(name = "processingLastError") val processingLastError: String?,
    @ColumnInfo(name = "hostedPipelineState") val hostedPipelineState: SessionHostedPipelineState,
)
