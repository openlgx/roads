package org.openlgx.roads.data.repo

import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import org.openlgx.roads.data.local.db.RoadsDatabase
import org.openlgx.roads.data.local.db.entity.RecordingSessionEntity
import org.openlgx.roads.data.local.db.model.RecordingSource
import org.openlgx.roads.data.local.db.model.SessionState
import org.openlgx.roads.data.local.db.model.SessionUploadState

@Singleton
class RecordingSessionRepositoryImpl
@Inject
constructor(
    private val database: RoadsDatabase,
) : RecordingSessionRepository {

    override suspend fun beginAutoRecordingSession(
        params: AutoRecordingSessionStartParams,
    ): Long {
        val entity =
            RecordingSessionEntity(
                uuid = UUID.randomUUID().toString(),
                armedAtEpochMs = params.armedAtEpochMs,
                startedAtEpochMs = params.recordingStartedAtEpochMs,
                state = SessionState.ACTIVE,
                recordingSource = params.recordingSource,
                qualityFlags = 0,
                sessionUploadState = SessionUploadState.NOT_QUEUED,
                collectorStateSnapshotJson = params.collectorStateSnapshotJson,
                capturePassiveCollectionEnabled = params.capturePassiveCollectionEnabled,
                uploadPolicyWifiOnly = params.uploadPolicyWifiOnly,
                uploadPolicyAllowCellular = params.uploadPolicyAllowCellular,
                uploadPolicyOnlyWhileCharging = params.uploadPolicyOnlyWhileCharging,
                uploadPolicyPauseOnLowBattery = params.uploadPolicyPauseOnLowBattery,
                uploadPolicyLowBatteryThresholdPercent = params.uploadPolicyLowBatteryThresholdPercent,
            )
        return database.recordingSessionDao().insert(entity)
    }

    override suspend fun endRecordingSession(
        sessionId: Long,
        endedAtEpochMs: Long,
        endState: SessionState,
    ) {
        database.recordingSessionDao().endSession(sessionId, endedAtEpochMs, endState)
    }

    override suspend fun countSessions(): Long = database.recordingSessionDao().count()

    override suspend fun findOpenSessionId(): Long? =
        database.recordingSessionDao().findOpenSession()?.id
}
