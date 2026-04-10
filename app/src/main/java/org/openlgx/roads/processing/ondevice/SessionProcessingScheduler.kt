package org.openlgx.roads.processing.ondevice

/**
 * Queue / run on-device session processing after capture completes or on manual reprocess.
 */
interface SessionProcessingScheduler {
    /**
     * Called when a session is finalised with [org.openlgx.roads.data.local.db.model.SessionState.COMPLETED]
     * and settings allow auto-processing.
     */
    fun onSessionRecordingCompleted(sessionId: Long)

    /**
     * User-requested reprocess; replaces QUEUED work for this session but does not interrupt RUNNING.
     */
    fun requestReprocess(sessionId: Long)

    /**
     * Queue on-device processing for completed sessions that never ran (e.g. before auto-schedule existed).
     * Safe to call more than once; [SessionProcessingSchedulerImpl] skips already-finished work.
     */
    fun requestBackfillPendingProcessing()
}
