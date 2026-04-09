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
                    Text("Capture min speed (m/s, placeholder): ${snap.settings.captureMinSpeedMps}")
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

                    Text("Last upload summary: ${snap.lastUploadSummaryPlaceholder}")

                    Button(onClick = viewModel::refresh) {
                        Text("Refresh again")
                    }
                }
            }
        }
    }
}
