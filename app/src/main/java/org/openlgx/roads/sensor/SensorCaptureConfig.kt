package org.openlgx.roads.sensor

import android.hardware.SensorManager

/**
 * IMU capture tuning (Phase 2B2). Delays use [SensorManager] microseconds convention.
 */
data class SensorCaptureConfig(
    val sensorDelayMicros: Int = SensorManager.SENSOR_DELAY_GAME,
    val batchMaxSize: Int = 120,
    val flushIntervalMs: Long = 2_000L,
    val enableAccelerometer: Boolean = true,
    val enableGyroscope: Boolean = true,
    val enableGravity: Boolean = true,
    val enableLinearAcceleration: Boolean = true,
    /** Set false to skip hardware rotation vector until analysis pipeline needs it (Phase 2B2+: TODO full quaternion ingest). */
    val enableRotationVector: Boolean = false,
)
