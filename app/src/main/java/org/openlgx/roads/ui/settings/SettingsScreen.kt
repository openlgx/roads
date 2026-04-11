package org.openlgx.roads.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.openlgx.roads.data.local.settings.CaptureSettingsPreset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val hostedDiag by viewModel.hostedDiagnostics.collectAsStateWithLifecycle()
    val batteryExempt by viewModel.batteryOptimizationExempt.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text("Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Capture policy", style = MaterialTheme.typography.titleMedium)
            SettingToggleRow(
                title = "Passive collection enabled (user)",
                checked = settings.passiveCollectionUserEnabled,
                onCheckedChange = viewModel::setPassiveCollectionEnabled,
                enabled = true,
                supporting =
                    "Persisted as passive_collection_enabled. Effective capture: ${settings.passiveCollectionEffective}. " +
                        "Requires onboarding to be completed.",
            )

            Text("Onboarding", style = MaterialTheme.typography.titleMedium)
            Button(onClick = viewModel::completeOnboarding) {
                Text("Mark onboarding complete (demo gate)")
            }

            Text("Battery & background", style = MaterialTheme.typography.titleMedium)
            Text(
                if (batteryExempt) {
                    "Battery optimization: exempt (recommended). Passive detection and WorkManager keepalive " +
                        "are less likely to be delayed by the OS."
                } else {
                    "Battery optimization: not exempt — OEMs (e.g. Samsung) may delay passive trip start " +
                        "until you open the app. Request exemption for reliable automatic trips."
                },
                style = MaterialTheme.typography.bodySmall,
            )
            if (!batteryExempt) {
                Button(
                    onClick = { context.startActivity(viewModel.batteryOptimizationExemptionIntent()) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Request battery optimization exemption")
                }
            }

            Text("Upload policy", style = MaterialTheme.typography.titleMedium)
            SettingToggleRow(
                title = "Upload on Wi‑Fi only",
                checked = settings.uploadWifiOnly,
                onCheckedChange = viewModel::setUploadWifiOnly,
            )
            SettingToggleRow(
                title = "Allow cellular upload",
                checked = settings.uploadAllowCellular,
                onCheckedChange = viewModel::setUploadAllowCellular,
            )
            SettingToggleRow(
                title = "Upload only while charging",
                checked = settings.uploadOnlyWhileCharging,
                onCheckedChange = viewModel::setUploadOnlyWhileCharging,
            )
            SettingToggleRow(
                title = "Pause uploads on low battery",
                checked = settings.uploadPauseOnLowBatteryEnabled,
                onCheckedChange = viewModel::setUploadPauseOnLowBattery,
            )
            Text(
                "Low battery threshold: ${settings.uploadLowBatteryThresholdPercent}%",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = { viewModel.setLowBatteryThreshold(15) }) {
                Text("Set threshold to 15% (quick test)")
            }
            Button(onClick = { viewModel.setLowBatteryThreshold(25) }) {
                Text("Set threshold to 25% (quick test)")
            }

            Text("Hosted alpha upload", style = MaterialTheme.typography.titleMedium)
            Text(
                "Uploads go to Supabase Edge (not Neon). Paste the functions base URL and DEVICE_UPLOAD key " +
                    "from seed_pilot_council plus project/device UUIDs. Never put Neon URLs or service keys here. " +
                    "See docs/pilot-readiness.md.",
                style = MaterialTheme.typography.bodySmall,
            )
            var draftBaseUrl by remember { mutableStateOf(settings.uploadBaseUrl) }
            var draftApiKey by remember { mutableStateOf("") }
            var draftCouncilSlug by remember { mutableStateOf(settings.uploadCouncilSlug) }
            var draftProjectSlug by remember { mutableStateOf(settings.uploadProjectSlug) }
            var draftProjectId by remember { mutableStateOf(settings.uploadProjectId) }
            var draftDeviceId by remember { mutableStateOf(settings.uploadDeviceId) }
            LaunchedEffect(
                settings.uploadBaseUrl,
                settings.uploadCouncilSlug,
                settings.uploadProjectSlug,
                settings.uploadProjectId,
                settings.uploadDeviceId,
            ) {
                draftBaseUrl = settings.uploadBaseUrl
                draftCouncilSlug = settings.uploadCouncilSlug
                draftProjectSlug = settings.uploadProjectSlug
                draftProjectId = settings.uploadProjectId
                draftDeviceId = settings.uploadDeviceId
            }
            Text("Hosted connection (pilot)", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = draftBaseUrl,
                onValueChange = { draftBaseUrl = it },
                label = { Text("Upload base URL") },
                supportingText = {
                    Text(
                        "Use https://YOUR_REF.supabase.co/functions/v1. If you paste the project root only " +
                            "(…supabase.co), /functions/v1 is added automatically on Save for *.supabase.co.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                singleLine = true,
            )
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = draftApiKey,
                onValueChange = { draftApiKey = it },
                label = { Text("DEVICE_UPLOAD API key (leave blank to keep saved)") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
            )
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = draftCouncilSlug,
                onValueChange = { draftCouncilSlug = it },
                label = { Text("Council slug") },
                singleLine = true,
            )
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = draftProjectSlug,
                onValueChange = { draftProjectSlug = it },
                label = { Text("Project slug (Neon)") },
                singleLine = true,
            )
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = draftProjectId,
                onValueChange = { draftProjectId = it },
                label = { Text("Project id (UUID)") },
                singleLine = true,
            )
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = draftDeviceId,
                onValueChange = { draftDeviceId = it },
                label = { Text("Device id (UUID)") },
                singleLine = true,
            )
            Button(
                onClick = {
                    viewModel.applyHostedPilotConnection(
                        uploadBaseUrl = draftBaseUrl,
                        apiKeyIfProvided = draftApiKey,
                        councilSlug = draftCouncilSlug,
                        projectSlug = draftProjectSlug,
                        projectId = draftProjectId,
                        deviceId = draftDeviceId,
                    )
                    draftApiKey = ""
                },
            ) {
                Text("Save hosted connection")
            }
            Button(onClick = { viewModel.applyPilotUploadDefaults() }) {
                Text("Apply recommended pilot upload defaults")
            }
            Text("Hosted upload diagnostics (no secrets shown)", style = MaterialTheme.typography.titleSmall)
            if (hostedDiag.pilotBootstrapApplied) {
                Text(
                    "Pilot bootstrap: active — label ${hostedDiag.pilotBootstrapLabel ?: "—"} " +
                        "(one-shot seed; edits here are not overwritten).",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text("Upload enabled: ${hostedDiag.uploadEnabled}", style = MaterialTheme.typography.bodySmall)
            Text(
                "Base URL host: ${hostedDiag.uploadBaseUrlHost}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "API key present: ${hostedDiag.uploadApiKeyConfigured}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Council slug: \"${hostedDiag.uploadCouncilSlug}\" · project slug: \"${hostedDiag.uploadProjectSlug}\"",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Project id set: ${hostedDiag.projectIdConfigured} · Device id set: ${hostedDiag.deviceIdConfigured}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Road pack (for council slug above): " +
                    "${if (hostedDiag.roadPackPresent) "loaded" else "missing — auto-upload is skipped (not crashed) when \"Require road pack\" is on"} " +
                    "(${hostedDiag.roadPackFeatureCount} features)" +
                    (hostedDiag.roadPackVersionLabel?.let { " · version dir $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Expected GeoJSON path: ${hostedDiag.roadPackExpectedPath}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text("Upload readiness:", style = MaterialTheme.typography.labelMedium)
            hostedDiag.uploadReadinessNotes.forEach { note ->
                Text("• $note", style = MaterialTheme.typography.bodySmall)
            }
            hostedDiag.roadPackLoadNote?.let {
                Text("Pack load note: $it", style = MaterialTheme.typography.bodySmall)
            }
            Text(
                "Upload queue (pending/retryable rows): ${hostedDiag.uploadQueuePendingOrRetryable}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Last upload attempt (epoch ms): ${hostedDiag.uploadLastAttemptAtEpochMs ?: "—"}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Last successful upload (epoch ms): ${hostedDiag.uploadLastSuccessAtEpochMs ?: "—"}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Last upload error (sanitized): ${hostedDiag.uploadLastError ?: "—"}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Network: Wi‑Fi only ${hostedDiag.wifiOnly}, cellular allowed ${hostedDiag.cellularAllowed}; " +
                    "charging hard gate ${hostedDiag.chargingRequiredHard}, soft preferred ${hostedDiag.chargingPreferredSoft}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Retry limit: ${hostedDiag.uploadRetryLimit} · auto after session: ${hostedDiag.uploadAutoAfterSession} · " +
                    "road filter: ${hostedDiag.roadFilterEnabled} · pack required for auto: ${hostedDiag.roadPackRequiredForAutoUpload}",
                style = MaterialTheme.typography.bodySmall,
            )
            SettingToggleRow(
                title = "Enable hosted upload",
                checked = settings.uploadEnabled,
                onCheckedChange = viewModel::setUploadEnabled,
            )
            SettingToggleRow(
                title = "Auto-queue upload after completed session",
                checked = settings.uploadAutoAfterSessionEnabled,
                onCheckedChange = viewModel::setUploadAutoAfterSessionEnabled,
            )
            SettingToggleRow(
                title = "Require road pack for auto-upload",
                checked = settings.uploadRoadPackRequiredForAutoUpload,
                onCheckedChange = viewModel::setUploadRoadPackRequired,
            )
            SettingToggleRow(
                title = "Prefer charging (soft; does not block when unplugged)",
                checked = settings.uploadChargingPreferred,
                onCheckedChange = viewModel::setUploadChargingPreferred,
            )
            SettingToggleRow(
                title = "Upload road filter (alpha; traceability)",
                checked = settings.uploadRoadFilterEnabled,
                onCheckedChange = viewModel::setUploadRoadFilterEnabled,
            )
            Text("Local storage safety (placeholders)", style = MaterialTheme.typography.titleMedium)
            Text("Retention days: ${settings.retentionDays}", style = MaterialTheme.typography.bodyMedium)
            Button(onClick = { viewModel.setRetentionDays(30) }) { Text("Retention: 30 days") }
            Button(onClick = { viewModel.setRetentionDays(90) }) { Text("Retention: 90 days") }

            Text(
                "Max local storage MB: ${settings.maxLocalStorageMb} (0 = unset / unlimited placeholder)",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = { viewModel.setMaxLocalStorageMb(0) }) { Text("Max storage: 0 (unset)") }
            Button(onClick = { viewModel.setMaxLocalStorageMb(2048) }) { Text("Max storage: 2048 MB") }

            SettingToggleRow(
                title = "Local compaction/cleanup enabled (placeholder)",
                checked = settings.localCompactionEnabled,
                onCheckedChange = viewModel::setLocalCompactionEnabled,
            )

            Text("Passive capture detection", style = MaterialTheme.typography.titleMedium)
            Text(
                "Tune arming and stop-hold (experimental). Field presets below apply speed gates and hold duration.",
                style = MaterialTheme.typography.bodySmall,
            )
            SettingToggleRow(
                title = "Fast arming",
                checked = settings.captureFastArmingEnabled,
                onCheckedChange = viewModel::setCaptureFastArmingEnabled,
                supporting =
                    "When on, strong in-vehicle + speed evidence uses a shorter debounce before recording.",
            )
            Text(
                "Start speed gate (m/s): ${"%.1f".format(settings.captureStartSpeedMps)} · " +
                    "Immediate-start threshold: ${"%.1f".format(settings.captureImmediateStartSpeedMps)} · " +
                    "Stop speed (m/s): ${"%.1f".format(settings.captureStopSpeedMps)}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Stop-hold: ${settings.captureStopHoldSeconds}s · Stationary radius: " +
                    "${"%.0f".format(settings.captureStationaryRadiusMeters)} m",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text("Quick presets", style = MaterialTheme.typography.titleSmall)
            Button(onClick = { viewModel.applyCapturePreset(CaptureSettingsPreset.CONSERVATIVE) }) {
                Text("Conservative")
            }
            Button(onClick = { viewModel.applyCapturePreset(CaptureSettingsPreset.NORMAL) }) {
                Text("Normal")
            }
            Button(onClick = { viewModel.applyCapturePreset(CaptureSettingsPreset.FAST_ARMING) }) {
                Text("Fast arming")
            }
            Button(onClick = { viewModel.setCaptureStopHoldSeconds(120) }) {
                Text("Hold: 2 min (quick test)")
            }
            Button(onClick = { viewModel.setCaptureStopHoldSeconds(180) }) {
                Text("Hold: 3 min (default)")
            }

            Text("On-device processing (experimental)", style = MaterialTheme.typography.titleMedium)
            SettingToggleRow(
                title = "Process session after recording completes",
                checked = settings.processingLiveAfterSessionEnabled,
                onCheckedChange = viewModel::setProcessingLiveAfterSessionEnabled,
            )
            SettingToggleRow(
                title = "All-runs map: consensus overlay default on",
                checked = settings.processingAllRunsOverlayEnabled,
                onCheckedChange = viewModel::setProcessingAllRunsOverlayEnabled,
            )
            Text(
                "Window: ${"%.2f".format(settings.processingWindowSeconds)} s · Distance bin: " +
                    "${"%.0f".format(settings.processingDistanceBinMeters)} m",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = { viewModel.setProcessingWindowSeconds(1.0f) }) { Text("Window: 1.0 s") }
            Button(onClick = { viewModel.setProcessingDistanceBinMeters(10f) }) { Text("Bin: 10 m") }

            Text("Developer", style = MaterialTheme.typography.titleMedium)
            SettingToggleRow(
                title = "Debug mode (enables future manual tools)",
                checked = settings.debugModeEnabled,
                onCheckedChange = viewModel::setDebugMode,
            )

            Text("Phase F — calibration workflow", style = MaterialTheme.typography.titleMedium)
            SettingToggleRow(
                title = "Record session-completed calibration anchors",
                checked = settings.calibrationWorkflowEnabled,
                onCheckedChange = viewModel::setCalibrationWorkflowEnabled,
                supporting =
                    "Experimental. When on, each completed recording session inserts a row in " +
                        "calibration_runs (anchor only—not MEMS or IRI calibration). " +
                        "Exports include calibration flags in manifest.json. See docs/calibration-notes.md.",
            )
        }
    }
}

@Composable
private fun SettingToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    supporting: String? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        if (supporting != null) {
            Text(supporting, style = MaterialTheme.typography.bodySmall)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
    }
}
