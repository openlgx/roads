package org.openlgx.roads.ui.sessions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import org.openlgx.roads.data.local.db.model.SessionListStats

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsListScreen(
    viewModel: SessionsListViewModel,
    onBack: () -> Unit,
    onOpenSession: (Long) -> Unit,
) {
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Recorded sessions") },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text("Back")
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
                    .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    "${sessions.size} session(s). Tap a row for detail and export.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            items(sessions, key = { it.id }) { row ->
                SessionListRow(
                    row = row,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable { onOpenSession(row.id) }
                            .padding(vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun SessionListRow(
    row: SessionListStats,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text("Session ${row.id} · ${row.uuid.take(8)}…", style = MaterialTheme.typography.titleSmall)
        Text(
            "Started: ${row.startedAtEpochMs}  State: ${row.state}  Source: ${row.recordingSource}",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "Samples — loc: ${row.locationSampleCount}, sensor: ${row.sensorSampleCount}",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
