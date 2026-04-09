package org.openlgx.roads.data.repo

import org.openlgx.roads.data.local.settings.AppSettings

data class TableCounts(
    val deviceProfiles: Long,
    val recordingSessions: Long,
    val locationSamples: Long,
    val sensorSamples: Long,
    val uploadBatches: Long,
    val derivedWindowFeatures: Long,
    val anomalyCandidates: Long,
    val segmentConsensusRecords: Long,
    val calibrationRuns: Long,
)

data class CollectorRuntimeDiagnostics(
    val lifecycleState: String,
    val activityRecognitionSupported: Boolean,
    val activityRecognitionPermissionGranted: Boolean,
    val activityRecognitionUpdatesActive: Boolean,
    val likelyInVehicle: Boolean,
    val foregroundServiceRunning: Boolean,
    val openRecordingSessionId: Long?,
)

data class DiagnosticsSnapshot(
    val appVersionName: String,
    val deviceModel: String,
    val sdkInt: Int,
    val manufacturer: String,
    val settings: AppSettings,
    val tableCounts: TableCounts,
    val pendingUploadBatches: Long,
    val latestDbUploadSuccessEpochMs: Long?,
    val collectorRuntime: CollectorRuntimeDiagnostics,
    val lastUploadSummaryPlaceholder: String,
)

interface DiagnosticsRepository {
    suspend fun loadSnapshot(): DiagnosticsSnapshot
}
