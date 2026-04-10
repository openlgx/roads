package org.openlgx.roads.roadpack

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Spatial index over road-pack line geometry (GeoJSON LineString / MultiLineString).
 * See [docs/road-filtering.md].
 */
class LocalRoadIndex private constructor(
    linesWgs84: List<LineSegment>,
    private val grid: Map<String, List<Int>>,
    private val cellDeg: Double,
) {
    private val segments: List<LineSegment> = linesWgs84

    fun nearestDistanceMeters(lat: Double, lon: Double): Double? {
        if (segments.isEmpty()) return null
        val ix = kotlin.math.floor(lat / cellDeg).toInt()
        val iy = kotlin.math.floor(lon / cellDeg).toInt()
        val candidateIndices = LinkedHashSet<Int>()
        for (dx in -1..1) {
            for (dy in -1..1) {
                grid[cellKey(ix + dx, iy + dy)]?.forEach { candidateIndices.add(it) }
            }
        }
        val toScan =
            if (candidateIndices.isEmpty()) {
                segments.indices
            } else {
                candidateIndices
            }
        var best = Double.MAX_VALUE
        for (i in toScan) {
            val d = segments[i].distanceMetersApprox(lat, lon)
            if (d < best) best = d
        }
        return if (best == Double.MAX_VALUE) null else best
    }

    companion object {
        fun fromGeoJsonFileOrEmpty(file: File): LocalRoadIndex = parseGeoJsonRoadPack(file).first

        /**
         * Parse GeoJSON FeatureCollection (or single Feature) into segments + grid index.
         */
        fun parseGeoJsonRoadPack(file: File): Pair<LocalRoadIndex, RoadPackDiagnostics> {
            if (!file.isFile) {
                return Pair(
                    emptyIndex(),
                    RoadPackDiagnostics(0, 0, null, null, null, null, "missing_file", null),
                )
            }
            return try {
                val text = file.readText(Charsets.UTF_8)
                val root = JSONObject(text)
                val (features, err) = featuresFromRoot(root)
                if (err != null) {
                    return Pair(
                        emptyIndex(),
                        RoadPackDiagnostics(0, 0, null, null, null, null, err, null),
                    )
                }
                val segments = mutableListOf<LineSegment>()
                var featCount = 0
                var minLat = Double.POSITIVE_INFINITY
                var maxLat = Double.NEGATIVE_INFINITY
                var minLon = Double.POSITIVE_INFINITY
                var maxLon = Double.NEGATIVE_INFINITY
                for (i in 0 until features.length()) {
                    val feat = features.getJSONObject(i)
                    val geom = feat.optJSONObject("geometry") ?: continue
                    featCount++
                    when (geom.optString("type")) {
                        "LineString" -> {
                            val coords = geom.getJSONArray("coordinates")
                            appendLineStringCoords(coords, segments) { la, lo ->
                                minLat = kotlin.math.min(minLat, la)
                                maxLat = kotlin.math.max(maxLat, la)
                                minLon = kotlin.math.min(minLon, lo)
                                maxLon = kotlin.math.max(maxLon, lo)
                            }
                        }
                        "MultiLineString" -> {
                            val lines = geom.getJSONArray("coordinates")
                            for (li in 0 until lines.length()) {
                                val coords = lines.getJSONArray(li)
                                appendLineStringCoords(coords, segments) { la, lo ->
                                    minLat = kotlin.math.min(minLat, la)
                                    maxLat = kotlin.math.max(maxLat, la)
                                    minLon = kotlin.math.min(minLon, lo)
                                    maxLon = kotlin.math.max(maxLon, lo)
                                }
                            }
                        }
                        else -> { }
                    }
                }
                val grid = buildGrid(segments, RoadFilterEngineConfig.gridCellDegrees)
                val index = LocalRoadIndex(segments, grid, RoadFilterEngineConfig.gridCellDegrees)
                val diag =
                    RoadPackDiagnostics(
                        featureCount = featCount,
                        segmentCount = segments.size,
                        minLat = if (minLat.isFinite()) minLat else null,
                        maxLat = if (maxLat.isFinite()) maxLat else null,
                        minLon = if (minLon.isFinite()) minLon else null,
                        maxLon = if (maxLon.isFinite()) maxLon else null,
                        loadError = null,
                        packVersion = readPackVersion(file),
                    )
                Pair(index, diag)
            } catch (e: Exception) {
                Pair(
                    emptyIndex(),
                    RoadPackDiagnostics(
                        0,
                        0,
                        null,
                        null,
                        null,
                        null,
                        e.message ?: e::class.java.simpleName,
                        null,
                    ),
                )
            }
        }

        private fun readPackVersion(geoJsonFile: File): String? {
            val parent = geoJsonFile.parentFile ?: return null
            val manifest = File(parent, "pack.json")
            if (manifest.isFile) {
                try {
                    val o = JSONObject(manifest.readText(Charsets.UTF_8))
                    val v = o.optString("version", "")
                    return v.ifEmpty { o.optString("packVersion", "") }.ifEmpty { null }
                } catch (_: Exception) { }
            }
            return parent.name.takeIf { it.isNotBlank() }
        }

        private fun featuresFromRoot(root: JSONObject): Pair<JSONArray, String?> {
            when (root.optString("type")) {
                "FeatureCollection" -> {
                    return Pair(root.getJSONArray("features"), null)
                }
                "Feature" -> {
                    val arr = JSONArray().put(root)
                    return Pair(arr, null)
                }
                else -> return Pair(JSONArray(), "unsupported_geojson_root")
            }
        }

        private fun appendLineStringCoords(
            coords: JSONArray,
            segments: MutableList<LineSegment>,
            onPoint: (Double, Double) -> Unit,
        ) {
            if (coords.length() < 2) return
            for (i in 1 until coords.length()) {
                val a = coords.getJSONArray(i - 1)
                val b = coords.getJSONArray(i)
                // GeoJSON position is [longitude, latitude]
                val aLon = a.getDouble(0)
                val aLat = a.getDouble(1)
                val bLon = b.getDouble(0)
                val bLat = b.getDouble(1)
                onPoint(aLat, aLon)
                onPoint(bLat, bLon)
                segments.add(LineSegment(aLon, aLat, bLon, bLat))
            }
        }

        private fun buildGrid(
            segments: List<LineSegment>,
            cellDeg: Double,
        ): Map<String, List<Int>> {
            val map = mutableMapOf<String, MutableList<Int>>()
            segments.forEachIndexed { idx, s ->
                // ax = lon, ay = lat (see [LineSegment.distanceMetersApprox])
                val minLat = kotlin.math.min(s.ay, s.by)
                val maxLat = kotlin.math.max(s.ay, s.by)
                val minLon = kotlin.math.min(s.ax, s.bx)
                val maxLon = kotlin.math.max(s.ax, s.bx)
                val i0 = kotlin.math.floor(minLat / cellDeg).toInt()
                val i1 = kotlin.math.floor(maxLat / cellDeg).toInt()
                val j0 = kotlin.math.floor(minLon / cellDeg).toInt()
                val j1 = kotlin.math.floor(maxLon / cellDeg).toInt()
                for (ix in i0..i1) {
                    for (iy in j0..j1) {
                        map.getOrPut(cellKey(ix, iy)) { mutableListOf() }.add(idx)
                    }
                }
            }
            return map.mapValues { (_, v) -> v.toList() }
        }

        private fun cellKey(
            ix: Int,
            iy: Int,
        ): String = "$ix,$iy"

        private fun emptyIndex(): LocalRoadIndex =
            LocalRoadIndex(emptyList(), emptyMap(), RoadFilterEngineConfig.gridCellDegrees)
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
