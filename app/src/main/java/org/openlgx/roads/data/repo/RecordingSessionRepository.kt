package org.openlgx.roads.data.repo

import kotlinx.coroutines.flow.Flow
import org.openlgx.roads.data.local.db.model.RecordingSource
import org.openlgx.roads.data.local.db.model.SessionState

data class AutoRecordingSessionStartParams(
    val armedAtEpochMs: Long,
    val recordingStartedAtEpochMs: Long,
    val recordingSource: RecordingSource = RecordingSource.AUTO,
    val collectorStateSnapshotJson: String,
    val capturePassiveCollectionEnabled: Boolean,
    val uploadPolicyWifiOnly: Boolean,
    val uploadPolicyAllowCellular: Boolean,
    val uploadPolicyOnlyWhileCharging: Boolean,
    val uploadPolicyPauseOnLowBattery: Boolean,
    val uploadPolicyLowBatteryThresholdPercent: Int,
)

interface RecordingSessionRepository {
    suspend fun beginAutoRecordingSession(params: AutoRecordingSessionStartParams): Long

    suspend fun updateSensorCaptureSnapshot(sessionId: Long, json: String?)

    suspend fun endRecordingSession(sessionId: Long, endedAtEpochMs: Long, endState: SessionState)

    suspend fun countSessions(): Long

    suspend fun findOpenSessionId(): Long?

    fun observeRecordingSessionCount(): Flow<Long>
}
