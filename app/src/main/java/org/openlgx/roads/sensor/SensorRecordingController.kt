package org.openlgx.roads.sensor

import kotlinx.coroutines.flow.StateFlow

interface SensorRecordingController {
    val uiState: StateFlow<SensorRecordingUiState>

    fun startRecording(sessionId: Long)

    suspend fun stopAndFlush()
}
