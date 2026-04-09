package org.openlgx.roads.validation

import android.hardware.Sensor
import org.openlgx.roads.data.local.db.entity.RecordingSessionEntity

data class SessionCaptureValidationSummary(
    val hasLocationData: Boolean,
    val hasSensorData: Boolean,
    val hasAccelerometerSamples: Boolean,
    val hasGyroscopeSamples: Boolean,
    val locationWallClockMonotonic: Boolean,
    val sensorWallClockMonotonic: Boolean,
    val locationSuspectGaps: Boolean,
    val sensorSuspectGaps: Boolean,
    val suspiciouslyLowSampleRate: Boolean,
    val notes: List<String>,
)

object SessionCaptureValidator {

    private const val LOCATION_GAP_WARN_MS = 45_000L
    private const val SENSOR_GAP_WARN_MS = 3_000L
    private const val MIN_DURATION_FOR_RATE_CHECK_MS = 120_000L
    private const val MIN_LOCATION_SAMPLES_WARN = 5
    private const val MIN_SENSOR_SAMPLES_WARN = 100

    fun summarize(
        session: RecordingSessionEntity,
        locationSampleCount: Long,
        sensorSampleCount: Long,
        locationWallClocksAsc: List<Long>,
        sensorWallClocksAsc: List<Long>,
        distinctSensorTypes: List<Int>,
    ): SessionCaptureValidationSummary {
        val hasLoc = locationSampleCount > 0
        val hasSens = sensorSampleCount > 0
        val hasAccel = distinctSensorTypes.contains(Sensor.TYPE_ACCELEROMETER)
        val hasGyro = distinctSensorTypes.contains(Sensor.TYPE_GYROSCOPE)

        val locMono = isNonDecreasing(locationWallClocksAsc)
        val sensMono = isNonDecreasing(sensorWallClocksAsc)

        val locGaps = hasGaps(locationWallClocksAsc, LOCATION_GAP_WARN_MS)
        val sensGaps = hasGaps(sensorWallClocksAsc, SENSOR_GAP_WARN_MS)

        val end = session.endedAtEpochMs ?: System.currentTimeMillis()
        val duration = (end - session.startedAtEpochMs).coerceAtLeast(0L)
        val lowRate =
            duration >= MIN_DURATION_FOR_RATE_CHECK_MS &&
                (locationSampleCount < MIN_LOCATION_SAMPLES_WARN ||
                    sensorSampleCount < MIN_SENSOR_SAMPLES_WARN)

        val notes = ArrayList<String>(4)
        if (!hasLoc) notes.add("No location samples")
        if (!hasSens) notes.add("No sensor samples")
        if (hasSens && !hasAccel) notes.add("No accelerometer-type rows (type ${Sensor.TYPE_ACCELEROMETER})")
        if (hasSens && !hasGyro) notes.add("No gyroscope-type rows (type ${Sensor.TYPE_GYROSCOPE})")
        if (hasLoc && !locMono) notes.add("Location wall-clock not monotonic (check clock skew / merges)")
        if (hasSens && !sensMono) notes.add("Sensor wall-clock not monotonic")
        if (hasLoc && locGaps) notes.add("Location gaps > ${LOCATION_GAP_WARN_MS}ms observed")
        if (hasSens && sensGaps) notes.add("Sensor gaps > ${SENSOR_GAP_WARN_MS}ms observed")
        if (lowRate) notes.add("Sample counts low relative to session duration (sanity only)")

        return SessionCaptureValidationSummary(
            hasLocationData = hasLoc,
            hasSensorData = hasSens,
            hasAccelerometerSamples = hasAccel,
            hasGyroscopeSamples = hasGyro,
            locationWallClockMonotonic = locMono,
            sensorWallClockMonotonic = sensMono,
            locationSuspectGaps = locGaps,
            sensorSuspectGaps = sensGaps,
            suspiciouslyLowSampleRate = lowRate,
            notes = notes,
        )
    }

    private fun isNonDecreasing(times: List<Long>): Boolean {
        if (times.size < 2) return true
        for (i in 1 until times.size) {
            if (times[i] < times[i - 1]) return false
        }
        return true
    }

    private fun hasGaps(times: List<Long>, thresholdMs: Long): Boolean {
        if (times.size < 2) return false
        for (i in 1 until times.size) {
            if (times[i] - times[i - 1] > thresholdMs) return true
        }
        return false
    }
}
