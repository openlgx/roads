package org.openlgx.roads.data.repo

import org.openlgx.roads.data.local.settings.AppSettings
import org.openlgx.roads.sensor.SensorAvailabilitySnapshot

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
    /** Last Activity Transition API label, e.g. "In vehicle · enter". */
    val lastActivityTransitionSummary: String,
    val batteryOptimizationExempt: Boolean,
    val foregroundServiceRunning: Boolean,
    val openRecordingSessionId: Long?,
    val fineLocationPermissionGranted: Boolean,
    val activeSessionLocationSamples: Long,
    val lastLocationWallClockEpochMs: Long?,
    val lastLocationSpeedMps: Float?,
)

data class SensorRuntimeDiagnostics(
    val hardwareAvailable: SensorAvailabilitySnapshot,
    val captureActive: Boolean,
    val activeSessionId: Long?,
    /** Rows in DB for the open session, or UI published count when it matches that session. */
    val activeSessionSampleCount: Long,
    val bufferedSampleCount: Int,
    val lastSensorWallClockEpochMs: Long?,
    val estimatedCallbacksPerSecond: Float?,
    val degradedRecording: Boolean,
    val degradedReason: String?,
)

data class ExportDiagnosticsSnapshot(
    val lastExportDirectoryPath: String?,
    val lastExportZipPath: String?,
    val lastExportEpochMs: Long?,
    val lastExportSuccess: Boolean,
    val lastExportError: String?,
    val lastExportedSessionId: Long?,
)

data class DiagnosticsSnapshot(
    val appVersionName: String,
    val pilotBootstrapSummary: String,
    val roadPackExpectedPath: String,
    val hostedUploadReadinessLines: List<String>,
    val deviceModel: String,
    val sdkInt: Int,
    val manufacturer: String,
    val settings: AppSettings,
    val tableCounts: TableCounts,
    val pendingUploadBatches: Long,
    val latestDbUploadSuccessEpochMs: Long?,
    val collectorRuntime: CollectorRuntimeDiagnostics,
    val sensorRuntime: SensorRuntimeDiagnostics,
    /** Typical Phase 2C export directory: `<externalFiles>/olgx_exports`. */
    val exportRootDirectory: String,
    val exportDiagnostics: ExportDiagnosticsSnapshot,
    val dataQualityWarnings: List<String>,
    val lastUploadSummaryPlaceholder: String,
)

interface DiagnosticsRepository {
    suspend fun loadSnapshot(): DiagnosticsSnapshot
}
