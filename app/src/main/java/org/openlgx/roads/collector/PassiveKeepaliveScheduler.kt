package org.openlgx.roads.collector

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schedules [PassiveKeepaliveWorker] on a 15-minute cadence (WorkManager minimum) so passive
 * collection survives process death without a manual app open.
 */
@Singleton
class PassiveKeepaliveScheduler
@Inject
constructor(
    @ApplicationContext private val context: Context,
) {
    fun ensureScheduled() {
        val request =
            PeriodicWorkRequestBuilder<PassiveKeepaliveWorker>(KEEPALIVE_INTERVAL_MINUTES, TimeUnit.MINUTES)
                .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
    }

    companion object {
        const val UNIQUE_WORK_NAME: String = "passive_keepalive"
        private const val KEEPALIVE_INTERVAL_MINUTES: Long = 15L
    }
}
