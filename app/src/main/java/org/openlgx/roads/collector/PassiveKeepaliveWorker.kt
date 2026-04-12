package org.openlgx.roads.collector

import android.app.Application
import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.EntryPointAccessors
import org.openlgx.roads.di.PassiveCollectionEntryPoint

/**
 * Periodic WorkManager job so [PassiveCollectionCoordinator] re-registers Activity Recognition after
 * process death (OEM kill, idle). See [PassiveKeepaliveScheduler].
 */
@HiltWorker
class PassiveKeepaliveWorker
@AssistedInject
constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as Application
        val handle = EntryPointAccessors.fromApplication(app, PassiveCollectionEntryPoint::class.java)
            .passiveCollectionHandle()
        handle.start()
        handle.nudge()
        return Result.success()
    }
}
