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

    override fun startCollectorService(sessionId: Long) {
        val intent =
            Intent(appContext, CollectorForegroundService::class.java).apply {
                action = CollectorForegroundService.ACTION_START
                putExtra(CollectorForegroundService.EXTRA_SESSION_ID, sessionId)
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
