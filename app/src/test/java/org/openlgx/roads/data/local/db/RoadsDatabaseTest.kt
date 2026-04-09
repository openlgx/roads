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
import org.openlgx.roads.data.local.db.entity.UploadBatchEntity
import org.openlgx.roads.data.local.db.model.BatchUploadState
import org.openlgx.roads.data.local.db.model.RecordingSource
import org.openlgx.roads.data.local.db.model.SessionState
import org.openlgx.roads.data.local.db.model.SessionUploadState
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RoadsDatabaseTest {

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
    fun countsSessionsAndPendingUploadBatches() =
        runBlocking {
            assertThat(db.recordingSessionDao().count()).isEqualTo(0)

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
            assertThat(sessionId).isGreaterThan(0L)
            assertThat(db.recordingSessionDao().count()).isEqualTo(1)

            db.uploadBatchDao().insert(
                UploadBatchEntity(
                    sessionId = sessionId,
                    createdAtEpochMs = 2L,
                    payloadManifestJson = "{}",
                    batchUploadState = BatchUploadState.READY,
                ),
            )

            assertThat(db.uploadBatchDao().count()).isEqualTo(1)
            assertThat(db.uploadBatchDao().countPendingOrRetryable()).isEqualTo(1)
        }
}
