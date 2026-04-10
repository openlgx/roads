package org.openlgx.roads.export

import android.content.Context
import android.hardware.Sensor
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.openlgx.roads.data.local.settings.AppSettings
import org.openlgx.roads.data.local.settings.AppSettingsRepository
import org.openlgx.roads.data.local.settings.CaptureSettingsPreset
import org.openlgx.roads.data.local.settings.UploadRoadFilterUnknownPolicy
import org.openlgx.roads.data.local.settings.testAppSettings
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.openlgx.roads.data.local.db.RoadsDatabase
import org.openlgx.roads.data.local.db.entity.LocationSampleEntity
import org.openlgx.roads.data.local.db.entity.RecordingSessionEntity
import org.openlgx.roads.data.local.db.entity.SensorSampleEntity
import org.openlgx.roads.data.local.db.model.RecordingSource
import org.openlgx.roads.data.local.db.model.SessionState
import org.openlgx.roads.data.local.db.model.SessionUploadState
import org.openlgx.roads.data.repo.session.SessionInspectionRepositoryImpl
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SessionExporterRoomTest {

    private lateinit var db: RoadsDatabase
    private lateinit var context: Context

    private class FakeExportDiag : ExportDiagnosticsRepository {
        override val state =
            MutableStateFlow(
                ExportDiagnosticsState(
                    lastExportDirectoryPath = null,
                    lastExportZipPath = null,
                    lastExportEpochMs = null,
                    lastExportSuccess = false,
                    lastExportError = null,
                    lastExportedSessionId = null,
                ),
            ).asStateFlow()

        override suspend fun recordSuccess(
            directoryAbsolutePath: String,
            zipAbsolutePath: String?,
            sessionId: Long,
        ) = Unit

        override suspend fun recordFailure(message: String) = Unit
    }

    private class FakeAppSettingsRepo(
        initial: AppSettings,
    ) : AppSettingsRepository {
        private val mut = MutableStateFlow(initial)
        override val settings = mut.asStateFlow()

        override suspend fun setOnboardingCompleted(completed: Boolean) = Unit

        override suspend fun completeOnboarding() = Unit

        override suspend fun setPassiveCollectionEnabled(enabled: Boolean) = Unit

        override suspend fun setUploadWifiOnly(enabled: Boolean) = Unit

        override suspend fun setUploadAllowCellular(enabled: Boolean) = Unit

        override suspend fun setUploadOnlyWhileCharging(enabled: Boolean) = Unit

        override suspend fun setUploadPauseOnLowBatteryEnabled(enabled: Boolean) = Unit

        override suspend fun setUploadLowBatteryThresholdPercent(percent: Int) = Unit

        override suspend fun setRetentionDays(days: Int) = Unit

        override suspend fun setMaxLocalStorageMb(mb: Int) = Unit

        override suspend fun setLocalCompactionEnabled(enabled: Boolean) = Unit

        override suspend fun setCaptureMinSpeedMps(mps: Float) = Unit

        override suspend fun setCaptureStartSpeedMps(mps: Float) = Unit

        override suspend fun setCaptureImmediateStartSpeedMps(mps: Float) = Unit

        override suspend fun setCaptureStopSpeedMps(mps: Float) = Unit

        override suspend fun setCaptureStopHoldSeconds(seconds: Int) = Unit

        override suspend fun setCaptureStationaryRadiusMeters(meters: Float) = Unit

        override suspend fun setCaptureFastArmingEnabled(enabled: Boolean) = Unit

        override suspend fun setProcessingWindowSeconds(seconds: Float) = Unit

        override suspend fun setProcessingDistanceBinMeters(meters: Float) = Unit

        override suspend fun setProcessingLiveAfterSessionEnabled(enabled: Boolean) = Unit

        override suspend fun setProcessingAllRunsOverlayEnabled(enabled: Boolean) = Unit

        override suspend fun applyCapturePreset(preset: CaptureSettingsPreset) = Unit

        override suspend fun setDebugModeEnabled(enabled: Boolean) = Unit

        override suspend fun setCalibrationWorkflowEnabled(enabled: Boolean) = Unit

        override suspend fun recordRecordingStartedAt(epochMs: Long) = Unit

        override suspend fun recordRecordingStoppedAt(epochMs: Long) = Unit

        override suspend fun setUploadEnabled(enabled: Boolean) = Unit

        override suspend fun setUploadBaseUrl(url: String) = Unit

        override suspend fun setUploadApiKey(key: String) = Unit

        override suspend fun setUploadRetryLimit(limit: Int) = Unit

        override suspend fun setUploadAutoAfterSessionEnabled(enabled: Boolean) = Unit

        override suspend fun setUploadRoadFilterEnabled(enabled: Boolean) = Unit

        override suspend fun setUploadRoadFilterDistanceMeters(meters: Float) = Unit

        override suspend fun setUploadRoadFilterUnknownPolicy(policy: UploadRoadFilterUnknownPolicy) = Unit

        override suspend fun setUploadRoadPackRequiredForAutoUpload(required: Boolean) = Unit

        override suspend fun setUploadCouncilSlug(slug: String) = Unit

        override suspend fun setUploadProjectSlug(slug: String) = Unit

        override suspend fun setUploadProjectId(id: String) = Unit

        override suspend fun setUploadDeviceId(id: String) = Unit

        override suspend fun setUploadChargingPreferred(preferred: Boolean) = Unit

        override suspend fun markHostedUploadAttempt(sessionId: Long, timestampMs: Long) = Unit

        override suspend fun markHostedUploadSuccess(sessionId: Long, timestampMs: Long) = Unit

        override suspend fun markHostedUploadFailure(sessionId: Long, message: String, timestampMs: Long) =
            Unit

        override suspend fun applyPilotBootstrapIfNeverApplied(
            label: String,
            baseUrl: String,
            councilSlug: String,
            projectSlug: String,
            projectId: String,
            deviceId: String,
            uploadApiKey: String,
        ): Boolean = false
    }

    private fun defaultSettings(calibrationWorkflow: Boolean = false): AppSettings =
        testAppSettings().copy(calibrationWorkflowEnabled = calibrationWorkflow)

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db =
            Room.inMemoryDatabaseBuilder(context, RoadsDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `export produces manifest session csv json and zip`() =
        runBlocking {
            val sessionId =
                db.recordingSessionDao().insert(
                    RecordingSessionEntity(
                        uuid = UUID.randomUUID().toString(),
                        startedAtEpochMs = 1L,
                        endedAtEpochMs = 10L,
                        state = SessionState.COMPLETED,
                        recordingSource = RecordingSource.AUTO,
                        collectorStateSnapshotJson = """{"a":1}""",
                        sessionUploadState = SessionUploadState.NOT_QUEUED,
                    ),
                )
            db.locationSampleDao().insertAll(
                listOf(
                    LocationSampleEntity(
                        sessionId = sessionId,
                        elapsedRealtimeNanos = 1L,
                        wallClockUtcEpochMs = 2L,
                        latitude = 1.0,
                        longitude = 2.0,
                    ),
                ),
            )
            db.sensorSampleDao().insertAll(
                listOf(
                    SensorSampleEntity(
                        sessionId = sessionId,
                        elapsedRealtimeNanos = 1L,
                        wallClockUtcEpochMs = 2L,
                        sensorType = Sensor.TYPE_GYROSCOPE,
                        x = 0.1f,
                        y = 0f,
                        z = 0f,
                    ),
                ),
            )

            val inspection = SessionInspectionRepositoryImpl(db)
            val settingsRepo = FakeAppSettingsRepo(defaultSettings(calibrationWorkflow = true))
            val exporter =
                SessionExporter(context, db, inspection, FakeExportDiag(), settingsRepo)
            val result = exporter.exportSession(sessionId)

            assertThat(result.isSuccess).isTrue()
            val bundle = result.getOrThrow()
            assertThat(File(bundle.directory, "session.json").exists()).isTrue()
            assertThat(File(bundle.directory, "manifest.json").exists()).isTrue()
            assertThat(File(bundle.directory, "location_samples.csv").exists()).isTrue()
            assertThat(File(bundle.directory, "location_samples.json").exists()).isTrue()
            assertThat(File(bundle.directory, "sensor_samples.csv").exists()).isTrue()
            assertThat(File(bundle.directory, "sensor_samples.json").exists()).isTrue()
            assertThat(File(bundle.directory, "derived_window_features.csv").exists()).isTrue()
            assertThat(File(bundle.directory, "derived_window_features.json").exists()).isTrue()
            assertThat(File(bundle.directory, "anomaly_candidates.csv").exists()).isTrue()
            assertThat(File(bundle.directory, "anomaly_candidates.json").exists()).isTrue()
            assertThat(bundle.zipFile.exists()).isTrue()

            val manifest = JSONObject(File(bundle.directory, "manifest.json").readText())
            assertThat(manifest.getInt("exportSchemaVersion")).isEqualTo(ExportConstants.EXPORT_SCHEMA_VERSION)
            assertThat(manifest.getString("exportMethodVersion")).isEqualTo(ExportConstants.EXPORT_METHOD_VERSION)
            assertThat(manifest.has("processing")).isTrue()
            assertThat(manifest.getInt("derivedWindowFeatureCount")).isEqualTo(0)
            assertThat(manifest.getInt("anomalyCandidateCount")).isEqualTo(0)
            assertThat(manifest.has("validationSummary")).isTrue()
            assertThat(manifest.getLong("sessionId")).isEqualTo(sessionId)
            assertThat(manifest.getBoolean("calibrationWorkflowEnabled")).isTrue()
            assertThat(manifest.getString("calibrationLiteraturePointer"))
                .isEqualTo(ExportConstants.CALIBRATION_LITERATURE_POINTER)
            assertThat(settingsRepo.settings.first().calibrationWorkflowEnabled).isTrue()
        }
}
