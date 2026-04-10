package org.openlgx.roads.upload

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
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
import org.openlgx.roads.data.local.settings.AppSettings
import org.openlgx.roads.data.local.settings.AppSettingsRepository
import org.openlgx.roads.roadpack.RoadPackManager

@Singleton
class SessionUploadWorkScheduler
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val roadPackManager: RoadPackManager,
    private val roadsDatabase: RoadsDatabase,
    private val appSettingsRepository: AppSettingsRepository,
) : SessionUploadScheduling {

    override suspend fun scheduleAfterSessionCompleted(sessionId: Long, settings: AppSettings) {
        if (!settings.uploadEnabled || !settings.uploadAutoAfterSessionEnabled) return
        if (settings.uploadRoadPackRequiredForAutoUpload &&
            !roadPackManager.hasPackForCouncil(settings.uploadCouncilSlug)
        ) {
            roadsDatabase.recordingSessionDao().updateHostedPipelineState(
                sessionId,
                SessionHostedPipelineState.UPLOAD_SKIPPED,
            )
            return
        }

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
                .build()

        WorkManager.getInstance(context).enqueue(request)
    }
}
