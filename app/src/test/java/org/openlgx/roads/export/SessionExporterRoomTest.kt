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
import kotlinx.coroutines.runBlocking
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
            val exporter = SessionExporter(context, db, inspection, FakeExportDiag())
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
        }
}
