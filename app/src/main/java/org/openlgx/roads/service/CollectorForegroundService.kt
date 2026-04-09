package org.openlgx.roads.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import org.openlgx.roads.R

@AndroidEntryPoint
class CollectorForegroundService : Service() {

    @Inject lateinit var stateRegistry: CollectorServiceStateRegistry

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        stateRegistry.setForegroundRunning(true)
    }

    override fun onDestroy() {
        stateRegistry.setForegroundRunning(false)
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                val notification = buildNotification()
                startForeground(NOTIFICATION_ID, notification)
            }
        }
        return START_STICKY
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.collector_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.collector_notification_channel_description)
            }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        ensureChannel()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.collector_notification_title))
            .setContentText(getString(R.string.collector_notification_body))
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_START: String = "org.openlgx.roads.action.COLLECTOR_START"
        const val ACTION_STOP: String = "org.openlgx.roads.action.COLLECTOR_STOP"

        private const val CHANNEL_ID: String = "roads_collector_phase2a"
        private const val NOTIFICATION_ID: Int = 0xC010
    }
}
