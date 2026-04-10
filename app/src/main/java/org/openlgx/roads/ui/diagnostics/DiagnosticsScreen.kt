package org.openlgx.roads.ui.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    viewModel: DiagnosticsViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Diagnostics") },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text("Back")
                    }
                },
                actions = {
                    TextButton(onClick = viewModel::refresh) {
                        Text("Refresh")
                    }
                },
            )
        },
    ) { padding ->
        when (val s = state) {
            DiagnosticsUiState.Loading -> {
                Column(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(padding),
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            is DiagnosticsUiState.Ready -> {
                val snap = s.snapshot
                Column(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .padding(16.dp)
                            .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("App", style = MaterialTheme.typography.titleMedium)
                    Text("Version: ${snap.appVersionName}")
                    Text("Device", style = MaterialTheme.typography.titleMedium)
                    Text("Manufacturer: ${snap.manufacturer}")
                    Text("Model: ${snap.deviceModel}")
                    Text("SDK: ${snap.sdkInt}")

                    Text("Settings (capture vs upload)", style = MaterialTheme.typography.titleMedium)
                    Text("Onboarding completed: ${snap.settings.onboardingCompleted}")
                    Text("Passive user toggle: ${snap.settings.passiveCollectionUserEnabled}")
                    Text("Passive effective: ${snap.settings.passiveCollectionEffective}")
                    Text("Upload Wi‑Fi only: ${snap.settings.uploadWifiOnly}")
                    Text("Upload allow cellular: ${snap.settings.uploadAllowCellular}")
                    Text("Upload only while charging: ${snap.settings.uploadOnlyWhileCharging}")
                    Text(
                        "Upload pause on low battery: ${snap.settings.uploadPauseOnLowBatteryEnabled} " +
                            "(${snap.settings.uploadLowBatteryThresholdPercent}%)",
                    )
                    Text("Retention days (placeholder): ${snap.settings.retentionDays}")
                    Text("Max local storage MB (0 = unset): ${snap.settings.maxLocalStorageMb}")
                    Text("Local compaction enabled (placeholder): ${snap.settings.localCompactionEnabled}")
                    Text("Capture start speed (m/s): ${snap.settings.captureStartSpeedMps}")
                    Text("Capture immediate start speed (m/s): ${snap.settings.captureImmediateStartSpeedMps}")
                    Text("Capture stop speed (m/s): ${snap.settings.captureStopSpeedMps}")
                    Text("Stop hold (s): ${snap.settings.captureStopHoldSeconds}")
                    Text("Fast arming: ${snap.settings.captureFastArmingEnabled}")
                    Text("Processing window (s): ${snap.settings.processingWindowSeconds}")
                    Text("Debug mode: ${snap.settings.debugModeEnabled}")

                    Text("Database row counts", style = MaterialTheme.typography.titleMedium)
                    Text("device_profiles: ${snap.tableCounts.deviceProfiles}")
                    Text("recording_sessions: ${snap.tableCounts.recordingSessions}")
                    Text("location_samples: ${snap.tableCounts.locationSamples}")
                    Text("sensor_samples: ${snap.tableCounts.sensorSamples}")
                    Text("upload_batches: ${snap.tableCounts.uploadBatches}")
                    Text("derived_window_features: ${snap.tableCounts.derivedWindowFeatures}")
                    Text("anomaly_candidates: ${snap.tableCounts.anomalyCandidates}")
                    Text("segment_consensus_records: ${snap.tableCounts.segmentConsensusRecords}")
                    Text("calibration_runs: ${snap.tableCounts.calibrationRuns}")

                    Text("Upload queue", style = MaterialTheme.typography.titleMedium)
                    Text("Pending / retryable batches: ${snap.pendingUploadBatches}")

                    Text("Collector runtime (Phase 2A)", style = MaterialTheme.typography.titleMedium)
                    Text("Lifecycle state: ${snap.collectorRuntime.lifecycleState}")
                    Text(
                        "Activity recognition supported: ${snap.collectorRuntime.activityRecognitionSupported}",
                    )
                    Text(
                        "Activity recognition permission granted: ${snap.collectorRuntime.activityRecognitionPermissionGranted}",
                    )
                    Text("Activity recognition updates active: ${snap.collectorRuntime.activityRecognitionUpdatesActive}")
                    Text("Likely in vehicle (UI model): ${snap.collectorRuntime.likelyInVehicle}")
                    Text("Foreground service running: ${snap.collectorRuntime.foregroundServiceRunning}")
                    Text("Open recording session id (DB): ${snap.collectorRuntime.openRecordingSessionId ?: "—"}")
                    Text(
                        "Placeholder recording_sessions row count: ${snap.tableCounts.recordingSessions}",
                    )
                    Text("Fine location permission: ${snap.collectorRuntime.fineLocationPermissionGranted}")
                    Text(
                        "Location samples (open session): ${snap.collectorRuntime.activeSessionLocationSamples}",
                    )
                    Text(
                        "Last location epoch ms: ${snap.collectorRuntime.lastLocationWallClockEpochMs ?: "—"}",
                    )
                    Text(
                        "Last location speed (m/s): ${snap.collectorRuntime.lastLocationSpeedMps ?: "—"}",
                    )

                    Text("Motion / IMU (Phase 2B2)", style = MaterialTheme.typography.titleMedium)
                    val imu = snap.sensorRuntime
                    Text("Sensor capture active: ${imu.captureActive}")
                    Text(
                        "Hardware — accel=${imu.hardwareAvailable.accelerometer}, gyro=${imu.hardwareAvailable.gyroscope}, " +
                            "gravity=${imu.hardwareAvailable.gravity}, linear=${imu.hardwareAvailable.linearAcceleration}, " +
                            "rotVec=${imu.hardwareAvailable.rotationVector}",
                    )
                    Text("Active session (UI): ${imu.activeSessionId ?: "—"}")
                    Text("Samples (open session, best of DB vs UI): ${imu.activeSessionSampleCount}")
                    Text("Buffered (UI): ${imu.bufferedSampleCount}")
                    Text("Last sensor wall ms: ${imu.lastSensorWallClockEpochMs ?: "—"}")
                    Text("Est. callbacks/sec: ${imu.estimatedCallbacksPerSecond ?: "—"}")
                    Text("Degraded: ${imu.degradedRecording} — ${imu.degradedReason ?: "—"}")

                    Text("Exports (Phase 2C)", style = MaterialTheme.typography.titleMedium)
                    Text("Export root (app external files): ${snap.exportRootDirectory}")
                    Text("Last export time (epoch ms): ${snap.exportDiagnostics.lastExportEpochMs ?: "—"}")
                    Text("Last export success: ${snap.exportDiagnostics.lastExportSuccess}")
                    Text("Last exported session id: ${snap.exportDiagnostics.lastExportedSessionId ?: "—"}")
                    Text("Last export folder: ${snap.exportDiagnostics.lastExportDirectoryPath ?: "—"}")
                    Text("Last export zip: ${snap.exportDiagnostics.lastExportZipPath ?: "—"}")
                    Text("Last export error: ${snap.exportDiagnostics.lastExportError ?: "—"}")
                    Text(
                        "Sessions in DB: ${snap.tableCounts.recordingSessions} — " +
                            "locations: ${snap.tableCounts.locationSamples}, " +
                            "sensors: ${snap.tableCounts.sensorSamples}",
                    )
                    if (snap.dataQualityWarnings.isEmpty()) {
                        Text("Data quality warnings: none")
                    } else {
                        Text("Data quality warnings:")
                        snap.dataQualityWarnings.forEach { w ->
                            Text("• $w", style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    Text("Last upload summary: ${snap.lastUploadSummaryPlaceholder}")

                    Button(onClick = viewModel::refresh) {
                        Text("Refresh again")
                    }
                }
            }
        }
    }
}
