package org.openlgx.roads.upload

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import org.openlgx.roads.data.local.db.RoadsDatabase
import org.openlgx.roads.data.local.db.model.SessionHostedPipelineState
import org.openlgx.roads.data.local.db.model.SessionState
import org.openlgx.roads.data.local.settings.AppSettings
import org.openlgx.roads.roadpack.RoadPackManager

@Singleton
class SessionUploadWorkScheduler
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val roadPackManager: RoadPackManager,
    private val roadsDatabase: RoadsDatabase,
) : SessionUploadScheduling {

    override suspend fun scheduleAfterSessionCompleted(sessionId: Long, settings: AppSettings) {
        if (!settings.uploadEnabled || !settings.uploadAutoAfterSessionEnabled) return
        if (shouldSkipForMissingRoadPack(settings)) {
            roadsDatabase.recordingSessionDao().updateHostedPipelineState(
                sessionId,
                SessionHostedPipelineState.UPLOAD_SKIPPED,
            )
            return
        }
        enqueueUploadWorkUnique(sessionId, settings)
    }

    override suspend fun enqueueBackfillAll(settings: AppSettings): Int {
        if (!settings.uploadEnabled) return 0
        if (!hasHostedCredentials(settings)) return 0

        val ids = roadsDatabase.recordingSessionDao().listSessionIdsEligibleForUpload()
        var enqueued = 0
        for (sessionId in ids) {
            enqueueUploadWorkUnique(sessionId, settings, replace = true)
            enqueued++
        }
        return enqueued
    }

    override suspend fun enqueueSingleSession(sessionId: Long, settings: AppSettings): String? {
        if (!settings.uploadEnabled) {
            return "Hosted upload is off — enable it in Settings first."
        }
        if (!hasHostedCredentials(settings)) {
            return "Missing base URL, API key, project id, or device id."
        }

        val row = roadsDatabase.recordingSessionDao().getById(sessionId)
            ?: return "Session not found."
        if (row.state != SessionState.COMPLETED) {
            return "Only completed sessions can be uploaded (state is ${row.state})."
        }
        val retryable = setOf(
            SessionHostedPipelineState.NOT_STARTED,
            SessionHostedPipelineState.FAILED,
            SessionHostedPipelineState.UPLOAD_SKIPPED,
            SessionHostedPipelineState.UPLOADING,
        )
        if (row.hostedPipelineState !in retryable) {
            return "Session is not pending upload (hosted state: ${row.hostedPipelineState})."
        }

        enqueueUploadWorkUnique(sessionId, settings, replace = true)
        return null
    }

    private fun shouldSkipForMissingRoadPack(settings: AppSettings): Boolean =
        settings.uploadRoadFilterEnabled &&
            settings.uploadRoadPackRequiredForAutoUpload &&
            !roadPackManager.hasPackForCouncil(settings.uploadCouncilSlug)

    private fun hasHostedCredentials(settings: AppSettings): Boolean =
        settings.uploadBaseUrl.isNotBlank() &&
            settings.uploadApiKey.isNotBlank() &&
            settings.uploadProjectId.isNotBlank() &&
            settings.uploadDeviceId.isNotBlank()

    private fun enqueueUploadWorkUnique(sessionId: Long, settings: AppSettings, replace: Boolean = false) {
        val networkType = when {
            settings.uploadWifiOnly && !settings.uploadAllowCellular -> NetworkType.UNMETERED
            else -> NetworkType.CONNECTED
        }
        val constraintsBuilder = Constraints.Builder()
            .setRequiredNetworkType(networkType)
        if (settings.uploadOnlyWhileCharging) {
            constraintsBuilder.setRequiresCharging(true)
        }
        if (settings.uploadPauseOnLowBatteryEnabled) {
            constraintsBuilder.setRequiresBatteryNotLow(true)
        }
        val constraints = constraintsBuilder.build()

        val initialDelay = if (settings.uploadChargingPreferred && !settings.uploadOnlyWhileCharging) {
            30L
        } else {
            0L
        }

        val request =
            OneTimeWorkRequestBuilder<SessionUploadWorker>()
                .setConstraints(constraints)
                .setInputData(workDataOf(SessionUploadWorker.KEY_SESSION_ID to sessionId))
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    30_000L,
                    TimeUnit.MILLISECONDS,
                )
                .setInitialDelay(initialDelay, TimeUnit.SECONDS)
                .addTag("upload-session-$sessionId")
                .addTag(TAG_SESSION_UPLOAD)
                .build()

        val policy = if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
        WorkManager.getInstance(context).enqueueUniqueWork(
            "upload-session-$sessionId",
            policy,
            request,
        )
    }

    companion object {
        /** Shared by all session upload workers — observe for transfer progress in Settings. */
        const val TAG_SESSION_UPLOAD = "org.openlgx.roads.session-upload"
    }
}
