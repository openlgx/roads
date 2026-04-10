package org.openlgx.roads.roadpack

/**
 * Diagnostics for road-pack load / parse (GeoJSON v1).
 */
data class RoadPackDiagnostics(
    val featureCount: Int,
    val segmentCount: Int,
    val minLat: Double?,
    val maxLat: Double?,
    val minLon: Double?,
    val maxLon: Double?,
    val loadError: String?,
    val packVersion: String?,
)
