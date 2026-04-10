package org.openlgx.roads.ui.review

import android.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import org.json.JSONArray
import org.json.JSONObject
import org.openlgx.roads.data.local.db.RoadsDatabase
import org.openlgx.roads.processing.ondevice.compassLabelFromDeg

/**
 * Versioned contract for WebView trip review ([reviewPayloadVersion] == 2).
 * (Version 1 is still accepted by the WebView for older cached payloads.)
 */
@Singleton
class ReviewPayloadBuilder
@Inject
constructor(
    private val database: RoadsDatabase,
) {
    suspend fun buildSessionPayload(sessionId: Long): String {
        val session = database.recordingSessionDao().getById(sessionId) ?: return "{}"
        val loc = database.locationSampleDao().listAllForSessionOrdered(sessionId)
        val windows = database.derivedWindowFeatureDao().listForSessionOrdered(sessionId)
        val anomalies = database.anomalyCandidateDao().listForSessionOrdered(sessionId)

        val t0 =
            sequenceOf(
                loc.minOfOrNull { it.wallClockUtcEpochMs }?.toDouble(),
                windows.minOfOrNull { it.windowStartWallClockUtcEpochMs ?: Long.MAX_VALUE }
                    ?.takeIf { it != Long.MAX_VALUE.toLong() }?.toDouble(),
            ).filterNotNull().minOrNull() ?: 0.0

        val routeArr = buildRoutePointsArray(loc, windows, sessionId, t0, maxPoints = null)
        val anoArr = buildAnomaliesArray(anomalies, t0)
        val chartSeries = buildChartSeries(sessionId, t0)

        val root =
            JSONObject().apply {
                put("reviewPayloadVersion", 2)
                put("reviewKind", "singleSession")
                put(
                    "session",
                    JSONObject().apply {
                        put("sessionId", session.id)
                        put("uuid", session.uuid)
                        put("processingState", session.processingState.name)
                        put("roughnessMethodVersion", session.roughnessMethodVersion)
                        put("experimental", true)
                    },
                )
                put("routePoints", routeArr)
                put("windows", buildWindowsArray(windows, session.id))
                put("anomalies", anoArr)
                put("consensusBins", JSONArray())
                put("chartSeries", chartSeries)
                put(
                    "experimentalDisclaimer",
                    "Experimental heuristics - not IRI - not calibration-validated.",
                )
            }
        return root.toString()
    }

    /**
     * All saved sessions polylines + roughness markers on one map (no charts).
     * @param excludedSessionIds trips to omit from the map (e.g. user unchecked them); default none.
     */
    suspend fun buildSessionsOverviewMapPayload(excludedSessionIds: Set<Long> = emptySet()): String {
        val allRows = database.recordingSessionDao().listAllSessionsOrdered()
        if (allRows.isEmpty()) {
            return overviewShellEmpty()
        }
        val sessions = allRows.filter { it.id !in excludedSessionIds }
        if (sessions.isEmpty()) {
            return overviewShellWithDisclaimer(
                "No trips on the map — turn on at least one trip with the checkboxes above the list.",
            )
        }
        val routeArr = JSONArray()
        val anomaliesGlobal = JSONArray()
        val windowsGlobal = JSONArray()
        var globalT0 = Double.MAX_VALUE
        for (s in sessions) {
            val loc = database.locationSampleDao().listAllForSessionOrdered(s.id)
            if (loc.isEmpty()) continue
            val tSess = loc.minOf { it.wallClockUtcEpochMs }.toDouble()
            if (tSess < globalT0) globalT0 = tSess
        }
        if (globalT0 == Double.MAX_VALUE) globalT0 = 0.0

        for (s in sessions) {
            val loc = database.locationSampleDao().listAllForSessionOrdered(s.id)
            if (loc.size < 2) continue
            val windows = database.derivedWindowFeatureDao().listForSessionOrdered(s.id)
            appendRoutePointsDownsampled(routeArr, loc, windows, s.id, globalT0, MAX_POINTS_PER_SESSION_OVERVIEW)
            appendWindowsForMap(windowsGlobal, windows, s.id)
            val an = database.anomalyCandidateDao().listForSessionOrdered(s.id)
            for (a in an) {
                anomaliesGlobal.put(
                    JSONObject().apply {
                        put("tRelS", ((a.wallClockUtcEpochMs ?: 0L) - globalT0) / 1000.0)
                        put("lat", a.latitude)
                        put("lon", a.longitude)
                        put("severity", a.severityBucket)
                        put("score", a.score)
                        put("sessionId", s.id)
                    },
                )
            }
        }

        val root =
            JSONObject().apply {
                put("reviewPayloadVersion", 2)
                put("reviewKind", "multiSessionMap")
                put("routePoints", routeArr)
                put("windows", windowsGlobal)
                put("anomalies", anomaliesGlobal)
                put("consensusBins", JSONArray())
                put("chartSeries", JSONObject())
                put(
                    "experimentalDisclaimer",
                    "All trips overview - tap a roughness dot to open that trip (if supported).",
                )
            }
        return root.toString()
    }

    /**
     * Multi-session map plus consensus heat markers (no charts).
     * @param consensusOverlayDefault initial visibility for consensus layer (from settings).
     */
    suspend fun buildAllRunsMapPayload(
        consensusOverlayDefault: Boolean = true,
        excludedSessionIds: Set<Long> = emptySet(),
    ): String {
        val root = JSONObject(buildSessionsOverviewMapPayload(excludedSessionIds))
        root.put("reviewKind", "allRunsMap")
        root.put("initialConsensusVisible", consensusOverlayDefault)
        val bins = JSONArray()
        val rows = database.segmentConsensusRecordDao().listAllOrderByUpdated()
        for (e in rows) {
            val la = e.centroidLatitude ?: continue
            val lo = e.centroidLongitude ?: continue
            bins.put(
                JSONObject().apply {
                    put("lat", la)
                    put("lon", lo)
                    put("proxy", e.consensusRoughnessProxy ?: 0.0)
                    put("key", e.segmentKey)
                    put("observed", e.observedCount)
                },
            )
        }
        root.put("consensusBins", bins)
        root.put(
            "experimentalDisclaimer",
            "All runs + local consensus bins - use toggles for layers. Not IRI / not calibrated.",
        )
        return root.toString()
    }

    private fun overviewShellEmpty(): String =
        overviewShellWithDisclaimer("No sessions to show on the map yet.")

    private fun overviewShellWithDisclaimer(disclaimer: String): String =
        JSONObject().apply {
            put("reviewPayloadVersion", 2)
            put("reviewKind", "multiSessionMap")
            put("routePoints", JSONArray())
            put("windows", JSONArray())
            put("anomalies", JSONArray())
            put("consensusBins", JSONArray())
            put("chartSeries", JSONObject())
            put("experimentalDisclaimer", disclaimer)
        }.toString()

    fun encodeForWebView(json: String): String =
        Base64.encodeToString(json.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    private fun buildRoutePointsArray(
        loc: List<org.openlgx.roads.data.local.db.entity.LocationSampleEntity>,
        windows: List<org.openlgx.roads.data.local.db.entity.DerivedWindowFeatureEntity>,
        sessionId: Long,
        t0: Double,
        maxPoints: Int?,
    ): JSONArray {
        val arr = JSONArray()
        appendRoutePointsDownsampled(arr, loc, windows, sessionId, t0, maxPoints ?: loc.size)
        return arr
    }

    private fun appendRoutePointsDownsampled(
        routeArr: JSONArray,
        loc: List<org.openlgx.roads.data.local.db.entity.LocationSampleEntity>,
        windows: List<org.openlgx.roads.data.local.db.entity.DerivedWindowFeatureEntity>,
        sessionId: Long,
        t0: Double,
        maxPoints: Int,
    ) {
        if (loc.isEmpty()) return
        val step = max(1, loc.size / maxPoints)
        var i = 0
        while (i < loc.size) {
            val p = loc[i]
            val tRel = (p.wallClockUtcEpochMs - t0) / 1000.0
            val spd = p.speedMps?.toDouble()
            val entry =
                JSONObject().apply {
                    put("tRelS", tRel)
                    put("lat", p.latitude)
                    put("lon", p.longitude)
                    put("sessionId", sessionId)
                    if (spd != null) put("speedKmh", spd * 3.6)
                    val w =
                        windows.minByOrNull { wk ->
                            val mid =
                                ((wk.windowStartWallClockUtcEpochMs ?: 0L) +
                                    (wk.windowEndWallClockUtcEpochMs ?: 0L)) / 2
                            kotlin.math.abs(mid - p.wallClockUtcEpochMs)
                        }
                    val norm = w?.roughnessProxyScore
                    if (norm != null) put("roughnessNorm", norm)
                }
            routeArr.put(entry)
            i += step
        }
        val last = loc.last()
        if (step > 1 && (loc.size - 1) % step != 0) {
            val p = last
            val tRel = (p.wallClockUtcEpochMs - t0) / 1000.0
            val spd = p.speedMps?.toDouble()
            val entry =
                JSONObject().apply {
                    put("tRelS", tRel)
                    put("lat", p.latitude)
                    put("lon", p.longitude)
                    put("sessionId", sessionId)
                    if (spd != null) put("speedKmh", spd * 3.6)
                    val w =
                        windows.minByOrNull { wk ->
                            val mid =
                                ((wk.windowStartWallClockUtcEpochMs ?: 0L) +
                                    (wk.windowEndWallClockUtcEpochMs ?: 0L)) / 2
                            kotlin.math.abs(mid - p.wallClockUtcEpochMs)
                        }
                    val norm = w?.roughnessProxyScore
                    if (norm != null) put("roughnessNorm", norm)
                }
            routeArr.put(entry)
        }
    }

    private fun buildWindowsArray(
        windows: List<org.openlgx.roads.data.local.db.entity.DerivedWindowFeatureEntity>,
        sessionId: Long,
    ): JSONArray {
        val winArr = JSONArray()
        for (w in windows) {
            winArr.put(
                JSONObject().apply {
                    put("sessionId", sessionId)
                    put("windowIndex", w.windowIndex)
                    put("startMs", w.windowStartWallClockUtcEpochMs)
                    put("endMs", w.windowEndWallClockUtcEpochMs)
                    put("midLat", w.midLatitude)
                    put("midLon", w.midLongitude)
                    put("pred", w.predPrimaryLabel)
                    put("roughnessProxy", w.roughnessProxyScore)
                    put("scoreCornering", w.scoreCornering)
                    put("scoreShock", w.scoreVerticalShock)
                    put("compass", compassLabelFromDeg(w.headingMeanDeg))
                },
            )
        }
        return winArr
    }

    /** Map layer: one marker per derived window (matches desktop-style payloads that plot window centroids). */
    private fun appendWindowsForMap(
        out: JSONArray,
        windows: List<org.openlgx.roads.data.local.db.entity.DerivedWindowFeatureEntity>,
        sessionId: Long,
    ) {
        for (w in windows) {
            val la = w.midLatitude ?: continue
            val lo = w.midLongitude ?: continue
            val proxy = w.roughnessProxyScore ?: continue
            out.put(
                JSONObject().apply {
                    put("sessionId", sessionId)
                    put("windowIndex", w.windowIndex)
                    put("midLat", la)
                    put("midLon", lo)
                    put("roughnessProxy", proxy)
                    put("pred", w.predPrimaryLabel)
                },
            )
        }
    }

    private fun buildAnomaliesArray(
        anomalies: List<org.openlgx.roads.data.local.db.entity.AnomalyCandidateEntity>,
        t0: Double,
    ): JSONArray {
        val anoArr = JSONArray()
        for (a in anomalies) {
            anoArr.put(
                JSONObject().apply {
                    put("tRelS", ((a.wallClockUtcEpochMs ?: 0L) - t0) / 1000.0)
                    put("lat", a.latitude)
                    put("lon", a.longitude)
                    put("severity", a.severityBucket)
                    put("score", a.score)
                },
            )
        }
        return anoArr
    }

    private suspend fun buildChartSeries(sessionId: Long, t0: Double): JSONObject {
        val motion =
            database.sensorSampleDao().listAllForSessionOrdered(sessionId).filter { it.sensorType == 1 }
        val gyro = database.sensorSampleDao().listAllForSessionOrdered(sessionId).filter { it.sensorType == 4 }
        val accT = JSONArray()
        val accMag = JSONArray()
        for (s in motion) {
            accT.put((s.wallClockUtcEpochMs - t0) / 1000.0)
            val m =
                kotlin.math.sqrt(
                    (s.x * s.x + s.y * s.y + s.z * s.z).toDouble(),
                )
            accMag.put(m)
        }
        val gyrT = JSONArray()
        val gyrMag = JSONArray()
        for (s in gyro) {
            gyrT.put((s.wallClockUtcEpochMs - t0) / 1000.0)
            val m =
                kotlin.math.sqrt(
                    (s.x * s.x + s.y * s.y + s.z * s.z).toDouble(),
                )
            gyrMag.put(m)
        }
        return JSONObject().apply {
            put("accT", accT)
            put("accMag", accMag)
            put("gyrT", gyrT)
            put("gyrMag", gyrMag)
        }
    }

    private companion object {
        const val MAX_POINTS_PER_SESSION_OVERVIEW = 450
    }
}
