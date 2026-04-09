package org.openlgx.roads.sensor

data class SensorAvailabilitySnapshot(
    val accelerometer: Boolean,
    val gyroscope: Boolean,
    val gravity: Boolean,
    val linearAcceleration: Boolean,
    val rotationVector: Boolean,
)
