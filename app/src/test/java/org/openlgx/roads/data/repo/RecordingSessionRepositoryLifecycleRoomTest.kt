package org.openlgx.roads.data.repo

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.openlgx.roads.data.local.db.RoadsDatabase
import org.openlgx.roads.data.local.db.model.RecordingSource
import org.openlgx.roads.data.local.db.model.SessionState
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RecordingSessionRepositoryLifecycleRoomTest {

    private lateinit var db: RoadsDatabase
    private lateinit var repo: RecordingSessionRepositoryImpl

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room.inMemoryDatabaseBuilder(context, RoadsDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        repo = RecordingSessionRepositoryImpl(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `begin and end recording session updates row`() =
        runBlocking {
            val id =
                repo.beginAutoRecordingSession(
                    AutoRecordingSessionStartParams(
                        armedAtEpochMs = 10L,
                        recordingStartedAtEpochMs = 20L,
                        recordingSource = RecordingSource.AUTO,
                        collectorStateSnapshotJson = "{}",
                        capturePassiveCollectionEnabled = true,
                        uploadPolicyWifiOnly = true,
                        uploadPolicyAllowCellular = false,
                        uploadPolicyOnlyWhileCharging = false,
                        uploadPolicyPauseOnLowBattery = true,
                        uploadPolicyLowBatteryThresholdPercent = 20,
                    ),
                )
            assertThat(id).isGreaterThan(0L)

            val openBefore = db.recordingSessionDao().findOpenSession()
            assertThat(openBefore?.state).isEqualTo(SessionState.ACTIVE)

            repo.endRecordingSession(id, endedAtEpochMs = 99L, endState = SessionState.COMPLETED)
            val openAfter = db.recordingSessionDao().findOpenSession()
            assertThat(openAfter).isNull()
        }
}
