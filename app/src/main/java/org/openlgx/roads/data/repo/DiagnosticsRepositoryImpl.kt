package org.openlgx.roads.data.repo

import android.os.Build
import kotlinx.coroutines.flow.first
import org.openlgx.roads.BuildConfig
import org.openlgx.roads.collector.PassiveCollectionHandle
import org.openlgx.roads.data.local.db.RoadsDatabase
import org.openlgx.roads.data.local.settings.AppSettingsRepository
import org.openlgx.roads.permission.ActivityRecognitionPermissionChecker
import org.openlgx.roads.service.CollectorServiceStateRegistry
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DiagnosticsRepositoryImpl
@Inject
constructor(
    private val database: RoadsDatabase,
    private val appSettingsRepository: AppSettingsRepository,
    private val passiveCollection: PassiveCollectionHandle,
    private val activityRecognitionPermissionChecker: ActivityRecognitionPermissionChecker,
    private val collectorServiceStateRegistry: CollectorServiceStateRegistry,
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
                openRecordingSessionId = database.recordingSessionDao().findOpenSession()?.id,
            )

        return DiagnosticsSnapshot(
            appVersionName = BuildConfig.VERSION_NAME,
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
            lastUploadSummaryPlaceholder =
                if (latestUpload == null) {
                    "No successful upload timestamps recorded in DB (expected in Phase 1)"
                } else {
                    "Latest DB batch success time (epoch ms): $latestUpload"
                },
        )
    }
}
