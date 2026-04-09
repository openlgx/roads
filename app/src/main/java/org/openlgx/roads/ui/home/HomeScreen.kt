package org.openlgx.roads.ui.home

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.openlgx.roads.collector.lifecycle.CollectorLifecycleState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onOpenSettings: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenSessions: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val collector = state.collector
    val context = LocalContext.current

    val activityRecognitionGranted =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

    val notificationsGranted =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

    val fineLocationGranted =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    val arLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { /* refresh via vm */ }

    val notifLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { /* refresh via vm */ }

    val fineLocationLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { /* refresh via vm */ }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("OLGX Roads") })
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Passive collection", style = MaterialTheme.typography.titleMedium)
            Text(
                "Effective (user + onboarding): ${state.passiveCollectionEffective}",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text("User toggle: ${state.passiveCollectionUserEnabled}", style = MaterialTheme.typography.bodyMedium)
            Text("Onboarding completed: ${state.onboardingCompleted}", style = MaterialTheme.typography.bodyMedium)

            Text("Collector runtime", style = MaterialTheme.typography.titleMedium)
            Text("Lifecycle state: ${collector.lifecycleState}", style = MaterialTheme.typography.bodyLarge)
            Text(
                "Likely in vehicle (heuristic): ${collector.likelyInVehicle}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Recording active: ${collector.recordingActive} (session id: ${collector.activeSessionId ?: "—"})",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Activity recognition: supported=${collector.activityRecognitionSupported}, " +
                    "updatesActive=${collector.activityRecognitionUpdatesActive}, " +
                    "last=${collector.lastActivityLabel} (${collector.lastActivityConfidence}%)",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Last lifecycle transition: ${collector.lastLifecycleTransitionLabel} " +
                    "(${collector.lastLifecycleTransitionEpochMs?.toString() ?: "—"})",
                style = MaterialTheme.typography.bodySmall,
            )
            Text("Foreground service running: ${collector.foregroundServiceRunning}", style = MaterialTheme.typography.bodyMedium)

            Text("Recording (Phase 2B1)", style = MaterialTheme.typography.titleMedium)
            Text("Recording sessions (DB count): ${state.recordingSessionCount}", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Location: published samples≈${state.locationRecording.publishedSampleCount}, " +
                    "buffered=${state.locationRecording.bufferedSampleCount}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Last location time (epoch ms): ${state.locationRecording.lastWallClockEpochMs ?: "—"}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Last speed (m/s): ${state.locationRecording.lastSpeedMps?.toString() ?: "—"}",
                style = MaterialTheme.typography.bodyMedium,
            )

            Text("IMU / motion (Phase 2B2)", style = MaterialTheme.typography.titleMedium)
            val s = state.sensorRecording
            Text(
                "Sensor capture active: ${s.recordingSensors} (degraded=${s.degradedRecording})",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (s.degradedReason != null) {
                Text("Sensor degraded: ${s.degradedReason}", style = MaterialTheme.typography.bodySmall)
            }
            Text(
                "Hardware OK — accel=${s.hardwareAvailable.accelerometer}, gyro=${s.hardwareAvailable.gyroscope}, " +
                    "gravity=${s.hardwareAvailable.gravity}, linear=${s.hardwareAvailable.linearAcceleration}, " +
                    "rotVec=${s.hardwareAvailable.rotationVector}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Subscribed this session — accel=${s.enabledSubscribed.accelerometer}, gyro=${s.enabledSubscribed.gyroscope}, " +
                    "gravity=${s.enabledSubscribed.gravity}, linear=${s.enabledSubscribed.linearAcceleration}, " +
                    "rotVec=${s.enabledSubscribed.rotationVector}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Sensor samples (published≈${s.publishedSampleCount}, buffered=${s.bufferedSampleCount})",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Last sensor time (epoch ms): ${s.lastWallClockEpochMs ?: "—"}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Est. callbacks/sec (flush window): ${s.estimatedCallbacksPerSecond?.toString() ?: "—"}",
                style = MaterialTheme.typography.bodySmall,
            )

            Text("Permissions", style = MaterialTheme.typography.titleMedium)
            Text("Activity recognition granted: $activityRecognitionGranted", style = MaterialTheme.typography.bodyMedium)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Button(
                    onClick = { arLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION) },
                    enabled = state.passiveCollectionEffective && !activityRecognitionGranted,
                ) {
                    Text("Request activity recognition permission")
                }
            }
            Text("Fine location granted: $fineLocationGranted", style = MaterialTheme.typography.bodyMedium)
            Button(
                onClick = { fineLocationLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION) },
                enabled = state.passiveCollectionEffective && !fineLocationGranted,
            ) {
                Text("Request fine location permission")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Text("Notifications granted: $notificationsGranted", style = MaterialTheme.typography.bodyMedium)
                Button(
                    onClick = { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                    enabled = state.passiveCollectionEffective && !notificationsGranted,
                ) {
                    Text("Request notification permission")
                }
            }

            Text("Upload policy", style = MaterialTheme.typography.titleMedium)
            Text(
                "Wi‑Fi only=${state.uploadWifiOnly}, cellular allowed=${state.uploadAllowCellular}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text("Upload queue: ${state.pendingUploadSummaryPlaceholder}", style = MaterialTheme.typography.bodyMedium)

            if (state.debugModeEnabled) {
                Text("Debug tools", style = MaterialTheme.typography.titleMedium)
                Button(onClick = { viewModel.debugSetSimulatedDriving(true) }) {
                    Text("Simulate driving: on")
                }
                Button(onClick = { viewModel.debugSetSimulatedDriving(false) }) {
                    Text("Simulate driving: off")
                }
                Button(onClick = { viewModel.debugSetSimulatedDriving(null) }) {
                    Text("Simulate driving: clear override")
                }
                Button(
                    onClick = { viewModel.debugForceStartRecording() },
                    enabled =
                        collector.lifecycleState != CollectorLifecycleState.RECORDING &&
                            collector.lifecycleState != CollectorLifecycleState.COOLDOWN,
                ) {
                    Text("Force start recording (manual/debug source)")
                }
                Button(onClick = { viewModel.debugResetCollector() }) {
                    Text("Reset collector state machine")
                }
            }

            Button(onClick = onOpenSettings) {
                Text("Settings")
            }
            Button(onClick = onOpenDiagnostics) {
                Text("Diagnostics")
            }
            Button(onClick = onOpenSessions) {
                Text("Recorded sessions")
            }
        }
    }
}
