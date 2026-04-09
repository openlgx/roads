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
import org.openlgx.roads.data.local.db.entity.LocationSampleEntity
import org.openlgx.roads.data.local.db.entity.RecordingSessionEntity
import org.openlgx.roads.data.local.db.model.RecordingSource
import org.openlgx.roads.data.local.db.model.SessionState
import org.openlgx.roads.data.local.db.model.SessionUploadState
import org.openlgx.roads.data.local.db.model.RoadEligibilityDisposition
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LocationSampleBatchingRoomTest {

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
    fun `insertAll persists batches for a session`() =
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
                List(20) { index ->
                    LocationSampleEntity(
                        sessionId = sessionId,
                        elapsedRealtimeNanos = index.toLong(),
                        wallClockUtcEpochMs = 1000L + index,
                        latitude = -33.0 + index * 0.0001,
                        longitude = 151.0,
                        speedMps = 5f,
                        bearingDegrees = 90f,
                        horizontalAccuracyMeters = 4f,
                        altitudeMeters = 10.0,
                        roadEligibilityDisposition = RoadEligibilityDisposition.UNKNOWN,
                        eligibilityConfidence = null,
                        retainedRegardlessOfEligibility = true,
                    )
                }

            db.locationSampleDao().insertAll(batch)

            assertThat(db.locationSampleDao().countForSession(sessionId)).isEqualTo(20L)
            val latest = db.locationSampleDao().latestForSession(sessionId)
            assertThat(latest?.wallClockUtcEpochMs).isEqualTo(1019L)
        }
}
