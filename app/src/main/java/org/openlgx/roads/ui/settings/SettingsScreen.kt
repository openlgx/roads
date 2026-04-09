package org.openlgx.roads.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()

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

            Text("Capture heuristics (placeholder for Phase 2)", style = MaterialTheme.typography.titleMedium)
            Text(
                "Minimum speed (m/s) for eligibility: ${"%.1f".format(settings.captureMinSpeedMps)}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = { viewModel.setCaptureMinSpeedMps(3.0f) }) { Text("Min speed: 3.0 m/s") }
            Button(onClick = { viewModel.setCaptureMinSpeedMps(5.0f) }) { Text("Min speed: 5.0 m/s") }

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
