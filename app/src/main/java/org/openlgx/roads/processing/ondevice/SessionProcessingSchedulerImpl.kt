package org.openlgx.roads.processing.ondevice

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.openlgx.roads.data.local.db.RoadsDatabase
import org.openlgx.roads.data.local.db.model.SessionProcessingState
import org.openlgx.roads.data.local.settings.AppSettingsRepository
import org.openlgx.roads.debug.AgentDebugLog
import org.openlgx.roads.di.ApplicationScope
import timber.log.Timber

@Singleton
class SessionProcessingSchedulerImpl
@Inject
constructor(
    @ApplicationScope private val applicationScope: CoroutineScope,
    private val database: RoadsDatabase,
    private val orchestrator: SessionProcessingOrchestrator,
    private val appSettingsRepository: AppSettingsRepository,
) : SessionProcessingScheduler {

    private val mutexes = java.util.concurrent.ConcurrentHashMap<Long, Mutex>()

    private fun mutexFor(sessionId: Long): Mutex = mutexes.getOrPut(sessionId) { Mutex() }

    override fun onSessionRecordingCompleted(sessionId: Long) {
        applicationScope.launch {
            mutexFor(sessionId).withLock {
                runCatching { processLocked(sessionId, fromAutoComplete = true) }
                    .onFailure { Timber.w(it, "schedule processing failed") }
            }
        }
    }

    override fun requestReprocess(sessionId: Long) {
        applicationScope.launch {
            mutexFor(sessionId).withLock {
                runCatching { processLocked(sessionId, fromAutoComplete = false) }
                    .onFailure { Timber.w(it, "reprocess failed") }
            }
        }
    }

    override fun requestBackfillPendingProcessing() {
        applicationScope.launch {
            val ids =
                runCatching { database.recordingSessionDao().listCompletedSessionIdsPendingProcessing() }
                    .getOrElse { emptyList() }
            // #region agent log
            AgentDebugLog.emit(
                hypothesisId = "H_BACKFILL",
                location = "SessionProcessingSchedulerImpl.kt:requestBackfillPendingProcessing",
                message = "backfill_ids_loaded",
                data =
                    mapOf(
                        "count" to ids.size,
                        "firstFew" to ids.take(5).joinToString(","),
                    ),
            )
            // #endregion
            if (ids.isEmpty()) return@launch
            Timber.i("Backfilling on-device processing for ${ids.size} session(s)")
            for (id in ids) {
                mutexFor(id).withLock {
                    runCatching { processLocked(id, fromAutoComplete = false) }
                        .onFailure { Timber.w(it, "backfill process failed for session $id") }
                }
                delay(50)
            }
        }
    }

    private suspend fun processLocked(
        sessionId: Long,
        fromAutoComplete: Boolean,
    ) {
        val row = database.recordingSessionDao().getById(sessionId) ?: return
        val settings = appSettingsRepository.settings.first()
        if (fromAutoComplete && !settings.processingLiveAfterSessionEnabled) return

        when (row.processingState) {
            SessionProcessingState.RUNNING -> return
            SessionProcessingState.QUEUED ->
                if (fromAutoComplete) {
                    return
                }
            else -> Unit
        }

        val now = System.currentTimeMillis()
        database.recordingSessionDao().updateProcessingFields(
            sessionId = sessionId,
            processingState = SessionProcessingState.RUNNING,
            processingStartedAtEpochMs = now,
            processingCompletedAtEpochMs = null,
            processingLastError = null,
            processingSummaryJson = null,
        )

        val result =
            orchestrator.processSession(
                sessionId = sessionId,
                windowSeconds = settings.processingWindowSeconds,
            )
        result.onFailure { e ->
            database.recordingSessionDao().updateProcessingFields(
                sessionId = sessionId,
                processingState = SessionProcessingState.FAILED,
                processingStartedAtEpochMs = now,
                processingCompletedAtEpochMs = System.currentTimeMillis(),
                processingLastError = e.message ?: e.toString(),
                processingSummaryJson = null,
            )
        }
    }
}
