package org.openlgx.roads.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CollectorForegroundServiceControllerImpl
@Inject
constructor(
    @ApplicationContext private val context: Context,
) : CollectorForegroundServiceController {

    private val appContext = context.applicationContext

    override fun startCollectorService() {
        val intent =
            Intent(appContext, CollectorForegroundService::class.java).apply {
                action = CollectorForegroundService.ACTION_START
            }
        ContextCompat.startForegroundService(appContext, intent)
    }

    override fun stopCollectorService() {
        val intent =
            Intent(appContext, CollectorForegroundService::class.java).apply {
                action = CollectorForegroundService.ACTION_STOP
            }
        appContext.startService(intent)
    }
}
