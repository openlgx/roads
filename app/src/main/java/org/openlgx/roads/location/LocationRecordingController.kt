package org.openlgx.roads.location

import kotlinx.coroutines.flow.StateFlow

interface LocationRecordingController {
    val uiState: StateFlow<LocationRecordingUiState>

    fun startRecording(sessionId: Long)

    suspend fun stopAndFlush()
}
