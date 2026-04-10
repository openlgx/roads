package org.openlgx.roads.roadpack

import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sqrt
import org.json.JSONObject
import org.openlgx.roads.data.local.db.entity.LocationSampleEntity
import org.openlgx.roads.data.local.db.model.RoadEligibilityDisposition
import org.openlgx.roads.data.local.settings.UploadRoadFilterUnknownPolicy

data class RoadFilterStats(
    val originalLocationCount: Int,
    val keptLocationCount: Int,
    val suppressedLocationCount: Int,
    val unknownLocationCount: Int,
    val originalDurationMs: Long,
    val keptDurationMs: Long,
    val suppressedDurationMs: Long,
    val suppressionReasonHistogram: Map<String, Int>,
    val roadPackVersion: String?,
    val filterMethodVersion: String,
    val lowValueSkip: Boolean,
    val uploadSkipReason: String?,
)

/**
 * Computes which location samples are kept for filtered export (does not write Room).
 */
object RoadPackFilterEngine {

    fun computeKeptLocations(
        locations: List<LocationSampleEntity>,
        index: LocalRoadIndex,
        bufferMeters: Float,
        unknownPolicy: UploadRoadFilterUnknownPolicy,
    ): Pair<Set<Long>, RoadFilterStats> {
        if (locations.isEmpty()) {
            return Pair(
                emptySet(),
                emptyStats(lowValueSkip = true, reason = "no_locations"),
            )
        }
        val n = locations.size
        val dispositions = Array(n) { RoadEligibilityDisposition.UNKNOWN }

        var lastEvalIdx = 0
        var lastEvalLat = locations[0].latitude
        var lastEvalLon = locations[0].longitude
        var lastEvalTime = locations[0].wallClockUtcEpochMs
        dispositions[0] =
            classify(
                index = index,
                lat = lastEvalLat,
                lon = lastEvalLon,
                bufferMeters = bufferMeters,
                accuracyMeters = locations[0].horizontalAccuracyMeters,
            )

        for (i in 1 until n) {
            val loc = locations[i]
            val distFromLast =
                haversineMeters(lastEvalLat, lastEvalLon, loc.latitude, loc.longitude)
            val dt = loc.wallClockUtcEpochMs - lastEvalTime
            val shouldEval =
                distFromLast >= RoadFilterEngineConfig.distanceCadenceMeters ||
                    dt >= RoadFilterEngineConfig.timeCadenceMs
            if (shouldEval) {
                dispositions[i] =
                    classify(
                        index = index,
                        lat = loc.latitude,
                        lon = loc.longitude,
                        bufferMeters = bufferMeters,
                        accuracyMeters = loc.horizontalAccuracyMeters,
                    )
                lastEvalIdx = i
                lastEvalLat = loc.latitude
                lastEvalLon = loc.longitude
                lastEvalTime = loc.wallClockUtcEpochMs
            } else {
                dispositions[i] = dispositions[lastEvalIdx]
            }
        }

        val suppressible = BooleanArray(n)
        var runStart = 0
        while (runStart < n) {
            var runEnd = runStart
            val d0 = dispositions[runStart]
            while (runEnd + 1 < n && dispositions[runEnd + 1] == d0) {
                runEnd++
            }
            val strictOff =
                d0 == RoadEligibilityDisposition.UNLIKELY_OR_OFF_ROAD ||
                    d0 == RoadEligibilityDisposition.PRIVATE_OR_CARPARK_HEURISTIC
            val unknown = d0 == RoadEligibilityDisposition.UNKNOWN
            val eligibleUnknown =
                when (unknownPolicy) {
                    UploadRoadFilterUnknownPolicy.UPLOAD -> false
                    UploadRoadFilterUnknownPolicy.SUPPRESS -> true
                    UploadRoadFilterUnknownPolicy.TRIM -> true
                }
            val needsLongRun = strictOff || (unknown && eligibleUnknown)
            if (needsLongRun) {
                val first = locations[runStart]
                val last = locations[runEnd]
                var runDist = 0.0
                for (j in runStart until runEnd) {
                    runDist +=
                        haversineMeters(
                            locations[j].latitude,
                            locations[j].longitude,
                            locations[j + 1].latitude,
                            locations[j + 1].longitude,
                        )
                }
                val runDur = last.wallClockUtcEpochMs - first.wallClockUtcEpochMs
                val mult =
                    if (unknown && unknownPolicy == UploadRoadFilterUnknownPolicy.TRIM) {
                        1.35f
                    } else {
                        1f
                    }
                val minM = RoadFilterEngineConfig.minOffRoadRunMeters * mult
                val minMs = (RoadFilterEngineConfig.minOffRoadRunMs * mult).toLong()
                if (runDist >= minM && runDur >= minMs) {
                    for (j in runStart..runEnd) {
                        suppressible[j] = true
                    }
                }
            }
            runStart = runEnd + 1
        }

        val filteredKept = mutableSetOf<Long>()
        var unknownCount = 0
        for (i in 0 until n) {
            when (dispositions[i]) {
                RoadEligibilityDisposition.UNKNOWN -> unknownCount++
                else -> {}
            }
            if (!suppressible[i]) {
                filteredKept.add(locations[i].id)
            }
        }

        val t0 = locations.first().wallClockUtcEpochMs
        val t1 = locations.last().wallClockUtcEpochMs
        val origDur = (t1 - t0).coerceAtLeast(0L)

        val hist = mutableMapOf<String, Int>()
        for (i in 0 until n) {
            if (suppressible[i]) {
                val key =
                    when (dispositions[i]) {
                        RoadEligibilityDisposition.UNKNOWN -> "UNKNOWN"
                        RoadEligibilityDisposition.UNLIKELY_OR_OFF_ROAD,
                        RoadEligibilityDisposition.PRIVATE_OR_CARPARK_HEURISTIC,
                        -> "OFF_PUBLIC_ROAD"
                        else -> "OTHER"
                    }
                hist[key] = (hist[key] ?: 0) + 1
            }
        }

        val keptList = locations.filter { filteredKept.contains(it.id) }
        val keptDur =
            if (keptList.size >= 2) {
                (keptList.last().wallClockUtcEpochMs - keptList.first().wallClockUtcEpochMs)
                    .coerceAtLeast(0L)
            } else {
                0L
            }
        val suppressedCount = n - filteredKept.size
        val supDur = (origDur - keptDur).coerceAtLeast(0L)

        val minKeptNeeded =
            maxOf(
                RoadFilterEngineConfig.lowValueMinKeptCount,
                kotlin.math.ceil(n * RoadFilterEngineConfig.lowValueKeptFraction.toDouble())
                    .toInt(),
            )
        val lowValue = filteredKept.size < minKeptNeeded
        val skipReason =
            if (lowValue) {
                "low_value_road_kept_fraction"
            } else {
                null
            }

        val finalKept = if (lowValue) emptySet() else filteredKept

        return Pair(
            finalKept,
            RoadFilterStats(
                originalLocationCount = n,
                keptLocationCount = filteredKept.size,
                suppressedLocationCount = suppressedCount,
                unknownLocationCount = unknownCount,
                originalDurationMs = origDur,
                keptDurationMs = keptDur,
                suppressedDurationMs = supDur,
                suppressionReasonHistogram = hist,
                roadPackVersion = null,
                filterMethodVersion = RoadFilterEngineConfig.filterMethodVersion,
                lowValueSkip = lowValue,
                uploadSkipReason = skipReason,
            ),
        )
    }

