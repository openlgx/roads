package org.openlgx.roads.processing.ondevice

import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.openlgx.roads.data.local.db.RoadsDatabase
import org.openlgx.roads.data.local.db.entity.AnomalyCandidateEntity
import org.openlgx.roads.data.local.db.entity.DerivedWindowFeatureEntity
import org.openlgx.roads.data.local.db.entity.SegmentConsensusRecordEntity
import org.openlgx.roads.data.local.db.model.AnomalyType
import org.openlgx.roads.data.local.db.model.RoadEligibilityDisposition
import org.openlgx.roads.data.local.db.model.SessionProcessingState
import androidx.room.withTransaction

/**
 * On-device port of [analysis.roughness_lab] windowing + heuristics.
 * Deletes prior derived rows for [sessionId] then rewrites outputs in one transaction.
 */
@Singleton
class SessionProcessingOrchestrator
@Inject
constructor(
    private val database: RoadsDatabase,
) {
    suspend fun processSession(
        sessionId: Long,
        windowSeconds: Float,
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                database.recordingSessionDao().getById(sessionId) ?: error("missing session")
                val loc = database.locationSampleDao().listAllForSessionOrdered(sessionId).sortedBy { it.wallClockUtcEpochMs }
                val sen = database.sensorSampleDao().listAllForSessionOrdered(sessionId).sortedBy { it.wallClockUtcEpochMs }
                val motion = sen.motionLinearOrAccel()
                val gyro = sen.gyroSamples()
                val (tRate, rRate) = HeadingRateEstimator.bearingRateSeries(loc)

                val tRefs = mutableListOf<Double>()
                if (loc.isNotEmpty()) {
                    tRefs.add(loc.minOf { it.wallClockUtcEpochMs }.toDouble())
                    tRefs.add(loc.maxOf { it.wallClockUtcEpochMs }.toDouble())
                }
                if (motion.isNotEmpty()) {
                    tRefs.add(motion.minOf { it.wallClockUtcEpochMs }.toDouble())
                    tRefs.add(motion.maxOf { it.wallClockUtcEpochMs }.toDouble())
                }
                if (tRefs.isEmpty()) error("no time range")

                val tMin = tRefs.minOrNull()!!
                val tMax = tRefs.maxOrNull()!!
                val stepMs = windowSeconds * 1000.0
                if (tMax <= tMin + stepMs * 0.5) error("range too short")

                data class Built(
                    val entity: DerivedWindowFeatureEntity,
                    val roughnessNorm: Double,
                )

                val built = mutableListOf<Built>()
                var windowIndex = 0
                var w0 = tMin
                while (w0 + stepMs <= tMax + 0.001) {
                    val w1 = w0 + stepMs
                    var midLat = Double.NaN
                    var midLon = Double.NaN
                    var speedMean = Double.NaN
                    val locIn =
                        loc.filter {
                            it.wallClockUtcEpochMs.toDouble() >= w0 && it.wallClockUtcEpochMs.toDouble() < w1
                        }
                    if (locIn.isNotEmpty()) {
                        midLat = locIn.map { it.latitude }.average()
                        midLon = locIn.map { it.longitude }.average()
                        val sp = locIn.mapNotNull { it.speedMps?.toDouble() }
                        if (sp.isNotEmpty()) speedMean = sp.average()
                    }
                    val mstats = windowStatsMotion(motion, w0, w1)
                    val gstats = windowStatsGyro(gyro, w0, w1)
                    val (hMean, hStd, hAbsMean) =
                        HeadingRateEstimator.headingRateInWindow(tRate, rRate, w0, w1)
                    val hiFrac =
                        if (mstats.n >= 16) {
                            highFrequencyEnergyApprox(motion, w0, w1)
                        } else {
                            Double.NaN
                        }

                    val h = scoreHeuristics(hStd, hAbsMean, mstats.energyX, mstats.energyY, mstats.energyZ, speedMean, mstats.peakMag, mstats.rmsMag)

                    val elapsedStart =
                        locIn.minOfOrNull { it.elapsedRealtimeNanos }
                            ?: motion.filter {
                                it.wallClockUtcEpochMs.toDouble() >= w0 && it.wallClockUtcEpochMs.toDouble() < w1
                            }.minOfOrNull { it.elapsedRealtimeNanos }
                            ?: 0L
                    val elapsedEnd =
                        locIn.maxOfOrNull { it.elapsedRealtimeNanos }
                            ?: motion.filter {
                                it.wallClockUtcEpochMs.toDouble() >= w0 && it.wallClockUtcEpochMs.toDouble() < w1
                            }.maxOfOrNull { it.elapsedRealtimeNanos }
                            ?: elapsedStart

                    val bundle =
                        JSONObject().apply {
                            putFiniteOrNull("rms_gyro_mag", gstats.rms)
                            putFiniteOrNull("peak_gyro_mag", gstats.peak)
                            put("gyro_sample_count", gstats.n)
                            put("motion_sample_count", mstats.n)
                            putFiniteOrNull("spectral_high_frac_proxy", hiFrac)
                            putFiniteOrNull("heading_rate_mean_deg_s", hMean)
                            put("analysis_note", EXPERIMENTAL_NOTE)
                        }.toString()

                    val windowRoughness = if (mstats.rmsMag.isFinite()) mstats.rmsMag else 0.0

                    val entity =
                        DerivedWindowFeatureEntity(
                            sessionId = sessionId,
                            windowStartElapsedNanos = elapsedStart,
                            windowEndElapsedNanos = elapsedEnd,
                            windowLengthMetersApprox = null,
                            methodVersion = SessionProcessingMethodVersions.ROUGHNESS_LAB_PORT,
                            isExperimental = true,
                            roughnessProxyScore = windowRoughness,
                            roadResponseScore = h.scoreVerticalShock.takeIf { it.isFinite() },
                            qualityFlags = 0,
                            roadEligibilityDisposition = RoadEligibilityDisposition.UNKNOWN,
                            eligibilityConfidence = null,
                            featureBundleJson = bundle,
                            windowStartWallClockUtcEpochMs = w0.roundToLong(),
                            windowEndWallClockUtcEpochMs = w1.roundToLong(),
                            midLatitude = midLat.takeIf { it.isFinite() },
                            midLongitude = midLon.takeIf { it.isFinite() },
                            meanSpeedMps = speedMean.takeIf { it.isFinite() },
                            headingMeanDeg = hMean.takeIf { it.isFinite() },
                            predPrimaryLabel = h.predPrimary,
                            scoreCornering = h.scoreCornering.takeIf { it.isFinite() },
                            scoreVerticalShock = h.scoreVerticalShock.takeIf { it.isFinite() },
                            scoreStableCruise = h.scoreStableCruise.takeIf { it.isFinite() },
                            windowIndex = windowIndex,
                        )
                    built.add(Built(entity, windowRoughness))
                    windowIndex++
                    w0 += stepMs
                }

                val maxRms = built.maxOfOrNull { it.roughnessNorm }?.takeIf { it > 0 } ?: 1.0
                val normEntities =
                    built.map { b ->
                        val nrm = (b.roughnessNorm / maxRms).coerceIn(0.0, 1.0)
                        b.entity.copy(roughnessProxyScore = nrm)
                    }

                val anomalies = mutableListOf<AnomalyCandidateEntity>()
                normEntities.forEachIndexed { idx, w ->
                    val shock = w.scoreVerticalShock ?: 0.0
                    val isImpact = w.predPrimaryLabel == "vertical_impact" || shock > 0.45
                    if (!isImpact) return@forEachIndexed
                    val tMid = ((w.windowStartWallClockUtcEpochMs ?: 0L) + (w.windowEndWallClockUtcEpochMs ?: 0L)) / 2
                    val nearest = loc.minByOrNull { kotlin.math.abs(it.wallClockUtcEpochMs - tMid) }
                    anomalies.add(
                        AnomalyCandidateEntity(
                            sessionId = sessionId,
                            timeElapsedRealtimeNanos = nearest?.elapsedRealtimeNanos,
                            anomalyType = AnomalyType.UNKNOWN_EXPERIMENTAL,
                            score = shock,
                            confidence = shock.toFloat().coerceIn(0f, 1f),
                            methodVersion = SessionProcessingMethodVersions.HEURISTIC_CORNERING_SHOCK,
                            qualityFlags = 0,
                            detailsJson =
                                JSONObject().apply {
                                    put("pred", w.predPrimaryLabel)
                                    put("compass", compassLabelFromDeg(w.headingMeanDeg))
                                }.toString(),
                            wallClockUtcEpochMs = nearest?.wallClockUtcEpochMs ?: tMid,
                            latitude = nearest?.latitude ?: w.midLatitude,
                            longitude = nearest?.longitude ?: w.midLongitude,
                            speedMps = nearest?.speedMps?.toDouble(),
                            headingDeg = w.headingMeanDeg,
                            severityBucket = if (shock > 0.7) "high" else "medium",
                            derivedWindowId = null,
                        ),
                    )
                }

                val labelCounts = normEntities.groupingBy { it.predPrimaryLabel ?: "?" }.eachCount()
                val meanRough =
                    normEntities
                        .mapNotNull { it.roughnessProxyScore }
                        .filter { it.isFinite() }
                        .takeIf { it.isNotEmpty() }
                        ?.average()
                val labelJson = JSONObject()
                labelCounts.forEach { (k, v) -> labelJson.put(k, v) }
                val roadResponseAvg =
                    normEntities
                        .mapNotNull { it.roadResponseScore }
                        .filter { it.isFinite() }
                        .takeIf { it.isNotEmpty() }
                        ?.average()

                val summaryJson =
                    JSONObject().apply {
                        put("windowCount", normEntities.size)
                        put("anomalyCount", anomalies.size)
                        put("labelCounts", labelJson)
                        put("meanRoughnessProxy", meanRough.toJsonNumber())
                        put("meanRoadResponse", roadResponseAvg.toJsonNumber())
                        put("methodVersions", SessionProcessingMethodVersions.ROUGHNESS_LAB_PORT)
                        put("experimentalNote", EXPERIMENTAL_NOTE)
                    }.toString()

                database.withTransaction {
                    database.derivedWindowFeatureDao().deleteForSession(sessionId)
                    database.anomalyCandidateDao().deleteForSession(sessionId)
                    normEntities.forEach { database.derivedWindowFeatureDao().insert(it) }
                    anomalies.forEach { database.anomalyCandidateDao().insert(it) }
                }

                for (w in normEntities) {
                    val lat = w.midLatitude ?: continue
                    val lon = w.midLongitude ?: continue
                    val proxy = w.roughnessProxyScore ?: continue
                    val key =
                        "${SessionProcessingMethodVersions.CONSENSUS_LOCAL_BIN}|" +
                            String.format(Locale.US, "%.4f", lat) +
                            "|" +
                            String.format(Locale.US, "%.4f", lon)
                    val consDao = database.segmentConsensusRecordDao()
                    val now = System.currentTimeMillis()
                    val existing = consDao.getByKey(key)
                    if (existing == null) {
                        consDao.insert(
                            SegmentConsensusRecordEntity(
                                segmentKey = key,
                                observedCount = 1,
                                distinctDeviceCount = 1,
                                consensusRoughnessProxy = proxy,
                                stabilityScore = null,
                                methodVersion = SessionProcessingMethodVersions.CONSENSUS_LOCAL_BIN,
                                lastUpdatedEpochMs = now,
                                centroidLatitude = lat,
                                centroidLongitude = lon,
                                binSizeMetersApprox = 11,
                            ),
                        )
                    } else {
                        val n = existing.observedCount + 1
                        val prev = existing.consensusRoughnessProxy ?: 0.0
                        val prevN = existing.observedCount.coerceAtLeast(1)
                        val newAvg = (prev * prevN + proxy) / (prevN + 1)
                        consDao.updateConsensusStats(key, n, newAvg, now)
                    }
                }

                database.recordingSessionDao().updateAggregateScores(
                    sessionId = sessionId,
                    roughnessProxy = meanRough?.takeIf { it.isFinite() },
                    roughnessMethodVersion = SessionProcessingMethodVersions.ROUGHNESS_LAB_PORT,
                    roadResponse = roadResponseAvg?.takeIf { it.isFinite() },
                    roadResponseMethodVersion = SessionProcessingMethodVersions.HEURISTIC_CORNERING_SHOCK,
                )
                val refreshed = database.recordingSessionDao().getById(sessionId)!!
                database.recordingSessionDao().updateProcessingFields(
                    sessionId = sessionId,
                    processingState = SessionProcessingState.COMPLETED,
                    processingStartedAtEpochMs = refreshed.processingStartedAtEpochMs,
                    processingCompletedAtEpochMs = System.currentTimeMillis(),
                    processingLastError = null,
                    processingSummaryJson = summaryJson,
                )
            }
        }

    private companion object {
        const val EXPERIMENTAL_NOTE: String =
            "experimental on-device roughness lab — not IRI; not calibration-validated"
    }
}
