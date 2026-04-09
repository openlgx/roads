package org.openlgx.roads.location

data class LocationRecordingUiState(
    val activeSessionId: Long? = null,
    val publishedSampleCount: Long = 0L,
    val bufferedSampleCount: Int = 0,
    val lastWallClockEpochMs: Long? = null,
    val lastSpeedMps: Float? = null,
    val lastLatitude: Double? = null,
    val lastLongitude: Double? = null,
    val recordingLocation: Boolean = false,
    val statusMessage: String? = null,
)
