package org.openlgx.roads.upload

import org.openlgx.roads.data.local.settings.AppSettings

interface SessionUploadScheduling {
    suspend fun scheduleAfterSessionCompleted(sessionId: Long, settings: AppSettings)

    /**
     * Enqueues [SessionUploadWorker] for every completed session with hosted state
     * [NOT_STARTED], [FAILED], [UPLOAD_SKIPPED], or [UPLOADING] (stale/crashed).
     * Manual operation — bypasses the road-pack gate so previously-skipped sessions
     * can be recovered. Uses REPLACE policy to unstick stale WorkManager entries.
     * @return number of work requests enqueued (0 if nothing to do or blocked by settings).
     */
    suspend fun enqueueBackfillAll(settings: AppSettings): Int

    /**
     * Manual upload for one session (bypasses "auto after session" toggle and road-pack gate).
     * Accepts sessions in [NOT_STARTED], [FAILED], [UPLOAD_SKIPPED], or [UPLOADING] state.
     * Uses REPLACE policy to unstick stale WorkManager entries.
     * @return null on success, or a user-facing error reason.
     */
    suspend fun enqueueSingleSession(sessionId: Long, settings: AppSettings): String?
}
