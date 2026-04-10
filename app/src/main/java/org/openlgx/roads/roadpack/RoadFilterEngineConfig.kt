package org.openlgx.roads.roadpack

/**
 * Central cadence, distance, and suppression thresholds for road-aware upload filtering.
 * User-facing buffer width remains [org.openlgx.roads.data.local.settings.AppSettings.uploadRoadFilterDistanceMeters].
 */
object RoadFilterEngineConfig {
    /** Minimum horizontal movement between classifier evaluations along the path. */
    const val distanceCadenceMeters: Double = 25.0

    /** Minimum wall-clock gap between classifier evaluations (milliseconds). */
    const val timeCadenceMs: Long = 5_000L

    /**
     * Distance to nearest linework must exceed [onRoadBufferMeters] * this value to count as
     * confidently off-road for suppression run detection (not for ON/NEAR labeling).
     */
    const val offRoadDistanceMultiplier: Float = 1.75f

    const val minOffRoadRunMeters: Double = 75.0
    const val minOffRoadRunMs: Long = 45_000L

    /** Locations with worse accuracy are evaluated as UNKNOWN. */
    const val maxHorizontalAccuracyMetersForEval: Float = 65f

    /** If kept locations are below max(this, fraction * total), skip auto-upload. */
    const val lowValueMinKeptCount: Int = 8
    const val lowValueKeptFraction: Float = 0.08f

    const val filterMethodVersion: String = "road_filter_v1"

    /** Extra wall-clock margin (ms) when attaching sensors to kept location spans. */
    const val sensorKeepMarginMs: Long = 1_500L

    /** Grid cell size (degrees) for spatial index (~1.3 km mid-lat). */
    const val gridCellDegrees: Double = 0.012
}