    fun statsToSummaryJson(
        stats: RoadFilterStats,
        roadPackVersion: String?,
    ): String {
        val hist = JSONObject()
        stats.suppressionReasonHistogram.forEach { (k, v) -> hist.put(k, v) }
        val o =
            JSONObject().apply {
                put("originalSampleCount", stats.originalLocationCount)
                put("keptSampleCount", stats.keptLocationCount)
                put("suppressedSampleCount", stats.suppressedLocationCount)
                put("unknownSampleCount", stats.unknownLocationCount)
                put("originalDurationMs", stats.originalDurationMs)
                put("keptDurationMs", stats.keptDurationMs)
                put("suppressedDurationMs", stats.suppressedDurationMs)
                put("suppressionReasonHistogram", hist)
                put("roadPackVersion", roadPackVersion ?: JSONObject.NULL)
                put("filterMethodVersion", stats.filterMethodVersion)
                put("lowValueSkip", stats.lowValueSkip)
                put("uploadSkipReason", stats.uploadSkipReason ?: JSONObject.NULL)
            }
        return o.toString()
    }

    private fun classify(
        index: LocalRoadIndex,
        lat: Double,
        lon: Double,
        bufferMeters: Float,
        accuracyMeters: Float?,
    ): RoadEligibilityDisposition {
        if (accuracyMeters != null &&
            accuracyMeters > RoadFilterEngineConfig.maxHorizontalAccuracyMetersForEval
        ) {
            return RoadEligibilityDisposition.UNKNOWN
        }
        val d = index.nearestDistanceMeters(lat, lon) ?: return RoadEligibilityDisposition.UNKNOWN
        val buf = bufferMeters.toDouble()
        val mult = RoadFilterEngineConfig.offRoadDistanceMultiplier.toDouble()
        return when {
            d <= buf * 0.5 -> RoadEligibilityDisposition.LIKELY_PUBLIC_ROAD
            d <= buf -> RoadEligibilityDisposition.NEAR_PUBLIC_ROAD
            d > buf * mult -> RoadEligibilityDisposition.UNLIKELY_OR_OFF_ROAD
            else -> RoadEligibilityDisposition.NEAR_PUBLIC_ROAD
        }
    }

