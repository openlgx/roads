package org.openlgx.roads.data.repo.session

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.openlgx.roads.data.local.db.RoadsDatabase
import org.openlgx.roads.data.local.db.entity.LocationSampleEntity
import org.openlgx.roads.data.local.db.entity.RecordingSessionEntity
import org.openlgx.roads.data.local.db.entity.SensorSampleEntity
import org.openlgx.roads.data.local.db.model.RecordingSource
import org.openlgx.roads.data.local.db.model.RoadEligibilityDisposition
import org.openlgx.roads.data.local.db.model.SessionState
import org.openlgx.roads.data.local.db.model.SessionUploadState
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SessionInspectionRepositoryRoomTest {

    private lateinit var db: RoadsDatabase
    private lateinit var repo: SessionInspectionRepository

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room.inMemoryDatabaseBuilder(context, RoadsDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        repo = SessionInspectionRepositoryImpl(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `getSessionDetail aggregates counts and validation`() =
        runBlocking {
            val sessionId =
                db.recordingSessionDao().insert(
                    RecordingSessionEntity(
                        uuid = UUID.randomUUID().toString(),
                        startedAtEpochMs = 1000L,
                        endedAtEpochMs = 5000L,
                        state = SessionState.COMPLETED,
                        recordingSource = RecordingSource.AUTO,
                        sessionUploadState = SessionUploadState.NOT_QUEUED,
                        sensorCaptureSnapshotJson = """{"k":1}""",
                    ),
                )

            db.locationSampleDao().insertAll(
                listOf(
                    LocationSampleEntity(
                        sessionId = sessionId,
                        elapsedRealtimeNanos = 1L,
                        wallClockUtcEpochMs = 2000L,
                        latitude = -33.0,
                        longitude = 151.0,
                    ),
                ),
            )
            db.sensorSampleDao().insertAll(
                listOf(
                    SensorSampleEntity(
                        sessionId = sessionId,
                        elapsedRealtimeNanos = 1L,
                        wallClockUtcEpochMs = 2000L,
                        sensorType = android.hardware.Sensor.TYPE_ACCELEROMETER,
                        x = 0f,
                        y = 0f,
                        z = 9.8f,
                        accuracy = 3,
                    ),
                ),
            )

            val detail = repo.getSessionDetail(sessionId)!!
            assertThat(detail.locationSampleCount).isEqualTo(1L)
            assertThat(detail.sensorSampleCount).isEqualTo(1L)
            assertThat(detail.lastLocationWallClockMs).isEqualTo(2000L)
            assertThat(detail.lastSensorWallClockMs).isEqualTo(2000L)
            assertThat(detail.session.sensorCaptureSnapshotJson).contains("k")
            assertThat(detail.validation.hasLocationData).isTrue()
            assertThat(detail.validation.hasAccelerometerSamples).isTrue()
        }
}
