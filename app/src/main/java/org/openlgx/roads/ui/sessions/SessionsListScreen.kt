package org.openlgx.roads.ui.sessions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.openlgx.roads.data.local.db.model.SessionListStats
import org.openlgx.roads.ui.review.TripReviewWebView

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsListScreen(
    viewModel: SessionsListViewModel,
    onBack: () -> Unit,
    onOpenSession: (Long) -> Unit,
    onOpenAllRunsReview: () -> Unit,
) {
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val mapB64 by viewModel.mapPayloadBase64.collectAsStateWithLifecycle()
    val mapErr by viewModel.mapError.collectAsStateWithLifecycle()
    val excludedFromMap by viewModel.excludedFromMapSessionIds.collectAsStateWithLifecycle()

    val processingDigest =
        sessions.joinToString("|") { "${it.id}:${it.processingState}:${it.derivedWindowCount}" }
    LaunchedEffect(processingDigest, excludedFromMap) {
        viewModel.refreshMap()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Recorded sessions") },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text("Back")
                    }
                },
                actions = {
                    TextButton(onClick = { viewModel.refreshMap() }) {
                        Text("Refresh map")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    "Map: check which trips to include (or use All / Only). Turn on the Route and " +
                        "Roughness toggles on the map for the line and dots. Roughness needs completed " +
                        "processing — open a trip and tap Reprocess if it failed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    if (mapErr != null) {
                        Text(
                            "Map load error: $mapErr",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else {
                        TripReviewWebView(
                            payloadBase64 = mapB64,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .height(340.dp),
                            onOpenSessionFromMap = onOpenSession,
                        )
                    }
                }
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilledTonalButton(
                        onClick = { viewModel.showAllTripsOnMap() },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("All trips on map")
                    }
                }
            }
            item {
                Button(
                    onClick = onOpenAllRunsReview,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("All runs map + consensus")
                }
            }
            items(sessions, key = { it.id }) { row ->
                SessionListMapRow(
                    row = row,
                    checkedOnMap = row.id !in excludedFromMap,
                    onToggleMap = { viewModel.toggleTripOnMap(row.id) },
                    onMapOnlyThis = { viewModel.showOnlyTripOnMap(row.id) },
                    onOpenSession = { onOpenSession(row.id) },
                )
            }
        }
    }
}

@Composable
private fun SessionListMapRow(
    row: SessionListStats,
    checkedOnMap: Boolean,
    onToggleMap: () -> Unit,
    onMapOnlyThis: () -> Unit,
    onOpenSession: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Checkbox(
            checked = checkedOnMap,
            onCheckedChange = { onToggleMap() },
        )
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .clickable(onClick = onOpenSession)
                    .padding(end = 4.dp),
        ) {
            SessionListRowBody(row = row)
        }
        TextButton(onClick = onMapOnlyThis) {
            Text("Only", style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun SessionListRowBody(
    row: SessionListStats,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text("Session ${row.id} · ${row.uuid.take(8)}…", style = MaterialTheme.typography.titleSmall)
        Text(
            "Started: ${row.startedAtEpochMs}  State: ${row.state}  Source: ${row.recordingSource}",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "Samples — loc: ${row.locationSampleCount}, sensor: ${row.sensorSampleCount}",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "Processing: ${row.processingState} · windows ${row.derivedWindowCount}, " +
                "anomalies ${row.anomalyCount}" +
                (row.roughnessProxyScore?.let { " · roughness $it" } ?: ""),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