    private fun haversineMeters(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double,
    ): Double {
        val r = 6_371_000.0
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = Math.toRadians(lat2 - lat1)
        val dl = Math.toRadians(lon2 - lon1)
        val a =
            kotlin.math.sin(dp / 2) * kotlin.math.sin(dp / 2) +
                cos(p1) * cos(p2) * kotlin.math.sin(dl / 2) * kotlin.math.sin(dl / 2)
        return 2 * r * kotlin.math.asin(min(1.0, sqrt(a)))
    }

    private fun emptyStats(
        lowValueSkip: Boolean,
        reason: String,
    ): RoadFilterStats =
        RoadFilterStats(
            originalLocationCount = 0,
            keptLocationCount = 0,
            suppressedLocationCount = 0,
            unknownLocationCount = 0,
            originalDurationMs = 0L,
            keptDurationMs = 0L,
            suppressedDurationMs = 0L,
            suppressionReasonHistogram = emptyMap(),
            roadPackVersion = null,
            filterMethodVersion = RoadFilterEngineConfig.filterMethodVersion,
            lowValueSkip = lowValueSkip,
            uploadSkipReason = reason,
        )

    fun mergeIntervalsForSensors(
        keptLocations: List<LocationSampleEntity>,
        marginMs: Long,
    ): List<LongRange> {
        if (keptLocations.isEmpty()) return emptyList()
        val sorted = keptLocations.sortedBy { it.wallClockUtcEpochMs }
        val ranges = mutableListOf<LongRange>()
        var curStart = sorted[0].wallClockUtcEpochMs - marginMs
        var curEnd = sorted[0].wallClockUtcEpochMs + marginMs
        for (i in 1 until sorted.size) {
            val t = sorted[i].wallClockUtcEpochMs
            if (t <= curEnd + 1) {
                curEnd = maxOf(curEnd, t + marginMs)
            } else {
                ranges.add(curStart..curEnd)
                curStart = t - marginMs
                curEnd = t + marginMs
            }
        }
        ranges.add(curStart..curEnd)
        return ranges
    }

    fun wallClockInAnyRange(
        t: Long,
        ranges: List<LongRange>,
    ): Boolean = ranges.any { t in it }
}
