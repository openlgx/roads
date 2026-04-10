package org.openlgx.roads.roadpack

import java.io.File

/**
 * Spatial index over road-pack line geometry (alpha: optional; populated when GeoJSON pack is present).
 * See [docs/road-filtering.md].
 */
class LocalRoadIndex(
    linesWgs84: List<LineSegment>,
) {
    private val segments: List<LineSegment> = linesWgs84

    fun nearestDistanceMeters(lat: Double, lon: Double): Double? {
        if (segments.isEmpty()) return null
        var best = Double.MAX_VALUE
        for (s in segments) {
            val d = s.distanceMetersApprox(lat, lon)
            if (d < best) best = d
        }
        return if (best == Double.MAX_VALUE) null else best
    }

    companion object {
        fun fromGeoJsonFileOrEmpty(file: File): LocalRoadIndex {
            if (!file.isFile) return LocalRoadIndex(emptyList())
            // Stub: full GeoJSON parse wired in a follow-up; empty index => UNKNOWN eligibility.
            return LocalRoadIndex(emptyList())
        }
    }
}

data class LineSegment(
    val ax: Double,
    val ay: Double,
    val bx: Double,
    val by: Double,
) {
    /** Rough local equirectangular distance in meters (adequate for small tiles). */
    fun distanceMetersApprox(lat: Double, lon: Double): Double {
        val rLat = Math.toRadians(lat)
        val metersPerDegLat = 111_320.0
        val metersPerDegLon = kotlin.math.cos(rLat) * 111_320.0
        val px = lon * metersPerDegLon
        val py = lat * metersPerDegLat
        val p0x = ax * metersPerDegLon
        val p0y = ay * metersPerDegLat
        val p1x = bx * metersPerDegLon
        val p1y = by * metersPerDegLat
        val dx = p1x - p0x
        val dy = p1y - p0y
        val len2 = dx * dx + dy * dy
        if (len2 < 1e-6) return kotlin.math.hypot(px - p0x, py - p0y)
        var t = ((px - p0x) * dx + (py - p0y) * dy) / len2
        t = t.coerceIn(0.0, 1.0)
        val qx = p0x + t * dx
        val qy = p0y + t * dy
        return kotlin.math.hypot(px - qx, py - qy)
    }
}
