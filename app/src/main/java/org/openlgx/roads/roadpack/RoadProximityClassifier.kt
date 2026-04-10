package org.openlgx.roads.roadpack

import org.openlgx.roads.data.local.db.model.RoadEligibilityDisposition

object RoadProximityClassifier {
    fun classify(distanceMeters: Double?, thresholdMeters: Float): RoadEligibilityDisposition {
        val d = distanceMeters ?: return RoadEligibilityDisposition.UNKNOWN
        return when {
            d <= thresholdMeters * 0.5f -> RoadEligibilityDisposition.LIKELY_PUBLIC_ROAD
            d <= thresholdMeters -> RoadEligibilityDisposition.NEAR_PUBLIC_ROAD
            else -> RoadEligibilityDisposition.UNLIKELY_OR_OFF_ROAD
        }
    }
}
