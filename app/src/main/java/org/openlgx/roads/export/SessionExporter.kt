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
import org.json.JSONArray
import org.json.JSONObject
import org.openlgx.roads.BuildConfig
import org.openlgx.roads.data.local.db.RoadsDatabase
import org.openlgx.roads.data.local.settings.AppSettingsRepository
import org.openlgx.roads.data.local.db.entity.DeviceProfileEntity
import org.openlgx.roads.data.repo.session.SessionInspectionRepository
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

    suspend fun exportSession(sessionId: Long): Result<SessionExportResult> =
        withContext(Dispatchers.IO) {
            try {
                val detail =
                    sessionInspectionRepository.getSessionDetail(sessionId)
                        ?: return@withContext Result.failure(IllegalArgumentException("Session not found"))
                val session = detail.session

                val root = File(context.getExternalFilesDir(null), "olgx_exports").apply { mkdirs() }
                val folderName = "session_${session.uuid}_${System.currentTimeMillis()}"
                val dir = File(root, folderName).apply { mkdirs() }

                File(dir, "session.json").writeText(session.toExportJson().toString(2))

                writeLocationCsvAndJson(dir, sessionId)
                writeSensorCsvAndJson(dir, sessionId)

                val profile = database.deviceProfileDao().firstOrNull()
                val calibrationWorkflowEnabled =
                    appSettingsRepository.settings.first().calibrationWorkflowEnabled
                val manifest =
                    buildManifest(
                        exportDir = dir,
                        sessionId = sessionId,
                        sessionUuid = session.uuid,
                        locationCount = detail.locationSampleCount,
                        sensorCount = detail.sensorSampleCount,
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

    private fun buildManifest(
        exportDir: File,
        sessionId: Long,
        sessionUuid: String,
        locationCount: Long,
        sensorCount: Long,
        validation: SessionCaptureValidationSummary,
        deviceProfile: DeviceProfileEntity?,
        calibrationWorkflowEnabled: Boolean,
    ): JSONObject {
        val files =
            JSONArray().apply {
                put(fileEntry("session.json", "session_metadata"))
                put(fileEntry("manifest.json", "export_manifest"))
                put(fileEntry("location_samples.csv", "location_csv"))
                put(fileEntry("location_samples.json", "location_json"))
                put(fileEntry("sensor_samples.csv", "sensor_csv"))
                put(fileEntry("sensor_samples.json", "sensor_json"))
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

        return JSONObject().apply {
            put("exportSchemaVersion", ExportConstants.EXPORT_SCHEMA_VERSION)
            put("exportMethodVersion", ExportConstants.EXPORT_METHOD_VERSION)
            put("roughnessMethodVersion", ExportConstants.ROUGHNESS_METHOD_VERSION_PLACEHOLDER)
            put("disclaimer", ExportConstants.DISCLAIMER)
            put("appVersionName", BuildConfig.VERSION_NAME)
            put("exportedAtEpochMs", System.currentTimeMillis())
            put("sessionId", sessionId)
            put("sessionUuid", sessionUuid)
            put("exportDirectoryName", exportDir.name)
            put("locationSampleCount", locationCount)
            put("sensorSampleCount", sensorCount)
            put("files", files)
            put("device", device)
            put("validationSummary", validationJson)
            put("calibrationWorkflowEnabled", calibrationWorkflowEnabled)
            put("calibrationLiteraturePointer", ExportConstants.CALIBRATION_LITERATURE_POINTER)
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
                }
            }
            jw.write("\n  ]\n}\n")
        }
    }

    private suspend fun writeSensorCsvAndJson(
        dir: File,
        sessionId: Long,
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
                }
            }
            jw.write("\n  ]\n}\n")
        }
    }

    private fun zipDirectory(
        sourceDir: File,
        zipFile: File,
    ) {
        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            val base = sourceDir.absolutePath.length + 1
            sourceDir.walkTopDown().filter { it.isFile }.forEach { file ->
                val entryName = file.absolutePath.substring(base).replace('\\', '/')
                zos.putNextEntry(ZipEntry(entryName))
                file.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
    }
}
