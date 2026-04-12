package org.openlgx.roads.export

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.io.DEFAULT_BUFFER_SIZE
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.json.JSONArray
import org.json.JSONObject
import org.openlgx.roads.BuildConfig
import org.openlgx.roads.data.local.db.RoadsDatabase
import org.openlgx.roads.data.local.settings.AppSettingsRepository
import org.openlgx.roads.data.local.db.entity.AnomalyCandidateEntity
import org.openlgx.roads.data.local.db.entity.DerivedWindowFeatureEntity
import org.openlgx.roads.data.local.db.entity.DeviceProfileEntity
import org.openlgx.roads.data.local.db.entity.LocationSampleEntity
import org.openlgx.roads.data.local.db.entity.RecordingSessionEntity
import org.openlgx.roads.data.repo.session.SessionInspectionRepository
import org.openlgx.roads.roadpack.RoadFilterEngineConfig
import org.openlgx.roads.roadpack.RoadPackFilterEngine
import org.openlgx.roads.validation.SessionCaptureValidationSummary

data class SessionExportResult(
    val sessionId: Long,
    val directory: File,
    val zipFile: File,
)

@Singleton
class SessionExporter
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val database: RoadsDatabase,
    private val sessionInspectionRepository: SessionInspectionRepository,
    private val exportDiagnosticsRepository: ExportDiagnosticsRepository,
    private val appSettingsRepository: AppSettingsRepository,
) {

    /**
     * @param onExportProgress Optional: [rowsDone] / [rowsTotal] while writing CSV/JSON (locations + sensors);
     *   [rowsTotal] is an estimate from session detail counts (derived/anomaly add minor cost after).
     */
    suspend fun exportSession(
        sessionId: Long,
        onExportProgress: (suspend (rowsDone: Long, rowsTotal: Long) -> Unit)? = null,
    ): Result<SessionExportResult> =
        withContext(Dispatchers.IO) {
            try {
                val detail =
                    sessionInspectionRepository.getSessionDetail(sessionId)
                        ?: return@withContext Result.failure(IllegalArgumentException("Session not found"))
                val session = detail.session

                val rowsTotal =
                    (detail.locationSampleCount + detail.sensorSampleCount).coerceAtLeast(1L)
                var rowsDone = 0L
                suspend fun report() {
                    onExportProgress?.invoke(rowsDone, rowsTotal)
                }

                val root = File(context.getExternalFilesDir(null), "olgx_exports").apply { mkdirs() }
                val folderName = "session_${session.uuid}_${System.currentTimeMillis()}"
                val dir = File(root, folderName).apply { mkdirs() }

                File(dir, "session.json").writeText(session.toExportJson().toString(2))

                writeLocationCsvAndJson(dir, sessionId) { pageRows ->
                    rowsDone += pageRows
                    report()
                }
                writeSensorCsvAndJson(dir, sessionId) { pageRows ->
                    rowsDone += pageRows
                    report()
                }

                val derivedCount =
                    writeDerivedWindowFeaturesCsvAndJson(dir, sessionId)
                val anomalyCount = writeAnomalyCandidatesCsvAndJson(dir, sessionId)

                val profile = database.deviceProfileDao().firstOrNull()
                val calibrationWorkflowEnabled =
                    appSettingsRepository.settings.first().calibrationWorkflowEnabled
                val manifest =
                    buildManifest(
                        exportDir = dir,
                        session = session,
                        locationCount = detail.locationSampleCount,
                        sensorCount = detail.sensorSampleCount,
                        derivedWindowFeatureCount = derivedCount,
                        anomalyCandidateCount = anomalyCount,
                        validation = detail.validation,
                        deviceProfile = profile,
                        calibrationWorkflowEnabled = calibrationWorkflowEnabled,
                    )
                File(dir, "manifest.json").writeText(manifest.toString(2))

                val zipFile = File(root, "$folderName.zip")
                zipDirectory(dir, zipFile)

                exportDiagnosticsRepository.recordSuccess(
                    directoryAbsolutePath = dir.absolutePath,
                    zipAbsolutePath = zipFile.absolutePath,
                    sessionId = sessionId,
                )

                Result.success(
                    SessionExportResult(
                        sessionId = sessionId,
                        directory = dir,
                        zipFile = zipFile,
                    ),
                )
            } catch (e: Exception) {
                exportDiagnosticsRepository.recordFailure(e.message ?: e::class.java.simpleName)
                Result.failure(e)
            }
        }

    /**
     * Filtered export: same canonical filenames as [exportSession]; additive [road_filter_summary.json]
     * and optional additive manifest keys only.
     *
     * @param keptLocations Pre-filtered location rows (same list used for road filter stats) to avoid a second
     *   full-table load from SQLite.
     * @param onExportProgress Optional row progress while writing (sensor pages after locations are written).
     */
    suspend fun exportSessionFilteredForUpload(
        sessionId: Long,
        keptLocations: List<LocationSampleEntity>,
        roadPackVersion: String?,
        roadFilterSummaryJson: String,
        onExportProgress: (suspend (rowsDone: Long, rowsTotal: Long) -> Unit)? = null,
    ): Result<SessionExportResult> =
        withContext(Dispatchers.IO) {
            try {
                val detail =
                    sessionInspectionRepository.getSessionDetail(sessionId)
                        ?: return@withContext Result.failure(IllegalArgumentException("Session not found"))
                val session = detail.session
                val root = File(context.getExternalFilesDir(null), "olgx_exports").apply { mkdirs() }
                val folderName = "session_${session.uuid}_filtered_${System.currentTimeMillis()}"
                val dir = File(root, folderName).apply { mkdirs() }

                File(dir, "session.json").writeText(session.toExportJson().toString(2))

                val keptLocs = keptLocations.sortedBy { it.wallClockUtcEpochMs }
                val sensorTotalEstimate = detail.sensorSampleCount.coerceAtLeast(1L)
                val rowsTotal =
                    (keptLocs.size.toLong() + sensorTotalEstimate).coerceAtLeast(1L)
                var rowsDone = 0L
                suspend fun report() {
                    onExportProgress?.invoke(rowsDone, rowsTotal)
                }

                val ranges =
                    RoadPackFilterEngine.mergeIntervalsForSensors(
                        keptLocs,
                        RoadFilterEngineConfig.sensorKeepMarginMs,
                    )

                writeLocationListCsvAndJson(dir, keptLocs)
                rowsDone += keptLocs.size.toLong()
                report()
                val sensorKept =
                    writeSensorCsvAndJsonFiltered(dir, sessionId, ranges) { pageRows ->
                        rowsDone += pageRows
                        report()
                    }
                val allDerived = database.derivedWindowFeatureDao().listForSessionOrdered(sessionId)
                val derivedFiltered =
                    allDerived.filter { rangesOverlapWallWindow(it, ranges) }
                val derivedCount = writeDerivedWindowFeaturesList(dir, derivedFiltered)
                val allAno = database.anomalyCandidateDao().listForSessionOrdered(sessionId)
                val anoFiltered =
                    allAno.filter { row ->
                        val t = row.wallClockUtcEpochMs
                        t == null || RoadPackFilterEngine.wallClockInAnyRange(t, ranges)
                    }
                val anomalyCount = writeAnomalyCandidatesList(dir, anoFiltered)

                val profile = database.deviceProfileDao().firstOrNull()
                val calibrationWorkflowEnabled =
                    appSettingsRepository.settings.first().calibrationWorkflowEnabled
                val additive =
                    JSONObject().apply {
                        put("roadFilterApplied", true)
                        put("filterMethodVersion", RoadFilterEngineConfig.filterMethodVersion)
                        if (roadPackVersion != null) {
                            put("roadPackVersion", roadPackVersion)
                        } else {
                            put("roadPackVersion", JSONObject.NULL)
                        }
                    }
                val manifest =
                    buildManifest(
                        exportDir = dir,
                        session = session,
                        locationCount = keptLocs.size.toLong(),
                        sensorCount = sensorKept,
                        derivedWindowFeatureCount = derivedCount,
                        anomalyCandidateCount = anomalyCount,
                        validation = detail.validation,
                        deviceProfile = profile,
                        calibrationWorkflowEnabled = calibrationWorkflowEnabled,
                        additiveManifestKeys = additive,
                    )
                File(dir, "manifest.json").writeText(manifest.toString(2))
                File(dir, "road_filter_summary.json").writeText(roadFilterSummaryJson)

                val zipFile = File(root, "$folderName.zip")
                zipDirectory(dir, zipFile)

                exportDiagnosticsRepository.recordSuccess(
                    directoryAbsolutePath = dir.absolutePath,
                    zipAbsolutePath = zipFile.absolutePath,
                    sessionId = sessionId,
                )

                Result.success(
                    SessionExportResult(sessionId = sessionId, directory = dir, zipFile = zipFile),
                )
            } catch (e: Exception) {
                exportDiagnosticsRepository.recordFailure(e.message ?: e::class.java.simpleName)
                Result.failure(e)
            }
        }

    private fun rangesOverlapWallWindow(
        row: DerivedWindowFeatureEntity,
        ranges: List<LongRange>,
    ): Boolean {
        val t0 = row.windowStartWallClockUtcEpochMs
        val t1 = row.windowEndWallClockUtcEpochMs
        if (t0 == null || t1 == null) return true
        return ranges.any { r -> t0 <= r.last && t1 >= r.first }
    }

    private fun buildManifest(
        exportDir: File,
        session: RecordingSessionEntity,
        locationCount: Long,
        sensorCount: Long,
        derivedWindowFeatureCount: Int,
        anomalyCandidateCount: Int,
        validation: SessionCaptureValidationSummary,
        deviceProfile: DeviceProfileEntity?,
        calibrationWorkflowEnabled: Boolean,
        additiveManifestKeys: JSONObject? = null,
    ): JSONObject {
        val files =
            JSONArray().apply {
                put(fileEntry("session.json", "session_metadata"))
                put(fileEntry("manifest.json", "export_manifest"))
                put(fileEntry("location_samples.csv", "location_csv"))
                put(fileEntry("location_samples.json", "location_json"))
                put(fileEntry("sensor_samples.csv", "sensor_csv"))
                put(fileEntry("sensor_samples.json", "sensor_json"))
                put(fileEntry("derived_window_features.csv", "derived_window_features_csv"))
                put(fileEntry("derived_window_features.json", "derived_window_features_json"))
                put(fileEntry("anomaly_candidates.csv", "anomaly_candidates_csv"))
                put(fileEntry("anomaly_candidates.json", "anomaly_candidates_json"))
            }

        val device =
            if (deviceProfile != null) {
                JSONObject().apply {
                    put("reference", "embedded")
                    put("installUuid", deviceProfile.installUuid)
                    put("manufacturer", deviceProfile.manufacturer)
                    put("model", deviceProfile.model)
                    put("brand", deviceProfile.brand)
                    put("sdkInt", deviceProfile.sdkInt)
                    put("sensorCapabilitiesJson", deviceProfile.sensorCapabilitiesJson)
                    put("lastProfiledAtEpochMs", deviceProfile.lastProfiledAtEpochMs)
                }
            } else {
                JSONObject().apply {
                    put("reference", "none")
                    put("note", "No device_profiles row; install profiler in a later phase.")
                }
            }

        val validationJson =
            JSONObject().apply {
                put("hasLocationData", validation.hasLocationData)
                put("hasSensorData", validation.hasSensorData)
                put("hasAccelerometerSamples", validation.hasAccelerometerSamples)
                put("hasGyroscopeSamples", validation.hasGyroscopeSamples)
                put("locationWallClockMonotonic", validation.locationWallClockMonotonic)
                put("sensorWallClockMonotonic", validation.sensorWallClockMonotonic)
                put("locationSuspectGaps", validation.locationSuspectGaps)
                put("sensorSuspectGaps", validation.sensorSuspectGaps)
                put("suspiciouslyLowSampleRate", validation.suspiciouslyLowSampleRate)
                put("notes", JSONArray(validation.notes))
            }

        val processingJson =
            JSONObject().apply {
                put("processingState", session.processingState.name)
                put("processingStartedAtEpochMs", session.processingStartedAtEpochMs)
                put("processingCompletedAtEpochMs", session.processingCompletedAtEpochMs)
                put("processingLastError", session.processingLastError)
                put("roughnessMethodVersion", session.roughnessMethodVersion)
                put("roadResponseMethodVersion", session.roadResponseMethodVersion)
                put(
                    "processingSummaryJson",
                    session.processingSummaryJson
                        ?: JSONObject.NULL,
                )
            }

        return JSONObject().apply {
            put("exportSchemaVersion", ExportConstants.EXPORT_SCHEMA_VERSION)
            put("exportMethodVersion", ExportConstants.EXPORT_METHOD_VERSION)
            put("roughnessMethodVersion", ExportConstants.ROUGHNESS_METHOD_VERSION_PLACEHOLDER)
            put("disclaimer", ExportConstants.DISCLAIMER)
            put("appVersionName", BuildConfig.VERSION_NAME)
            put("exportedAtEpochMs", System.currentTimeMillis())
            put("sessionId", session.id)
            put("sessionUuid", session.uuid)
            put("exportDirectoryName", exportDir.name)
            put("locationSampleCount", locationCount)
            put("sensorSampleCount", sensorCount)
            put("derivedWindowFeatureCount", derivedWindowFeatureCount)
            put("anomalyCandidateCount", anomalyCandidateCount)
            put("processing", processingJson)
            put("files", files)
            put("device", device)
            put("validationSummary", validationJson)
            put("calibrationWorkflowEnabled", calibrationWorkflowEnabled)
            put("calibrationLiteraturePointer", ExportConstants.CALIBRATION_LITERATURE_POINTER)
            additiveManifestKeys?.let { add ->
                val it = add.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    put(k, add.get(k))
                }
            }
        }
    }

    private fun fileEntry(
        name: String,
        role: String,
    ): JSONObject =
        JSONObject().apply {
            put("name", name)
            put("role", role)
        }

    private suspend fun writeLocationCsvAndJson(
        dir: File,
        sessionId: Long,
        onPageFinished: suspend (rowsInPage: Int) -> Unit = {},
    ) {
        val dao = database.locationSampleDao()
        val csv = File(dir, "location_samples.csv")
        val json = File(dir, "location_samples.json")
        val pageSize = 2000
        var offset = 0
        var firstJson = true

        BufferedWriter(json.writer(), DEFAULT_BUFFER_SIZE).use { jw ->
            jw.write("{\n  \"samples\": [\n")
            BufferedWriter(csv.writer(), DEFAULT_BUFFER_SIZE).use { cw ->
                cw.write(
                    "id,sessionId,elapsedRealtimeNanos,wallClockUtcEpochMs," +
                        "latitude,longitude,speedMps,bearingDegrees,horizontalAccuracyMeters," +
                        "altitudeMeters,roadEligibilityDisposition,eligibilityConfidence," +
                        "retainedRegardlessOfEligibility\n",
                )
                while (true) {
                    val page = dao.pageForSessionOrdered(sessionId, pageSize, offset)
                    if (page.isEmpty()) break
                    for (row in page) {
                        if (!firstJson) jw.write(",\n")
                        firstJson = false
                        jw.write(row.toJson().toString())
                        cw.write("${row.id},${row.sessionId},${row.elapsedRealtimeNanos},")
                        cw.write("${row.wallClockUtcEpochMs},${row.latitude},${row.longitude},")
                        cw.write("${row.speedMps ?: ""},${row.bearingDegrees ?: ""},")
                        cw.write("${row.horizontalAccuracyMeters ?: ""},${row.altitudeMeters ?: ""},")
                        cw.write("${row.roadEligibilityDisposition.name},")
                        cw.write("${row.eligibilityConfidence ?: ""},${row.retainedRegardlessOfEligibility}\n")
                    }
                    offset += page.size
                    onPageFinished(page.size)
                    yield()
                }
            }
            jw.write("\n  ]\n}\n")
        }
    }

    private fun writeLocationListCsvAndJson(
        dir: File,
        samples: List<LocationSampleEntity>,
    ) {
        val csv = File(dir, "location_samples.csv")
        val json = File(dir, "location_samples.json")
        var firstJson = true
        BufferedWriter(json.writer(), DEFAULT_BUFFER_SIZE).use { jw ->
            jw.write("{\n  \"samples\": [\n")
            BufferedWriter(csv.writer(), DEFAULT_BUFFER_SIZE).use { cw ->
                cw.write(
                    "id,sessionId,elapsedRealtimeNanos,wallClockUtcEpochMs," +
                        "latitude,longitude,speedMps,bearingDegrees,horizontalAccuracyMeters," +
                        "altitudeMeters,roadEligibilityDisposition,eligibilityConfidence," +
                        "retainedRegardlessOfEligibility\n",
                )
                for (row in samples) {
                    if (!firstJson) jw.write(",\n")
                    firstJson = false
                    jw.write(row.toJson().toString())
                    cw.write("${row.id},${row.sessionId},${row.elapsedRealtimeNanos},")
                    cw.write("${row.wallClockUtcEpochMs},${row.latitude},${row.longitude},")
                    cw.write("${row.speedMps ?: ""},${row.bearingDegrees ?: ""},")
                    cw.write("${row.horizontalAccuracyMeters ?: ""},${row.altitudeMeters ?: ""},")
                    cw.write("${row.roadEligibilityDisposition.name},")
                    cw.write("${row.eligibilityConfidence ?: ""},${row.retainedRegardlessOfEligibility}\n")
                }
            }
            jw.write("\n  ]\n}\n")
        }
    }

    /** @return count of sensor rows written */
    private suspend fun writeSensorCsvAndJsonFiltered(
        dir: File,
        sessionId: Long,
        ranges: List<LongRange>,
        onKeptPage: suspend (keptRowsInPage: Int) -> Unit = {},
    ): Long {
        val dao = database.sensorSampleDao()
        val csv = File(dir, "sensor_samples.csv")
        val json = File(dir, "sensor_samples.json")
        val pageSize = 2000
        var offset = 0
        var firstJson = true
        var count = 0L
        BufferedWriter(json.writer(), DEFAULT_BUFFER_SIZE).use { jw ->
            jw.write("{\n  \"samples\": [\n")
            BufferedWriter(csv.writer(), DEFAULT_BUFFER_SIZE).use { cw ->
                cw.write(
                    "id,sessionId,elapsedRealtimeNanos,wallClockUtcEpochMs," +
                        "sensorType,x,y,z,w,accuracy,roadEligibilityDisposition,eligibilityConfidence\n",
                )
                while (true) {
                    val page = dao.pageForSessionOrdered(sessionId, pageSize, offset)
                    if (page.isEmpty()) break
                    var keptThisPage = 0
                    for (row in page) {
                        if (!RoadPackFilterEngine.wallClockInAnyRange(row.wallClockUtcEpochMs, ranges)) {
                            continue
                        }
                        if (!firstJson) jw.write(",\n")
                        firstJson = false
                        jw.write(row.toJson().toString())
                        cw.write("${row.id},${row.sessionId},${row.elapsedRealtimeNanos},")
                        cw.write("${row.wallClockUtcEpochMs},${row.sensorType},")
                        cw.write("${row.x},${row.y},${row.z},${row.w ?: ""},${row.accuracy ?: ""},")
                        cw.write(
                            "${row.roadEligibilityDisposition.name}," +
                                "${row.eligibilityConfidence ?: ""}\n",
                        )
                        count++
                        keptThisPage++
                    }
                    offset += page.size
                    if (keptThisPage > 0) {
                        onKeptPage(keptThisPage)
                    }
                    yield()
                }
            }
            jw.write("\n  ]\n}\n")
        }
        return count
    }

    private fun writeDerivedWindowFeaturesList(
        dir: File,
        rows: List<DerivedWindowFeatureEntity>,
    ): Int {
        val csv = File(dir, "derived_window_features.csv")
        val json = File(dir, "derived_window_features.json")
        BufferedWriter(json.writer(), DEFAULT_BUFFER_SIZE).use { jw ->
            jw.write("{\n  \"windows\": [\n")
            BufferedWriter(csv.writer(), DEFAULT_BUFFER_SIZE).use { cw ->
                cw.write(
                    "id,sessionId,windowStartElapsedNanos,windowEndElapsedNanos," +
                        "windowLengthMetersApprox,methodVersion,isExperimental,roughnessProxyScore," +
                        "roadResponseScore,qualityFlags,roadEligibilityDisposition,eligibilityConfidence," +
                        "featureBundleJson,windowStartWallClockUtcEpochMs,windowEndWallClockUtcEpochMs," +
                        "midLatitude,midLongitude,meanSpeedMps,headingMeanDeg,predPrimaryLabel," +
                        "scoreCornering,scoreVerticalShock,scoreStableCruise,windowIndex\n",
                )
                rows.forEachIndexed { index, row ->
                    if (index > 0) jw.write(",\n")
                    jw.write(row.toExportJson().toString())
                    cw.write("${row.id},${row.sessionId},${row.windowStartElapsedNanos},")
                    cw.write("${row.windowEndElapsedNanos},${row.windowLengthMetersApprox ?: ""},")
                    cw.write("${row.methodVersion ?: ""},${row.isExperimental},")
                    cw.write("${row.roughnessProxyScore ?: ""},${row.roadResponseScore ?: ""},")
                    cw.write("${row.qualityFlags},${row.roadEligibilityDisposition.name},")
                    cw.write("${row.eligibilityConfidence ?: ""},${csvEscape(row.featureBundleJson)},")
                    cw.write("${row.windowStartWallClockUtcEpochMs ?: ""},")
                    cw.write("${row.windowEndWallClockUtcEpochMs ?: ""},")
                    cw.write("${row.midLatitude ?: ""},${row.midLongitude ?: ""},")
                    cw.write("${row.meanSpeedMps ?: ""},${row.headingMeanDeg ?: ""},")
                    cw.write("${row.predPrimaryLabel ?: ""},${row.scoreCornering ?: ""},")
                    cw.write("${row.scoreVerticalShock ?: ""},${row.scoreStableCruise ?: ""},")
                    cw.write("${row.windowIndex ?: ""}\n")
                }
            }
            jw.write("\n  ]\n}\n")
        }
        return rows.size
    }

    private fun writeAnomalyCandidatesList(
        dir: File,
        rows: List<AnomalyCandidateEntity>,
    ): Int {
        val csv = File(dir, "anomaly_candidates.csv")
        val json = File(dir, "anomaly_candidates.json")
        BufferedWriter(json.writer(), DEFAULT_BUFFER_SIZE).use { jw ->
            jw.write("{\n  \"anomalies\": [\n")
            BufferedWriter(csv.writer(), DEFAULT_BUFFER_SIZE).use { cw ->
                cw.write(
                    "id,sessionId,timeElapsedRealtimeNanos,anomalyType,score,confidence," +
                        "methodVersion,qualityFlags,detailsJson,wallClockUtcEpochMs,latitude," +
                        "longitude,speedMps,headingDeg,severityBucket,derivedWindowId\n",
                )
                rows.forEachIndexed { index, row ->
                    if (index > 0) jw.write(",\n")
                    jw.write(row.toExportJson().toString())
                    cw.write("${row.id},${row.sessionId},${row.timeElapsedRealtimeNanos ?: ""},")
                    cw.write("${row.anomalyType.name},${row.score ?: ""},${row.confidence ?: ""},")
                    cw.write("${row.methodVersion ?: ""},${row.qualityFlags},")
                    cw.write("${csvEscape(row.detailsJson)},${row.wallClockUtcEpochMs ?: ""},")
                    cw.write("${row.latitude ?: ""},${row.longitude ?: ""},")
                    cw.write("${row.speedMps ?: ""},${row.headingDeg ?: ""},")
                    cw.write("${row.severityBucket ?: ""},${row.derivedWindowId ?: ""}\n")
                }
            }
            jw.write("\n  ]\n}\n")
        }
        return rows.size
    }

    private suspend fun writeDerivedWindowFeaturesCsvAndJson(
        dir: File,
        sessionId: Long,
    ): Int {
        val rows = database.derivedWindowFeatureDao().listForSessionOrdered(sessionId)
        val csv = File(dir, "derived_window_features.csv")
        val json = File(dir, "derived_window_features.json")
        BufferedWriter(json.writer(), DEFAULT_BUFFER_SIZE).use { jw ->
            jw.write("{\n  \"windows\": [\n")
            BufferedWriter(csv.writer(), DEFAULT_BUFFER_SIZE).use { cw ->
                cw.write(
                    "id,sessionId,windowStartElapsedNanos,windowEndElapsedNanos," +
                        "windowLengthMetersApprox,methodVersion,isExperimental,roughnessProxyScore," +
                        "roadResponseScore,qualityFlags,roadEligibilityDisposition,eligibilityConfidence," +
                        "featureBundleJson,windowStartWallClockUtcEpochMs,windowEndWallClockUtcEpochMs," +
                        "midLatitude,midLongitude,meanSpeedMps,headingMeanDeg,predPrimaryLabel," +
                        "scoreCornering,scoreVerticalShock,scoreStableCruise,windowIndex\n",
                )
                rows.forEachIndexed { index, row ->
                    if (index > 0) jw.write(",\n")
                    jw.write(row.toExportJson().toString())
                    cw.write("${row.id},${row.sessionId},${row.windowStartElapsedNanos},")
                    cw.write("${row.windowEndElapsedNanos},${row.windowLengthMetersApprox ?: ""},")
                    cw.write("${row.methodVersion ?: ""},${row.isExperimental},")
                    cw.write("${row.roughnessProxyScore ?: ""},${row.roadResponseScore ?: ""},")
                    cw.write("${row.qualityFlags},${row.roadEligibilityDisposition.name},")
                    cw.write("${row.eligibilityConfidence ?: ""},${csvEscape(row.featureBundleJson)},")
                    cw.write("${row.windowStartWallClockUtcEpochMs ?: ""},")
                    cw.write("${row.windowEndWallClockUtcEpochMs ?: ""},")
                    cw.write("${row.midLatitude ?: ""},${row.midLongitude ?: ""},")
                    cw.write("${row.meanSpeedMps ?: ""},${row.headingMeanDeg ?: ""},")
                    cw.write("${row.predPrimaryLabel ?: ""},${row.scoreCornering ?: ""},")
                    cw.write("${row.scoreVerticalShock ?: ""},${row.scoreStableCruise ?: ""},")
                    cw.write("${row.windowIndex ?: ""}\n")
                }
            }
            jw.write("\n  ]\n}\n")
        }
        return rows.size
    }

    private suspend fun writeAnomalyCandidatesCsvAndJson(
        dir: File,
        sessionId: Long,
    ): Int {
        val rows = database.anomalyCandidateDao().listForSessionOrdered(sessionId)
        val csv = File(dir, "anomaly_candidates.csv")
        val json = File(dir, "anomaly_candidates.json")
        BufferedWriter(json.writer(), DEFAULT_BUFFER_SIZE).use { jw ->
            jw.write("{\n  \"anomalies\": [\n")
            BufferedWriter(csv.writer(), DEFAULT_BUFFER_SIZE).use { cw ->
                cw.write(
                    "id,sessionId,timeElapsedRealtimeNanos,anomalyType,score,confidence," +
                        "methodVersion,qualityFlags,detailsJson,wallClockUtcEpochMs,latitude," +
                        "longitude,speedMps,headingDeg,severityBucket,derivedWindowId\n",
                )
                rows.forEachIndexed { index, row ->
                    if (index > 0) jw.write(",\n")
                    jw.write(row.toExportJson().toString())
                    cw.write("${row.id},${row.sessionId},${row.timeElapsedRealtimeNanos ?: ""},")
                    cw.write("${row.anomalyType.name},${row.score ?: ""},${row.confidence ?: ""},")
                    cw.write("${row.methodVersion ?: ""},${row.qualityFlags},")
                    cw.write("${csvEscape(row.detailsJson)},${row.wallClockUtcEpochMs ?: ""},")
                    cw.write("${row.latitude ?: ""},${row.longitude ?: ""},")
                    cw.write("${row.speedMps ?: ""},${row.headingDeg ?: ""},")
                    cw.write("${row.severityBucket ?: ""},${row.derivedWindowId ?: ""}\n")
                }
            }
            jw.write("\n  ]\n}\n")
        }
        return rows.size
    }

    /** Minimal CSV escape for optional JSON text fields (double quotes). */
    private fun csvEscape(value: String?): String {
        if (value == null) return ""
        if (value.contains(',') || value.contains('"') || value.contains('\n')) {
            return "\"" + value.replace("\"", "\"\"") + "\""
        }
        return value
    }

    private suspend fun writeSensorCsvAndJson(
        dir: File,
        sessionId: Long,
        onPageFinished: suspend (rowsInPage: Int) -> Unit = {},
    ) {
        val dao = database.sensorSampleDao()
        val csv = File(dir, "sensor_samples.csv")
        val json = File(dir, "sensor_samples.json")
        val pageSize = 2000
        var offset = 0
        var firstJson = true

        BufferedWriter(json.writer(), DEFAULT_BUFFER_SIZE).use { jw ->
            jw.write("{\n  \"samples\": [\n")
            BufferedWriter(csv.writer(), DEFAULT_BUFFER_SIZE).use { cw ->
                cw.write(
                    "id,sessionId,elapsedRealtimeNanos,wallClockUtcEpochMs," +
                        "sensorType,x,y,z,w,accuracy,roadEligibilityDisposition,eligibilityConfidence\n",
                )
                while (true) {
                    val page = dao.pageForSessionOrdered(sessionId, pageSize, offset)
                    if (page.isEmpty()) break
                    for (row in page) {
                        if (!firstJson) jw.write(",\n")
                        firstJson = false
                        jw.write(row.toJson().toString())
                        cw.write("${row.id},${row.sessionId},${row.elapsedRealtimeNanos},")
                        cw.write("${row.wallClockUtcEpochMs},${row.sensorType},")
                        cw.write("${row.x},${row.y},${row.z},${row.w ?: ""},${row.accuracy ?: ""},")
                        cw.write(
                            "${row.roadEligibilityDisposition.name}," +
                                "${row.eligibilityConfidence ?: ""}\n",
                        )
                    }
                    offset += page.size
                    onPageFinished(page.size)
                    yield()
                }
            }
            jw.write("\n  ]\n}\n")
        }
    }

    private suspend fun zipDirectory(
        sourceDir: File,
        zipFile: File,
    ) {
        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            val base = sourceDir.absolutePath.length + 1
            val files = sourceDir.walkTopDown().filter { it.isFile }.toList()
            for (file in files) {
                val entryName = file.absolutePath.substring(base).replace('\\', '/')
                zos.putNextEntry(ZipEntry(entryName))
                file.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
                yield()
            }
        }
    }
}
