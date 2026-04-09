package org.openlgx.roads.ui.home

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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

    val activityRecognitionRequired = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    val notificationsRequired = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    val allRequiredPermissionsGranted =
        fineLocationGranted &&
            (!activityRecognitionRequired || activityRecognitionGranted) &&
            (!notificationsRequired || notificationsGranted)

    val passivePipelineListening =
        state.passiveCollectionEffective &&
            collector.activityRecognitionPermissionGranted &&
            collector.activityRecognitionUpdatesActive

    val passiveCollectionActive =
        state.passiveCollectionEffective &&
            allRequiredPermissionsGranted &&
            passivePipelineListening

    val arLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { /* refresh via vm */ }

    val notifLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { /* refresh via vm */ }

    val fineLocationLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { /* refresh via vm */ }

    var technicalDetailsExpanded by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("OLGX Roads") })
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(padding)
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(modifier = Modifier.height(4.dp))

            // --- Setup ---
            HomeSectionTitle("Setup")

            if (!state.onboardingCompleted) {
                Card(
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            "Get started",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Button(
                            onClick = { viewModel.activatePassiveCollectionAndCompleteOnboarding() },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Complete setup and enable passive collection")
                        }
                        Text(
                            "Turns on trip detection after you allow the permissions below. You can grant " +
                                "permissions before or after this step.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else if (state.passiveCollectionEffective && allRequiredPermissionsGranted) {
                Card(
                    colors =
                        CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        ),
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Setup complete — passive collection is on.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }

            if (!allRequiredPermissionsGranted || state.debugModeEnabled) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            "Permissions",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            if (!allRequiredPermissionsGranted) {
                                "OLGX needs access to run in the background and detect when you are driving."
                            } else {
                                "All required permissions are granted. You can open a system dialog again for testing."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            PermissionActionRow(
                                title = "Activity recognition",
                                subtitle =
                                    if (activityRecognitionGranted) "Granted — used to detect vehicle activity."
                                    else "Required to detect when you are in a vehicle.",
                                needsAction = !activityRecognitionGranted,
                                buttonLabel = "Allow activity recognition",
                                onRequest = { arLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION) },
                                requestEnabled = state.debugModeEnabled || !activityRecognitionGranted,
                            )
                        }

                        PermissionActionRow(
                            title = "Location",
                            subtitle =
                                if (fineLocationGranted) "Granted — used to record trips."
                                else "Required to capture your route while recording.",
                            needsAction = !fineLocationGranted,
                            buttonLabel = "Allow precise location",
                            onRequest = { fineLocationLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION) },
                            requestEnabled = state.debugModeEnabled || !fineLocationGranted,
                        )

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            PermissionActionRow(
                                title = "Notifications",
                                subtitle =
                                    if (notificationsGranted) "Granted — shows recording status when a trip runs."
                                    else "Required to show an ongoing recording notification.",
                                needsAction = !notificationsGranted,
                                buttonLabel = "Allow notifications",
                                onRequest = { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                                requestEnabled = state.debugModeEnabled || !notificationsGranted,
                            )
                        }
                    }
                }
            }

            // --- Passive collection status ---
            HomeSectionTitle("Passive collection status")
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    StatusLabeledChip(
                        label = "Passive collection",
                        value = if (passiveCollectionActive) "Active" else "Not active",
                        positive = passiveCollectionActive,
                    )
                    StatusLabeledChip(
                        label = "Permissions",
                        value = if (allRequiredPermissionsGranted) "Ready" else "Action required",
                        positive = allRequiredPermissionsGranted,
                    )
                    StatusLabeledChip(
                        label = "Collector state",
                        value = collectorStateDisplay(collector.lifecycleState),
                        positive =
                            collector.lifecycleState != CollectorLifecycleState.PAUSED_POLICY &&
                                collector.lifecycleState != CollectorLifecycleState.DEGRADED,
                    )
                    StatusLabeledChip(
                        label = "Recording active",
                        value = if (collector.recordingActive) "Yes" else "No",
                        positive = collector.recordingActive,
                    )
                }
            }

            // --- Collector runtime ---
            HomeSectionTitle("Collector runtime")
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SummaryLine(
                        label = "Listening for trips",
                        value = if (passivePipelineListening) "Yes" else "No",
                    )
                    SummaryLine(
                        label = "In vehicle (estimate)",
                        value =
                            when {
                                collector.likelyInVehicle -> "Likely yes"
                                collector.activityRecognitionUpdatesActive -> "Likely no"
                                else -> "Unknown"
                            },
                    )
                    SummaryLine(
                        label = "Current activity",
                        value =
                            "${collector.lastActivityLabel} (${collector.lastActivityConfidence}% confidence)",
                    )
                    SummaryLine(
                        label = "Background recording service",
                        value = if (collector.foregroundServiceRunning) "Running" else "Not running",
                    )
                }
            }

            // --- Recording summary ---
            HomeSectionTitle("Recording summary")
            val sensorState = state.sensorRecording
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SummaryLine(
                        label = "Trips saved on device",
                        value = state.recordingSessionCount.toString(),
                    )
                    SummaryLine(
                        label = "Upload policy",
                        value =
                            if (state.uploadWifiOnly) "Wi‑Fi only" else "Wi‑Fi preferred",
                    )
                    Text(
                        state.pendingUploadSummaryPlaceholder,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    Text(
                        "While recording",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SummaryLine(
                        label = "GPS samples (approx.)",
                        value = state.locationRecording.publishedSampleCount.toString(),
                    )
                    SummaryLine(
                        label = "Motion sampling",
                        value =
                            when {
                                !sensorState.recordingSensors -> "Idle"
                                sensorState.degradedRecording -> "Active (reduced quality)"
                                else -> "Active"
                            },
                    )
                }
            }

            // --- Technical details (collapsed) ---
            Card(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable { technicalDetailsExpanded = !technicalDetailsExpanded },
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Technical details",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = if (technicalDetailsExpanded) "Hide" else "Show",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    AnimatedVisibility(visible = technicalDetailsExpanded) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "Effective (onboarding and toggle): ${state.passiveCollectionEffective}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                "User toggle: ${state.passiveCollectionUserEnabled}, onboarding done: ${state.onboardingCompleted}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                "Lifecycle raw: ${collector.lifecycleState}; session id: ${collector.activeSessionId ?: "—"}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                "AR supported: ${collector.activityRecognitionSupported}, " +
                                    "updates active: ${collector.activityRecognitionUpdatesActive}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                "Last transition: ${collector.lastLifecycleTransitionLabel} " +
                                    "(${collector.lastLifecycleTransitionEpochMs ?: "—"})",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                "Location buffered: ${state.locationRecording.bufferedSampleCount}, " +
                                    "last time (epoch ms): ${state.locationRecording.lastWallClockEpochMs ?: "—"}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                "Last speed (m/s): ${state.locationRecording.lastSpeedMps ?: "—"}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                "Cellular uploads allowed: ${state.uploadAllowCellular}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                "Sensor capture: sensors=${sensorState.recordingSensors}, degraded=${sensorState.degradedRecording}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            sensorState.degradedReason?.let {
                                Text("Sensor note: $it", style = MaterialTheme.typography.bodySmall)
                            }
                            Text(
                                "Hardware — accel=${sensorState.hardwareAvailable.accelerometer}, " +
                                    "gyro=${sensorState.hardwareAvailable.gyroscope}, " +
                                    "gravity=${sensorState.hardwareAvailable.gravity}, " +
                                    "linear=${sensorState.hardwareAvailable.linearAcceleration}, " +
                                    "rotVec=${sensorState.hardwareAvailable.rotationVector}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                "Subscribed — accel=${sensorState.enabledSubscribed.accelerometer}, " +
                                    "gyro=${sensorState.enabledSubscribed.gyroscope}, gravity=${
                                        sensorState.enabledSubscribed.gravity
                                    }, linear=${sensorState.enabledSubscribed.linearAcceleration}, " +
                                    "rotVec=${sensorState.enabledSubscribed.rotationVector}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                "Sensor samples (pub≈${sensorState.publishedSampleCount}, " +
                                    "buf=${sensorState.bufferedSampleCount}), " +
                                    "last epoch: ${sensorState.lastWallClockEpochMs ?: "—"}, " +
                                    "est. Hz: ${sensorState.estimatedCallbacksPerSecond ?: "—"}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }

            if (state.debugModeEnabled) {
                HomeSectionTitle("Debug tools")
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors =
                        CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
                        ),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "For development only. Uses the same actions as before.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        OutlinedButton(
                            onClick = { viewModel.completeOnboardingOnly() },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Complete onboarding only")
                        }
                        OutlinedButton(
                            onClick = { viewModel.activatePassiveCollectionAndCompleteOnboarding() },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Complete onboarding & force passive on")
                        }
                        OutlinedButton(
                            onClick = { viewModel.resetOnboardingForDebug() },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Reset onboarding")
                        }
                        HorizontalDivider()
                        FilledTonalButton(
                            onClick = { viewModel.debugSetSimulatedDriving(true) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Simulate driving: on")
                        }
                        FilledTonalButton(
                            onClick = { viewModel.debugSetSimulatedDriving(false) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Simulate driving: off")
                        }
                        FilledTonalButton(
                            onClick = { viewModel.debugSetSimulatedDriving(null) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Simulate driving: clear")
                        }
                        FilledTonalButton(
                            onClick = { viewModel.debugForceStartRecording() },
                            enabled =
                                collector.lifecycleState != CollectorLifecycleState.RECORDING &&
                                    collector.lifecycleState != CollectorLifecycleState.COOLDOWN,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Force start recording")
                        }
                        OutlinedButton(
                            onClick = { viewModel.debugResetCollector() },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Reset collector state machine")
                        }
                    }
                }
            }

            HomeSectionTitle("Navigate")
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                    Text("Settings")
                }
                OutlinedButton(onClick = onOpenDiagnostics, modifier = Modifier.fillMaxWidth()) {
                    Text("Diagnostics")
                }
                FilledTonalButton(onClick = onOpenSessions, modifier = Modifier.fillMaxWidth()) {
                    Text("Recorded sessions")
                }
            }
        }
    }
}

