package org.openlgx.roads.ui.review

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
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
fun SessionReviewScreen(
    viewModel: SessionReviewViewModel,
    onBack: () -> Unit,
) {
    val b64 by viewModel.payloadBase64.collectAsStateWithLifecycle()
    val err by viewModel.error.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Trip review") },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text("Back")
                    }
                },
            )
        },
    ) { padding ->
        if (err != null) {
            Text(
                "Error: $err",
                modifier = Modifier.padding(padding).padding(16.dp),
            )
            return@Scaffold
        }
        TripReviewWebView(
            payloadBase64 = b64,
            modifier = Modifier.fillMaxSize().padding(padding),
            onOpenSessionFromMap = null,
        )
    }
}
