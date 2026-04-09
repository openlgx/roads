package org.openlgx.roads.sensor

private fun allSensorsFalse(): SensorAvailabilitySnapshot =
    SensorAvailabilitySnapshot(
        accelerometer = false,
        gyroscope = false,
        gravity = false,
        linearAcceleration = false,
        rotationVector = false,
    )

data class SensorRecordingUiState(
    val activeSessionId: Long? = null,
    val recordingSensors: Boolean = false,
    val hardwareAvailable: SensorAvailabilitySnapshot = allSensorsFalse(),
    /** Subscribed sensors for the current recording (may differ from [hardwareAvailable]). */
    val enabledSubscribed: SensorAvailabilitySnapshot = allSensorsFalse(),
    val publishedSampleCount: Long = 0L,
    val bufferedSampleCount: Int = 0,
    val lastWallClockEpochMs: Long? = null,
    val lastElapsedRealtimeNanos: Long? = null,
    /** Moving estimate of sensor callbacks/sec over the flush interval window. */
    val estimatedCallbacksPerSecond: Float? = null,
    val degradedRecording: Boolean = false,
    val degradedReason: String? = null,
)
