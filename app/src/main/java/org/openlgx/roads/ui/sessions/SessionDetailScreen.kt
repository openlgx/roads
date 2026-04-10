package org.openlgx.roads.ui.sessions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
fun SessionDetailScreen(
    viewModel: SessionDetailViewModel,
    onBack: () -> Unit,
    onOpenReview: () -> Unit,
) {
    val detail by viewModel.detail.collectAsStateWithLifecycle()
    val exportMessage by viewModel.exportMessage.collectAsStateWithLifecycle()
    val reprocessMessage by viewModel.reprocessMessage.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Session detail") },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text("Back")
                    }
                },
            )
        },
    ) { padding ->
        val d = detail
        if (d == null) {
            Text("Loading or session not found…", modifier = Modifier.padding(padding).padding(16.dp))
            return@Scaffold
        }

        val s = d.session
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Identity", style = MaterialTheme.typography.titleMedium)
            Text("id: ${s.id}")
            Text("uuid: ${s.uuid}")

            Text("Timing", style = MaterialTheme.typography.titleMedium)
            Text("startedAtEpochMs: ${s.startedAtEpochMs}")
            Text("endedAtEpochMs: ${s.endedAtEpochMs ?: "— (open)"}")
            Text("durationMs (computed): ${d.durationMs}")

            Text("Classification", style = MaterialTheme.typography.titleMedium)
            Text("state: ${s.state}")
            Text("recordingSource: ${s.recordingSource}")
            Text("sessionUploadState: ${s.sessionUploadState} (queue placeholder)")
            Text("qualityFlags: ${s.qualityFlags} (placeholder bitmask)")

            Text("On-device processing", style = MaterialTheme.typography.titleMedium)
            Text("processingState: ${s.processingState}")
            Text("processingStartedAtEpochMs: ${s.processingStartedAtEpochMs ?: "—"}")
            Text("processingCompletedAtEpochMs: ${s.processingCompletedAtEpochMs ?: "—"}")
            Text("processingLastError: ${s.processingLastError ?: "—"}")
            Text(
                "processingSummaryJson: ${s.processingSummaryJson ?: "—"}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text("Hosted pipeline (upload / remote)", style = MaterialTheme.typography.titleMedium)
            Text("hostedPipelineState: ${s.hostedPipelineState}")
            Text("roughnessProxyScore: ${s.roughnessProxyScore ?: "—"} (${s.roughnessMethodVersion ?: "—"})")
            Text("roadResponseScore: ${s.roadResponseScore ?: "—"} (${s.roadResponseMethodVersion ?: "—"})")

            Text("Samples", style = MaterialTheme.typography.titleMedium)
            Text("locationSampleCount: ${d.locationSampleCount}")
            Text("sensorSampleCount: ${d.sensorSampleCount}")
            Text("lastLocationWallClockMs: ${d.lastLocationWallClockMs ?: "—"}")
            Text("lastSensorWallClockMs: ${d.lastSensorWallClockMs ?: "—"}")

            Text("Sensor snapshot (JSON)", style = MaterialTheme.typography.titleMedium)
            Text(
                s.sensorCaptureSnapshotJson ?: "—",
                style = MaterialTheme.typography.bodySmall,
            )

            Text("Collector snapshot (JSON)", style = MaterialTheme.typography.titleMedium)
            Text(
                s.collectorStateSnapshotJson ?: "—",
                style = MaterialTheme.typography.bodySmall,
            )

            Text("Validation (sanity)", style = MaterialTheme.typography.titleMedium)
            val v = d.validation
            Text("hasLocationData: ${v.hasLocationData}")
            Text("hasSensorData: ${v.hasSensorData}")
            Text("accel rows: ${v.hasAccelerometerSamples}, gyro rows: ${v.hasGyroscopeSamples}")
            Text("loc monotonic: ${v.locationWallClockMonotonic}, sens monotonic: ${v.sensorWallClockMonotonic}")
            Text("loc gaps: ${v.locationSuspectGaps}, sens gaps: ${v.sensorSuspectGaps}")
            Text("low rate warn: ${v.suspiciouslyLowSampleRate}")
            Text("notes:\n${v.notes.joinToString("\n")}")

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onOpenReview) {
                    Text("Open trip review")
                }
                Button(onClick = viewModel::requestReprocess) {
                    Text("Reprocess")
                }
            }
            reprocessMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

            Button(onClick = viewModel::exportSession) {
                Text("Export session (folder + zip)")
            }
            exportMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

            TextButton(onClick = viewModel::refresh) {
                Text("Refresh from database")
            }
        }
    }
}
