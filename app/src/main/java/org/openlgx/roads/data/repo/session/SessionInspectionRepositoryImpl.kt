package org.openlgx.roads.data.repo.session

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import org.openlgx.roads.data.local.db.RoadsDatabase
import org.openlgx.roads.data.local.db.model.SessionListStats
import org.openlgx.roads.validation.SessionCaptureValidator

@Singleton
class SessionInspectionRepositoryImpl
@Inject
constructor(
    private val database: RoadsDatabase,
) : SessionInspectionRepository {

    override fun observeSessionList(): Flow<List<SessionListStats>> =
        database.recordingSessionDao().observeSessionListStats()

    override suspend fun getSessionDetail(sessionId: Long): SessionDetail? {
        val session = database.recordingSessionDao().getById(sessionId) ?: return null
        val locDao = database.locationSampleDao()
        val sensDao = database.sensorSampleDao()

        val locCount = locDao.countForSession(sessionId)
        val sensCount = sensDao.countForSession(sessionId)
        val lastLoc = locDao.latestForSession(sessionId)?.wallClockUtcEpochMs
        val lastSens = sensDao.maxWallClockEpochMsForSession(sessionId)

        val locClocks = locDao.wallClocksAscForSession(sessionId)
        val sensClocks = sensDao.wallClocksAscForSession(sessionId)
        val distinctTypes = sensDao.distinctSensorTypesForSession(sessionId)

        val validation =
            SessionCaptureValidator.summarize(
                session = session,
                locationSampleCount = locCount,
                sensorSampleCount = sensCount,
                locationWallClocksAsc = locClocks,
                sensorWallClocksAsc = sensClocks,
                distinctSensorTypes = distinctTypes,
            )

        val end = session.endedAtEpochMs ?: System.currentTimeMillis()
        val duration = (end - session.startedAtEpochMs).coerceAtLeast(0L)
        val latestBatch = database.uploadBatchDao().latestForSession(sessionId)

        return SessionDetail(
            session = session,
            locationSampleCount = locCount,
            sensorSampleCount = sensCount,
            lastLocationWallClockMs = lastLoc,
            lastSensorWallClockMs = lastSens,
            durationMs = duration,
            validation = validation,
            latestUploadBatch = latestBatch,
        )
    }
}
