package org.openlgx.roads.roadpack

import org.openlgx.roads.data.local.db.model.RoadEligibilityDisposition
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Labels samples/windows for upload trimming. **Does not write to Room.**
 */
@Singleton
class RoadEligibilityEvaluator
@Inject
constructor() {
    fun evaluateNearestRoad(
        index: LocalRoadIndex,
        lat: Double,
        lon: Double,
        bufferMeters: Float,
    ): RoadEligibilityDisposition {
        val d = index.nearestDistanceMeters(lat, lon)
        return RoadProximityClassifier.classify(d, bufferMeters)
    }
}
