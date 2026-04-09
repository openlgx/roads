package org.openlgx.roads.location

import android.location.Location
import android.os.Build
import android.os.SystemClock
import org.openlgx.roads.data.local.db.entity.LocationSampleEntity
import org.openlgx.roads.data.local.db.model.RoadEligibilityDisposition

fun Location.toSampleEntity(sessionId: Long): LocationSampleEntity {
    val elapsedNanos =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            elapsedRealtimeNanos
        } else {
            SystemClock.elapsedRealtime() * 1_000_000L
        }
    return LocationSampleEntity(
        sessionId = sessionId,
        elapsedRealtimeNanos = elapsedNanos,
        wallClockUtcEpochMs = time,
        latitude = latitude,
        longitude = longitude,
        speedMps = if (hasSpeed()) speed else null,
        bearingDegrees = if (hasBearing()) bearing else null,
        horizontalAccuracyMeters = if (hasAccuracy()) accuracy else null,
        altitudeMeters = if (hasAltitude()) altitude else null,
        roadEligibilityDisposition = RoadEligibilityDisposition.UNKNOWN,
        eligibilityConfidence = null,
        retainedRegardlessOfEligibility = true,
    )
}
