package org.openlgx.roads.data.local.db

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
import org.openlgx.roads.data.local.db.entity.RecordingSessionEntity
import org.openlgx.roads.data.local.db.entity.SensorSampleEntity
import org.openlgx.roads.data.local.db.model.RoadEligibilityDisposition
import org.openlgx.roads.data.local.db.model.RecordingSource
import org.openlgx.roads.data.local.db.model.SessionState
import org.openlgx.roads.data.local.db.model.SessionUploadState
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SensorSampleBatchingRoomTest {

    private lateinit var db: RoadsDatabase

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
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
    fun `insertAll batches and countForSession work`() =
        runBlocking {
            val sessionId =
                db.recordingSessionDao().insert(
                    RecordingSessionEntity(
                        uuid = UUID.randomUUID().toString(),
                        startedAtEpochMs = 1L,
                        state = SessionState.ACTIVE,
                        recordingSource = RecordingSource.AUTO,
                        sessionUploadState = SessionUploadState.NOT_QUEUED,
                    ),
                )

            val batch =
                List(25) { index ->
                    SensorSampleEntity(
                        sessionId = sessionId,
                        elapsedRealtimeNanos = index * 1_000_000L,
                        wallClockUtcEpochMs = 2000L + index,
                        sensorType = 1,
                        x = index.toFloat(),
                        y = 0f,
                        z = 0f,
                        w = null,
                        accuracy = 3,
                        roadEligibilityDisposition = RoadEligibilityDisposition.UNKNOWN,
                        eligibilityConfidence = null,
                    )
                }

            db.sensorSampleDao().insertAll(batch)

            assertThat(db.sensorSampleDao().countForSession(sessionId)).isEqualTo(25L)
            assertThat(db.sensorSampleDao().maxWallClockEpochMsForSession(sessionId)).isEqualTo(2024L)
        }
}
