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

        override suspend fun setDebugModeEnabled(enabled: Boolean) = Unit

        override suspend fun setCalibrationWorkflowEnabled(enabled: Boolean) = Unit
    }

    private fun defaultSettings(calibrationWorkflow: Boolean = false): AppSettings =
        AppSettings(
            onboardingCompleted = true,
            passiveCollectionUserEnabled = true,
            passiveCollectionEffective = true,
            uploadWifiOnly = true,
            uploadAllowCellular = false,
            uploadOnlyWhileCharging = false,
            uploadPauseOnLowBatteryEnabled = true,
            uploadLowBatteryThresholdPercent = 20,
            retentionDays = 30,
            maxLocalStorageMb = 0,
            localCompactionEnabled = false,
            captureMinSpeedMps = 4.5f,
            debugModeEnabled = false,
            calibrationWorkflowEnabled = calibrationWorkflow,
        )

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
            assertThat(bundle.zipFile.exists()).isTrue()

            val manifest = JSONObject(File(bundle.directory, "manifest.json").readText())
            assertThat(manifest.getInt("exportSchemaVersion")).isEqualTo(ExportConstants.EXPORT_SCHEMA_VERSION)
            assertThat(manifest.getString("exportMethodVersion")).isEqualTo(ExportConstants.EXPORT_METHOD_VERSION)
            assertThat(manifest.has("validationSummary")).isTrue()
            assertThat(manifest.getLong("sessionId")).isEqualTo(sessionId)
            assertThat(manifest.getBoolean("calibrationWorkflowEnabled")).isTrue()
            assertThat(manifest.getString("calibrationLiteraturePointer"))
                .isEqualTo(ExportConstants.CALIBRATION_LITERATURE_POINTER)
            assertThat(settingsRepo.settings.first().calibrationWorkflowEnabled).isTrue()
        }
}
