package org.openlgx.roads.data.repo

import android.content.Context
import android.os.Build
import java.io.File
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import org.openlgx.roads.BuildConfig
import org.openlgx.roads.collector.PassiveCollectionHandle
import org.openlgx.roads.data.local.db.RoadsDatabase
import org.openlgx.roads.data.local.settings.AppSettingsRepository
import org.openlgx.roads.location.LocationRecordingController
import org.openlgx.roads.permission.ActivityRecognitionPermissionChecker
import org.openlgx.roads.sensor.SensorGateway
import org.openlgx.roads.sensor.SensorRecordingController
import org.openlgx.roads.permission.FineLocationPermissionChecker
import org.openlgx.roads.service.CollectorServiceStateRegistry
import org.openlgx.roads.export.ExportDiagnosticsRepository
import org.openlgx.roads.roadpack.RoadPackManager
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DiagnosticsRepositoryImpl
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val database: RoadsDatabase,
    private val appSettingsRepository: AppSettingsRepository,
    private val passiveCollection: PassiveCollectionHandle,
    private val activityRecognitionPermissionChecker: ActivityRecognitionPermissionChecker,
    private val fineLocationPermissionChecker: FineLocationPermissionChecker,
    private val locationRecordingController: LocationRecordingController,
    private val sensorGateway: SensorGateway,
    private val sensorRecordingController: SensorRecordingController,
    private val collectorServiceStateRegistry: CollectorServiceStateRegistry,
    private val exportDiagnosticsRepository: ExportDiagnosticsRepository,
    private val roadPackManager: RoadPackManager,
) : DiagnosticsRepository {

    override suspend fun loadSnapshot(): DiagnosticsSnapshot {
        val settings = appSettingsRepository.settings.first()

        val deviceProfiles = database.deviceProfileDao().count()
        val recordingSessions = database.recordingSessionDao().count()
        val locationSamples = database.locationSampleDao().count()
        val sensorSamples = database.sensorSampleDao().count()
        val uploadBatches = database.uploadBatchDao().count()
        val derived = database.derivedWindowFeatureDao().count()
        val anomalies = database.anomalyCandidateDao().count()
        val consensus = database.segmentConsensusRecordDao().count()
        val calibration = database.calibrationRunDao().count()
        val pending = database.uploadBatchDao().countPendingOrRetryable()
        val latestUpload = database.uploadBatchDao().latestSuccessfulUploadAt()

        val collectorUi = passiveCollection.uiState.value
        val locationUi = locationRecordingController.uiState.value
        val sensorHardware = sensorGateway.queryAvailability()
        val sensorUi = sensorRecordingController.uiState.value
        val openSessionId = database.recordingSessionDao().findOpenSession()?.id
        val locationSamplesForOpen =
            if (openSessionId != null) {
                database.locationSampleDao().countForSession(openSessionId)
            } else {
                0L
            }
        val sensorSamplesForOpen =
            if (openSessionId != null) {
                database.sensorSampleDao().countForSession(openSessionId)
            } else {
                0L
            }
        val sensorCountLive =
            if (openSessionId != null && sensorUi.activeSessionId == openSessionId) {
                maxOf(sensorSamplesForOpen, sensorUi.publishedSampleCount)
            } else {
                sensorSamplesForOpen
            }

        val collectorRuntime =
            CollectorRuntimeDiagnostics(
                lifecycleState = collectorUi.lifecycleState.name,
                activityRecognitionSupported = collectorUi.activityRecognitionSupported,
                activityRecognitionPermissionGranted =
                    activityRecognitionPermissionChecker.isGranted(),
                activityRecognitionUpdatesActive = collectorUi.activityRecognitionUpdatesActive,
                likelyInVehicle = collectorUi.likelyInVehicle,
                foregroundServiceRunning =
                    collectorServiceStateRegistry.foregroundRunning.value,
                openRecordingSessionId = openSessionId,
                fineLocationPermissionGranted = fineLocationPermissionChecker.isGranted(),
                activeSessionLocationSamples = locationSamplesForOpen,
                lastLocationWallClockEpochMs = locationUi.lastWallClockEpochMs,
                lastLocationSpeedMps = locationUi.lastSpeedMps,
            )

        val sensorRuntime =
            SensorRuntimeDiagnostics(
                hardwareAvailable = sensorHardware,
                captureActive = sensorUi.recordingSensors,
                activeSessionId = sensorUi.activeSessionId,
                activeSessionSampleCount = sensorCountLive,
                bufferedSampleCount = sensorUi.bufferedSampleCount,
                lastSensorWallClockEpochMs = sensorUi.lastWallClockEpochMs,
                estimatedCallbacksPerSecond = sensorUi.estimatedCallbacksPerSecond,
                degradedRecording = sensorUi.degradedRecording,
                degradedReason = sensorUi.degradedReason,
            )

        val exportState = exportDiagnosticsRepository.state.first()
        val exportRoot =
            File(context.getExternalFilesDir(null) ?: context.filesDir, "olgx_exports")
                .absolutePath

        val roadPackHint = roadPackManager.expectedPublicRoadsPathHint(settings.uploadCouncilSlug)
        val pilotBootstrapSummary =
            if (settings.pilotBootstrapApplied) {
                "Yes — label: ${settings.pilotBootstrapLabel ?: "—"} (one-shot seed; overrides respected)"
            } else {
                "No (manual pilot fields or non-bootstrap build)"
            }
        val hostedUploadReadinessLines =
            buildList {
                val slug = settings.uploadCouncilSlug.trim()
                if (slug.isNotEmpty() && !roadPackManager.hasPackForCouncil(slug)) {
                    add(
                        "Road pack not installed for council \"$slug\". Expected GeoJSON path (example): $roadPackHint",
                    )
                }
                if (!settings.uploadEnabled) {
                    add("Hosted upload is disabled — recording, processing, and export still work on device.")
                } else {
                    if (settings.uploadApiKey.isBlank()) {
                        add(
                            "Upload is enabled but no API key is stored — add DEVICE_UPLOAD key in Settings or inject for debug (see README).",
                        )
                    }
                    if (settings.uploadBaseUrl.isBlank()) {
                        add("Upload base URL is blank — hosted pipeline cannot run.")
                    }
                    if (settings.uploadProjectId.isBlank() || settings.uploadDeviceId.isBlank()) {
                        add("Project id and/or device id is blank — uploads-create request will be incomplete.")
                    }
                }
                if (isEmpty()) {
                    add("No hosted upload / road-pack blockers detected from current settings (queue still follows network and charging rules).")
                }
            }

        val dataQualityWarnings =
            buildList {
                if (openSessionId != null && locationSamplesForOpen == 0L) {
                    add("Open recording session has no location samples yet.")
                }
                if (openSessionId != null && sensorSamplesForOpen == 0L) {
                    add("Open recording session has no sensor samples yet.")
                }
                if (recordingSessions > 0 && locationSamples == 0L) {
                    add("There are sessions but no location rows in the database.")
                }
                if (recordingSessions > 0 && sensorSamples == 0L) {
                    add("There are sessions but no sensor rows in the database.")
                }
            }

        val exportDiag =
            ExportDiagnosticsSnapshot(
                lastExportDirectoryPath = exportState.lastExportDirectoryPath,
                lastExportZipPath = exportState.lastExportZipPath,
                lastExportEpochMs = exportState.lastExportEpochMs,
                lastExportSuccess = exportState.lastExportSuccess,
                lastExportError = exportState.lastExportError,
                lastExportedSessionId = exportState.lastExportedSessionId,
            )

        return DiagnosticsSnapshot(
            appVersionName = BuildConfig.VERSION_NAME,
            pilotBootstrapSummary = pilotBootstrapSummary,
            roadPackExpectedPath = roadPackHint,
            hostedUploadReadinessLines = hostedUploadReadinessLines,
            deviceModel = Build.MODEL,
            sdkInt = Build.VERSION.SDK_INT,
            manufacturer = Build.MANUFACTURER,
            settings = settings,
            tableCounts =
                TableCounts(
                    deviceProfiles = deviceProfiles,
                    recordingSessions = recordingSessions,
                    locationSamples = locationSamples,
                    sensorSamples = sensorSamples,
                    uploadBatches = uploadBatches,
                    derivedWindowFeatures = derived,
                    anomalyCandidates = anomalies,
                    segmentConsensusRecords = consensus,
                    calibrationRuns = calibration,
                ),
            pendingUploadBatches = pending,
            latestDbUploadSuccessEpochMs = latestUpload,
            collectorRuntime = collectorRuntime,
            sensorRuntime = sensorRuntime,
            exportRootDirectory = exportRoot,
            exportDiagnostics = exportDiag,
            dataQualityWarnings = dataQualityWarnings,
            lastUploadSummaryPlaceholder =
                if (latestUpload == null) {
                    "No successful upload timestamps recorded in DB (expected in Phase 1)"
                } else {
                    "Latest DB batch success time (epoch ms): $latestUpload"
                },
        )
    }
}
