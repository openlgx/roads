package org.openlgx.roads.sensor

import android.hardware.Sensor
import android.hardware.SensorEvent
import org.openlgx.roads.data.local.db.entity.SensorSampleEntity

internal fun SensorEvent.toSampleEntity(sessionId: Long): SensorSampleEntity {
    val wallClockUtcEpochMs = System.currentTimeMillis()
    val type = sensor.type
    val v = values
    val ax = v.getOrNull(0) ?: 0f
    val ay = v.getOrNull(1) ?: 0f
    val az = v.getOrNull(2) ?: 0f
    val w =
        if (type == Sensor.TYPE_ROTATION_VECTOR && v.size >= 4) {
            v[3]
        } else {
            null
        }
    return SensorSampleEntity(
        sessionId = sessionId,
        elapsedRealtimeNanos = timestamp,
        wallClockUtcEpochMs = wallClockUtcEpochMs,
        sensorType = type,
        x = ax,
        y = ay,
        z = az,
        w = w,
        accuracy = accuracy,
    )
}