@Composable
private fun HomeSectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
    )
}

@Composable
private fun StatusLabeledChip(
    label: String,
    value: String,
    positive: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
        )
        Surface(
            shape = RoundedCornerShape(12.dp),
            color =
                if (positive) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                color =
                    if (positive) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
        }
    }
}

@Composable
private fun SummaryLine(
    label: String,
    value: String,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun PermissionActionRow(
    title: String,
    subtitle: String,
    needsAction: Boolean,
    buttonLabel: String,
    onRequest: () -> Unit,
    requestEnabled: Boolean,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .then(
                    if (needsAction) {
                        Modifier.padding(vertical = 4.dp)
                    } else {
                        Modifier
                    },
                ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (needsAction) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    modifier = Modifier.padding(top = 2.dp),
                ) {
                    Text(
                        text = "!",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (needsAction || requestEnabled) {
            FilledTonalButton(
                onClick = onRequest,
                enabled = requestEnabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(buttonLabel)
            }
        }
    }
}

private fun collectorStateDisplay(state: CollectorLifecycleState): String =
    when (state) {
        CollectorLifecycleState.IDLE -> "Idle"
        CollectorLifecycleState.ARMING -> "Arming"
        CollectorLifecycleState.RECORDING -> "Recording"
        CollectorLifecycleState.COOLDOWN -> "Cooldown"
        CollectorLifecycleState.PAUSED_POLICY -> "Waiting for permission"
        CollectorLifecycleState.DEGRADED -> "Unavailable"
    }
